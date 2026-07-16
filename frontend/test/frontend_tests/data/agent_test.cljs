;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.agent-test
  "Guards the canonical history invariant that both providers enforce:
  every tool call the assistant makes must have a matching result in the
  next message, or the *next* request is rejected outright.

  This is worth testing precisely because it fails silently and late — a
  cancel that leaves a dangling call produces no error at cancel time, only
  a 400 on the user's next message, surfacing as a generic error bubble with
  nothing pointing back at the cancel."
  (:require
   [app.main.data.workspace.agent :as agent]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]))

(def ^:private turn-with-dangling-calls
  [{:role :user :text "make the button blue"}
   {:role :assistant
    :text "On it."
    :tool-calls [{:id "call_1" :name "read_design" :input {}}
                 {:id "call_2" :name "apply_tokens" :input {:token "blue"}}]}])

;; ---------------------------------------------------------------------------
;; result->content — the oversize backstop
;;
;; It used to `(subs s 0 20000)` a JSON string, which cuts mid-token and hands
;; the model unparseable JSON with no marker: it could not tell a truncated
;; result from a complete one, and would read a partial list as the whole file.
;; ---------------------------------------------------------------------------

(t/deftest a-normal-result-passes-through-unchanged
  (let [s (agent/result->content {:ok true :note "fine"})]
    (t/is (= {"ok" true "note" "fine"} (js->clj (js/JSON.parse s))))))

(t/deftest an-oversized-result-is-still-valid-json
  (let [huge (apply str (repeat 30000 "x"))
        s    (agent/result->content {:blob huge})]
    ;; the point: parsing must not throw
    (t/is (map? (js->clj (js/JSON.parse s))))))

(t/deftest an-oversized-result-says-it-was-not-returned
  (let [huge (apply str (repeat 30000 "x"))
        out  (js->clj (js/JSON.parse (agent/result->content {:blob huge})) :keywordize-keys true)]
    (t/is (true? (:truncated out)))
    (t/is (str/includes? (str/lower (:error out)) "narrow"))))

(t/deftest an-oversized-result-reports-its-real-size
  (let [huge (apply str (repeat 30000 "x"))
        out  (js->clj (js/JSON.parse (agent/result->content {:blob huge})) :keywordize-keys true)]
    (t/is (> (:chars out) 20000))))

(t/deftest the-backstop-never-emits-a-partial-prefix
  ;; The old behaviour: the first 20k chars of the real payload. If any of the
  ;; blob survives, a model could read it as real data.
  (let [huge (apply str (repeat 30000 "x"))
        s    (agent/result->content {:blob huge})]
    (t/is (< (count s) 1000))
    (t/is (not (str/includes? s (apply str (repeat 100 "x")))))))

;; ---------------------------------------------------------------------------
;; cancel-history
;; ---------------------------------------------------------------------------

(t/deftest cancel-history-closes-dangling-tool-calls
  (t/testing "a cancelled turn gets a synthesized result for every unanswered call"
    (let [out  (agent/cancel-history turn-with-dangling-calls)
          last (peek out)]
      (t/is (= 3 (count out)))
      (t/is (= :tool-results (:role last)))
      (t/is (= ["call_1" "call_2"] (mapv :id (:results last))))
      (t/is (every? :error? (:results last)))
      (t/is (every? #(string? (:content %)) (:results last))))))

(t/deftest cancel-history-leaves-answered-turns-alone
  (t/testing "nothing dangling — history is returned untouched"
    (let [history (conj turn-with-dangling-calls
                        {:role :tool-results
                         :results [{:id "call_1" :content "{}"}
                                   {:id "call_2" :content "{}"}]})]
      (t/is (= history (agent/cancel-history history))))))

(t/deftest cancel-history-ignores-text-only-assistant
  (t/testing "an assistant message with no tool calls needs no closing"
    (let [history [{:role :user :text "hi"}
                   {:role :assistant :text "hello" :tool-calls []}]]
      (t/is (= history (agent/cancel-history history))))))

(t/deftest cancel-history-on-user-only-history
  (t/testing "cancelled before the model replied — the user message survives"
    (let [history [{:role :user :text "hi"}]]
      (t/is (= history (agent/cancel-history history))))))

(t/deftest cancel-history-handles-empty
  (t/is (= [] (agent/cancel-history []))))

;; ---------------------------------------------------------------------------
;; The wire invariant, per provider. This is the assertion that matters:
;; the canonical fix has to satisfy BOTH encoders.
;; ---------------------------------------------------------------------------

(defn- anthropic-pairs
  "→ [tool_use ids, tool_result ids] as encoded for Anthropic."
  [messages]
  (let [encoded (agent/encode-anthropic messages)
        blocks  (mapcat :content encoded)]
    [(->> blocks (filter #(= "tool_use" (:type %))) (mapv :id))
     (->> blocks (filter #(= "tool_result" (:type %))) (mapv :tool_use_id))]))

(t/deftest anthropic-every-tool-use-has-a-result-after-cancel
  (t/testing "no `tool_use ids were found without tool_result blocks` 400"
    (let [[uses results] (anthropic-pairs (agent/cancel-history turn-with-dangling-calls))]
      (t/is (= 2 (count uses)))
      (t/is (= uses results)))))

(t/deftest anthropic-uncancelled-dangling-would-be-rejected
  (t/testing "sanity: without the fix the encoded shape really is unbalanced"
    (let [[uses results] (anthropic-pairs turn-with-dangling-calls)]
      (t/is (= 2 (count uses)))
      (t/is (empty? results)))))

(defn- openai-pairs
  "→ [tool_call ids, tool-message ids] as encoded for OpenAI."
  [messages]
  (let [encoded (agent/encode-openai "sys" messages)]
    [(->> encoded (mapcat :tool_calls) (filterv some?) (mapv :id))
     (->> encoded (filter #(= "tool" (:role %))) (mapv :tool_call_id))]))

(t/deftest openai-every-tool-call-has-a-result-after-cancel
  (t/testing "no unmatched tool_call_id 400"
    (let [[calls results] (openai-pairs (agent/cancel-history turn-with-dangling-calls))]
      (t/is (= 2 (count calls)))
      (t/is (= calls results)))))

;; ---------------------------------------------------------------------------
;; Streaming accumulators — they must rebuild exactly what the buffered
;; decoders used to return, since the turn loop still branches on that shape.
;; ---------------------------------------------------------------------------

(defn- fold-anthropic
  [frames]
  (reduce (fn [[acc texts] frame]
            (let [[acc' text] (agent/accumulate-anthropic acc frame)]
              [acc' (cond-> texts text (conj text))]))
          [agent/empty-accumulator []]
          frames))

(def ^:private anthropic-frames
  [{:type "message_start"
    :message {:usage {:input_tokens 10 :cache_read_input_tokens 4 :cache_creation_input_tokens 2}}}
   {:type "content_block_start" :index 0 :content_block {:type "text"}}
   {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "Hel"}}
   {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "lo"}}
   {:type "content_block_stop" :index 0}
   {:type "content_block_start" :index 1
    :content_block {:type "tool_use" :id "call_1" :name "read_design"}}
   {:type "content_block_delta" :index 1 :delta {:type "input_json_delta" :partial_json "{\"a\":"}}
   {:type "content_block_delta" :index 1 :delta {:type "input_json_delta" :partial_json "1}"}}
   {:type "content_block_stop" :index 1}
   {:type "ping"}
   {:type "message_delta" :delta {:stop_reason "tool_use"} :usage {:output_tokens 25}}
   {:type "message_stop"}])

(t/deftest anthropic-accumulator-rebuilds-text-tools-and-usage
  (let [[acc texts] (fold-anthropic anthropic-frames)
        outcome     (agent/accumulator->outcome acc true)]
    (t/testing "text is reassembled from its deltas"
      (t/is (= "Hello" (:text outcome)))
      (t/is (= ["Hel" "lo"] texts) "each text delta is surfaced for live rendering"))
    (t/testing "tool call json is reassembled across partial_json fragments"
      (t/is (= [{:id "call_1" :name "read_design" :input {:a 1}}] (:tool-calls outcome))))
    (t/testing "usage merges message_start and message_delta"
      (t/is (= {:input-tokens 10 :output-tokens 25 :cache-read-tokens 4
                :cache-write-tokens 2 :requests 1}
               (:usage outcome))))
    (t/is (false? (:stopped-for-length? outcome)))))

(t/deftest anthropic-accumulator-flags-length-stop
  (let [frames (conj (vec (butlast anthropic-frames))
                     {:type "message_delta" :delta {:stop_reason "max_tokens"} :usage {:output_tokens 5}})
        [acc _] (fold-anthropic frames)]
    (t/is (true? (:stopped-for-length? (agent/accumulator->outcome acc true))))))

(t/deftest anthropic-accumulator-tool-with-no-arguments
  (t/testing "a no-arg tool streams zero json deltas — must not blow up on \"\""
    (let [frames [{:type "content_block_start" :index 0
                   :content_block {:type "tool_use" :id "c1" :name "audit_file"}}
                  {:type "content_block_stop" :index 0}]
          [acc _] (fold-anthropic frames)]
      (t/is (= [{:id "c1" :name "audit_file" :input {}}]
               (:tool-calls (agent/accumulator->outcome acc true)))))))

(defn- fold-openai
  [frames]
  (reduce (fn [[acc texts] frame]
            (let [[acc' text] (agent/accumulate-openai acc frame)]
              [acc' (cond-> texts text (conj text))]))
          [agent/empty-accumulator []]
          frames))

(t/deftest openai-accumulator-rebuilds-text-tools-and-usage
  (let [frames [{:choices [{:delta {:content "Hi"}}]}
                {:choices [{:delta {:content " there"}}]}
                {:choices [{:delta {:tool_calls [{:index 0 :id "call_9"
                                                  :function {:name "apply_tokens" :arguments "{\"t\":"}}]}}]}
                ;; id/name only arrive on the first fragment per index
                {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "\"blue\"}"}}]}}]}
                {:choices [{:delta {} :finish_reason "tool_calls"}]}
                ;; the include_usage tail chunk: no choices, just usage
                {:choices [] :usage {:prompt_tokens 7 :completion_tokens 11
                                     :prompt_tokens_details {:cached_tokens 3}}}]
        [acc texts] (fold-openai frames)
        outcome     (agent/accumulator->outcome acc false)]
    (t/is (= "Hi there" (:text outcome)))
    (t/is (= ["Hi" " there"] texts))
    (t/is (= [{:id "call_9" :name "apply_tokens" :input {:t "blue"}}] (:tool-calls outcome)))
    (t/is (= {:input-tokens 7 :output-tokens 11 :cache-read-tokens 3
              :cache-write-tokens 0 :requests 1}
             (:usage outcome)))))

(t/deftest openai-accumulator-flags-length-stop
  (let [[acc _] (fold-openai [{:choices [{:delta {:content "x"} :finish_reason "length"}]}])]
    (t/is (true? (:stopped-for-length? (agent/accumulator->outcome acc false))))))

;; ---------------------------------------------------------------------------
;; trim-history must not undo the fix
;; ---------------------------------------------------------------------------

(t/deftest trim-history-keeps-the-pair-cancel-just-closed
  (t/testing "trimming a long history never splits tool_use from its results"
    (let [filler  (vec (mapcat (fn [i]
                                 [{:role :user :text (str "q" i)}
                                  {:role :assistant :text (str "a" i) :tool-calls []}])
                               (range 40)))
          history (agent/cancel-history (into filler turn-with-dangling-calls))
          trimmed (agent/trim-history history)
          [uses results] (anthropic-pairs trimmed)]
      (t/is (<= (count trimmed) 40))
      (t/is (= uses results) "every tool_use still has its tool_result")
      (t/is (= :user (:role (first trimmed))) "a turn must start at a user message"))))

;; ---------------------------------------------------------------------------
;; Image blocks on the user message.
;;
;; The dialects genuinely diverge, which is why the canonical model stores raw
;; base64 + mimetype and lets each encoder assemble its own shape:
;;   Anthropic → {:type "image" :source {:type "base64" :media_type … :data …}}
;;   OpenAI    → {:type "image_url" :image_url {:url "data:…;base64,…"}}
;; Getting this wrong fails at the provider, not here — hence the assertions on
;; the exact wire shape rather than on "an image is present somewhere".
;; ---------------------------------------------------------------------------

(def ^:private png {:mtype "image/png" :data "iVBORw0KGgo="})
(def ^:private jpg {:mtype "image/jpeg" :data "/9j/4AAQSkZJRg=="})

(defn- a-content [message] (:content (first (agent/encode-anthropic [message]))))
(defn- o-content [message] (:content (second (agent/encode-openai "sys" [message]))))

(t/deftest user-without-images-stays-a-plain-string
  (t/testing "the no-image fast path is untouched — this is the common case"
    (let [m {:role :user :text "make it blue"}]
      (t/is (= "make it blue" (a-content m)))
      (t/is (= "make it blue" (o-content m))))))

(t/deftest user-with-empty-images-stays-a-plain-string
  (t/testing "an empty :images vector must not promote content to blocks"
    (let [m {:role :user :text "hi" :images []}]
      (t/is (string? (a-content m)))
      (t/is (string? (o-content m))))))

(t/deftest anthropic-encodes-an-image-block
  (let [content (a-content {:role :user :text "what is this?" :images [png]})]
    (t/is (= [{:type "image"
               :source {:type "base64" :media_type "image/png" :data "iVBORw0KGgo="}}
              {:type "text" :text "what is this?"}]
             content)
          "bare base64 + separate media_type, image first")))

(t/deftest openai-encodes-an-image-url-block
  (let [content (o-content {:role :user :text "what is this?" :images [png]})]
    (t/is (= [{:type "image_url"
               :image_url {:url "data:image/png;base64,iVBORw0KGgo="}}
              {:type "text" :text "what is this?"}]
             content)
          "a full data: URI, not a bare base64 payload")))

(t/deftest images-precede-the-text-in-both-dialects
  (t/testing "providers read a trailing question as being about the images above it"
    (let [m {:role :user :text "compare these" :images [png jpg]}]
      (t/is (= ["image" "image" "text"] (mapv :type (a-content m))))
      (t/is (= ["image_url" "image_url" "text"] (mapv :type (o-content m)))))))

(t/deftest five-images-all-survive-encoding
  (t/testing "5 is the product cap; the encoder must not silently drop any"
    (let [imgs (mapv (fn [i] {:mtype "image/png" :data (str "data" i)}) (range 5))
          m    {:role :user :text "look" :images imgs}]
      (t/is (= 5 (count (filter #(= "image" (:type %)) (a-content m)))))
      (t/is (= 5 (count (filter #(= "image_url" (:type %)) (o-content m)))))
      (t/is (= ["data0" "data1" "data2" "data3" "data4"]
               (->> (a-content m) (filter #(= "image" (:type %))) (mapv #(get-in % [:source :data]))))
            "order is preserved — the user's 1st image stays 1st"))))

(t/deftest image-without-text-emits-no-empty-text-block
  (t/testing "Anthropic rejects an empty text block, so it must be omitted entirely"
    (let [m {:role :user :text "" :images [png]}]
      (t/is (= ["image"] (mapv :type (a-content m))))
      (t/is (= ["image_url"] (mapv :type (o-content m)))))))

(t/deftest image-message-still-carries-the-design-context
  (t/testing "context rides the text block, exactly as it does without images"
    (let [m {:role :user :text "fix this" :context {:file "f" :selection ["a"]}
             :images [png]}
          a-text (->> (a-content m) (filter #(= "text" (:type %))) first :text)]
      (t/is (str/includes? a-text "Current design context"))
      (t/is (str/includes? a-text "fix this")))))

(t/deftest images-do-not-leak-onto-other-roles
  (t/testing "only the user message grows images; assistant/tool shapes are unchanged"
    (let [history [{:role :user :text "hi" :images [png]}
                   {:role :assistant :text "ok" :tool-calls []}
                   {:role :tool-results :results [{:id "c1" :content "{}"}]}]
          encoded (agent/encode-anthropic history)]
      (t/is (= [{:type "text" :text "ok"}] (:content (second encoded))))
      (t/is (= "tool_result" (:type (first (:content (nth encoded 2)))))))))

(t/deftest trim-history-preserves-images
  (t/testing "trimming cuts at user messages — it must not strip their images"
    (let [filler  (vec (mapcat (fn [i]
                                 [{:role :user :text (str "q" i)}
                                  {:role :assistant :text (str "a" i) :tool-calls []}])
                               (range 40)))
          history (conj filler {:role :user :text "look" :images [png]})
          trimmed (agent/trim-history history)]
      (t/is (= [png] (:images (peek trimmed)))))))

;; ---------------------------------------------------------------------------
;; Degrading to a text-only model.
;;
;; The subtle part: history is re-encoded from canonical on EVERY round, so
;; switching to a text-only model mid-conversation re-encodes images the user
;; sent five turns ago. Stripping has to apply to the whole history, not just
;; the next message, or the switch fails the entire conversation.
;; ---------------------------------------------------------------------------

(t/deftest strip-images-removes-every-image-in-the-history
  (let [history [{:role :user :text "look at this" :images [png]}
                 {:role :assistant :text "ok" :tool-calls []}
                 {:role :user :text "and this" :images [png jpg]}]
        out     (agent/strip-images history)]
    (t/is (every? (comp nil? :images) out))
    (t/is (= ["image" "image" "image"]
             (->> (agent/encode-anthropic history)
                  (mapcat :content)
                  (filterv map?)
                  (filterv #(= "image" (:type %)))
                  (mapv :type)))
          "sanity: the un-stripped history really does carry 3 images")
    (t/is (empty? (->> (agent/encode-anthropic out)
                       (mapcat :content)
                       (filterv map?)
                       (filterv #(= "image" (:type %)))))
          "and the stripped one carries none")))

(t/deftest strip-images-leaves-a-note-in-their-place
  (t/testing "a silent disappearance invites the model to confabulate what it saw"
    (let [out  (agent/strip-images [{:role :user :text "what is this?" :images [png]}])
          text (:text (first out))]
      (t/is (str/includes? text "what is this?") "the user's own words survive")
      (t/is (str/includes? text "omitted") "and the model is told an image was there"))))

(t/deftest strip-images-notes-the-count
  (let [out (agent/strip-images [{:role :user :text "compare" :images [png jpg]}])]
    (t/is (str/includes? (:text (first out)) "2"))))

(t/deftest strip-images-handles-an-image-with-no-text
  (t/testing "the note becomes the whole message rather than a dangling blank line"
    (let [out (agent/strip-images [{:role :user :text "" :images [png]}])]
      (t/is (str/starts-with? (:text (first out)) "["))
      (t/is (not (str/starts-with? (:text (first out)) "\n"))))))

(t/deftest strip-images-leaves-image-free-history-untouched
  (t/testing "the common case must be a no-op — not a rebuilt equal-ish value"
    (let [history [{:role :user :text "hi"}
                   {:role :assistant :text "hello" :tool-calls []}
                   {:role :tool-results :results [{:id "c1" :content "{}"}]}]]
      (t/is (= history (agent/strip-images history))))))

;; ---------------------------------------------------------------------------
;; Pruning images out of an ageing history.
;;
;; Every round re-encodes the WHOLE conversation, so an image sent on turn 1 is
;; re-uploaded on turn 12. Five downscaled screenshots are cheap once and
;; ruinous twelve times, and the failure arrives with no new user action — the
;; conversation simply stops working. This is the second of the two leaks;
;; downscale-on-attach is the first.
;; ---------------------------------------------------------------------------

(defn- user-turn
  [n & {:keys [images]}]
  [{:role :user :text (str "turn " n) :images images}
   {:role :assistant :text (str "reply " n) :tool-calls []}])

(defn- images-per-turn
  "→ image count per user message, oldest first."
  [messages]
  (->> messages
       (filter #(= :user (:role %)))
       (mapv #(count (:images %)))))

(t/deftest prune-keeps-the-recent-turns-images
  (t/testing "a short conversation is untouched — the common case must not pay"
    (let [history (vec (mapcat #(user-turn % :images [png]) (range 2)))]
      (t/is (= history (agent/prune-history-images history))))))

(t/deftest prune-drops-images-from-older-turns
  (let [history (vec (mapcat #(user-turn % :images [png jpg]) (range 5)))
        out     (agent/prune-history-images history)]
    (t/testing "only the most recent turns keep their images"
      (t/is (= [0 0 0 2 2] (images-per-turn out))))
    (t/testing "and the stripped ones say so rather than vanishing"
      (t/is (str/includes? (:text (first out)) "omitted"))
      (t/is (str/includes? (:text (first out)) "turn 0") "the user's words survive"))))

(t/deftest prune-leaves-the-newest-turn-alone
  (t/testing "the turn being asked about right now must keep its picture"
    (let [history (vec (mapcat #(user-turn % :images [png]) (range 6)))
          out     (agent/prune-history-images history)
          newest  (last (filter #(= :user (:role %)) out))]
      (t/is (= [png] (:images newest))))))

(t/deftest prune-is-a-no-op-without-images
  (t/testing "a text-only conversation must come back identical, not rebuilt"
    (let [history (vec (mapcat #(user-turn %) (range 8)))]
      (t/is (= history (agent/prune-history-images history))))))

(t/deftest prune-counts-turns-not-messages
  (t/testing "a turn with many tool rounds is still ONE turn — counting raw
              messages would strip the current turn's own image"
    (let [history (into [{:role :user :text "look" :images [png]}]
                        (mapcat (fn [i]
                                  [{:role :assistant :text "" :tool-calls [{:id (str "c" i) :name "read_design" :input {}}]}
                                   {:role :tool-results :results [{:id (str "c" i) :content "{}"}]}])
                                (range 10)))
          out     (agent/prune-history-images history)]
      (t/is (= [1] (images-per-turn out))))))

(t/deftest prune-then-encode-drops-the-blocks
  (t/testing "the point of all this: old images leave the wire"
    (let [history (vec (mapcat #(user-turn % :images [png]) (range 5)))
          blocks  (->> (agent/encode-anthropic (agent/prune-history-images history))
                       (mapcat :content)
                       (filter map?)
                       (filter #(= "image" (:type %))))]
      (t/is (= 2 (count blocks)) "5 turns of images encode down to the recent 2"))))

;; ---------------------------------------------------------------------------
;; History hygiene: stubbing stale tool results.
;;
;; A tool result is the deadest weight a history carries: a read_design dump
;; from five turns ago is almost never re-read, but — the whole history being
;; re-encoded every round — it is re-sent forever. Old, large results get their
;; CONTENT replaced by a stub; the message structure (ids, pairing) stays, so
;; neither provider's tool_use/tool_result invariant breaks. The thresholds are
;; the retired TS app's proven values (>400 chars, older than the last 8
;; messages).
;; ---------------------------------------------------------------------------

(def ^:private big-result (apply str (repeat 1000 "x")))

(defn- turn-with-result
  "user → assistant tool call → its result (3 messages)."
  [n content]
  [{:role :user :text (str "q" n)}
   {:role :assistant :text "" :tool-calls [{:id (str "c" n) :name "read_design" :input {}}]}
   {:role :tool-results :results [{:id (str "c" n) :content content}]}])

(t/deftest stubbing-clears-old-large-results
  (let [history (vec (mapcat #(turn-with-result % big-result) (range 4)))
        out     (agent/stub-stale-tool-results history)
        oldest  (first (:results (nth out 2)))]
    (t/testing "an old result's content is replaced, not truncated"
      (t/is (< (count (:content oldest)) 200))
      (t/is (str/includes? (:content oldest) "cleared")))
    (t/testing "its id survives — the pairing must not break"
      (t/is (= "c0" (:id oldest))))))

(t/deftest stubbing-spares-the-recent-tail
  (t/testing "the last 8 messages keep their results verbatim — the model may
              still be reading what it just asked for"
    (let [history (vec (mapcat #(turn-with-result % big-result) (range 4)))
          out     (agent/stub-stale-tool-results history)
          newest  (first (:results (peek out)))]
      (t/is (= big-result (:content newest))))))

(t/deftest stubbing-spares-small-results
  (t/testing "a small result costs nothing and may carry a fact — left alone"
    (let [history (vec (mapcat #(turn-with-result % "{\"ok\":true}") (range 4)))]
      (t/is (= history (agent/stub-stale-tool-results history))))))

(t/deftest stubbing-a-short-history-is-a-no-op
  (let [history (vec (turn-with-result 0 big-result))]
    (t/is (= history (agent/stub-stale-tool-results history)))))

(t/deftest stubbing-keeps-the-wire-invariant
  (t/testing "after stubbing, every tool_use still has its tool_result"
    (let [history (vec (mapcat #(turn-with-result % big-result) (range 4)))
          [uses results] (anthropic-pairs (agent/stub-stale-tool-results history))]
      (t/is (= 4 (count uses)))
      (t/is (= uses results)))))

(t/deftest stubbing-preserves-the-error-flag
  (t/testing "an old FAILED result stays marked as an error when stubbed"
    (let [history (into (vec (mapcat #(turn-with-result % "{}") (range 3)))
                        [{:role :user :text "old"}
                         {:role :assistant :text "" :tool-calls [{:id "cE" :name "audit_file" :input {}}]}
                         {:role :tool-results :results [{:id "cE" :content big-result :error? true}]}
                         ;; 8 filler messages push the error result out of the tail
                         {:role :user :text "f1"} {:role :assistant :text "r1" :tool-calls []}
                         {:role :user :text "f2"} {:role :assistant :text "r2" :tool-calls []}
                         {:role :user :text "f3"} {:role :assistant :text "r3" :tool-calls []}
                         {:role :user :text "f4"} {:role :assistant :text "r4" :tool-calls []}])
          out    (agent/stub-stale-tool-results history)
          erred  (->> out (filter #(= :tool-results (:role %)))
                      (mapcat :results)
                      (filter #(= "cE" (:id %)))
                      (first))]
      (t/is (true? (:error? erred)))
      (t/is (str/includes? (:content erred) "cleared")))))

;; ---------------------------------------------------------------------------
;; History hygiene: the char budget on trim-history.
;;
;; The 40-MESSAGE cap bounded nothing real: a message can be a 20k-char tool
;; result, so 40 of them is 800k chars. The budget bounds actual size, with the
;; same never-split-a-turn rule, and the newest turn is kept whole even when it
;; alone exceeds the budget — a turn the model is mid-way through must never
;; lose its own context.
;; ---------------------------------------------------------------------------

(t/deftest trim-cuts-by-size-not-just-count
  (t/testing "12 messages but ~200k chars — the old cap would keep all of it"
    (let [history (vec (mapcat #(turn-with-result % (apply str (repeat 50000 "y"))) (range 4)))
          out     (agent/trim-history history)]
      (t/is (< (count out) (count history)))
      (t/is (= :user (:role (first out))) "cut lands on a turn start"))))

(t/deftest trim-keeps-a-small-history-identical
  (t/testing "under both caps → the very same value, not a rebuilt copy"
    (let [history (vec (mapcat #(turn-with-result % "{\"ok\":true}") (range 4)))]
      (t/is (= history (agent/trim-history history))))))

(t/deftest trim-never-splits-the-newest-turn
  (t/testing "one turn alone over the budget survives whole — never split"
    (let [history (vec (turn-with-result 0 (apply str (repeat 100000 "z"))))
          out     (agent/trim-history history)]
      (t/is (= history out)))))

(t/deftest trim-budget-keeps-the-wire-invariant
  (let [history (vec (mapcat #(turn-with-result % (apply str (repeat 30000 "w"))) (range 5)))
        [uses results] (anthropic-pairs (agent/trim-history history))]
    (t/is (= uses results) "no dangling tool_use after a budget cut")
    (t/is (pos? (count uses)) "something survived the cut")))

;; ---------------------------------------------------------------------------
;; Auto-compaction (the pure halves).
;;
;; Past a size threshold the history is replaced by [one summary message + the
;; last turn]. The summarizer call itself follows detect-round's pattern and is
;; exercised live; what is pinned here is the split — everything before the
;; last turn goes, the last turn survives INTACT (its tool pairs included),
;; and the summary arrives as a user message flagged :compacted?.
;; ---------------------------------------------------------------------------

(t/deftest compaction-not-due-under-the-threshold
  (let [history (vec (mapcat #(turn-with-result % "{\"ok\":true}") (range 4)))]
    (t/is (false? (boolean (agent/compact-due? history))))))

(t/deftest compaction-due-over-the-threshold
  (let [history (vec (mapcat #(turn-with-result % (apply str (repeat 40000 "x"))) (range 4)))]
    (t/is (true? (boolean (agent/compact-due? history))))))

(t/deftest compacted-history-is-summary-plus-last-turn
  (let [history (vec (mapcat #(turn-with-result % big-result) (range 4)))
        out     (agent/compacted-history history "the summary")]
    (t/testing "one summary message replaces every earlier turn"
      (t/is (= 4 (count out)) "summary + the last turn's 3 messages")
      (t/is (= :user (:role (first out))))
      (t/is (true? (:compacted? (first out))))
      (t/is (str/includes? (:text (first out)) "the summary"))
      (t/is (str/includes? (str/lower (:text (first out))) "compacted")
            "the message says what it is — the model must not read it as the user's words"))
    (t/testing "the last turn survives verbatim, pairs intact"
      (t/is (= (subvec history 9) (subvec out 1)))
      (let [[uses results] (anthropic-pairs out)]
        (t/is (= uses results))))))

(t/deftest compacted-history-keeps-a-lone-turn-whole
  (t/testing "a history that is one giant turn has no head to compact away"
    (let [history (vec (turn-with-result 0 big-result))]
      (t/is (= history (agent/compacted-history history "s"))))))

(t/deftest compaction-transcript-drops-images
  (t/testing "the summarizer reads text — base64 would be pure cost"
    (let [history [{:role :user :text "look" :images [png]}
                   {:role :assistant :text "ok" :tool-calls []}
                   {:role :user :text "more"}]
          text    (agent/compaction-transcript history)]
      (t/is (not (str/includes? text "iVBOR")))
      (t/is (str/includes? text "look"))
      (t/is (str/includes? text "ok")))))

;; ---------------------------------------------------------------------------
;; The runaway brake (checkpoint-due?).
;;
;; A turn that has run 12 tool rounds — or spent ~$1 — pauses for the user's
;; go-ahead instead of grinding on (a model once spent $4 building an
;; unrequested landing page; the meter showed it, nothing acted on it). The
;; thresholds are per-SEGMENT: a resumed turn earns a fresh allowance, so the
;; pause never re-fires immediately after Continue.
;; ---------------------------------------------------------------------------

(def ^:private no-spend agent/empty-usage)

(t/deftest checkpoint-not-due-early
  (t/is (false? (boolean (agent/checkpoint-due? "claude-sonnet-5" 3 no-spend)))))

(t/deftest checkpoint-due-at-the-round-cap
  (t/is (true? (boolean (agent/checkpoint-due? "claude-sonnet-5" 12 no-spend)))))

(t/deftest checkpoint-never-fires-before-the-first-round
  (t/testing "round 0 = nothing has run in this segment yet — even a resumed
              turn with heavy prior spend must not re-pause instantly"
    (let [heavy {:input-tokens 1000000 :output-tokens 1000000
                 :cache-read-tokens 0 :cache-write-tokens 0 :requests 30}]
      (t/is (false? (boolean (agent/checkpoint-due? "claude-sonnet-5" 0 heavy)))))))

(t/deftest checkpoint-due-on-spend-alone
  (t/testing "a few expensive rounds trip the brake before the round cap"
    ;; 80k output on opus-4-8 at $25/M = $2 — over the $1 threshold
    (let [spent {:input-tokens 10000 :output-tokens 80000
                 :cache-read-tokens 0 :cache-write-tokens 0 :requests 3}]
      (t/is (true? (boolean (agent/checkpoint-due? "claude-opus-4-8" 3 spent)))))))

(t/deftest checkpoint-unpriced-model-uses-rounds-only
  (t/testing "no price entry → no cost estimate → the round cap is the brake"
    (let [spent {:input-tokens 9000000 :output-tokens 9000000
                 :cache-read-tokens 0 :cache-write-tokens 0 :requests 3}]
      (t/is (false? (boolean (agent/checkpoint-due? "some-unpriced-model" 3 spent))))
      (t/is (true? (boolean (agent/checkpoint-due? "some-unpriced-model" 12 spent)))))))

;; ---------------------------------------------------------------------------
;; The history cache breakpoint.
;;
;; The system block's `cache_control` marker caches tools+system only — the
;; message history behind it was re-sent UNcached on every round, and a turn is
;; up to 32 rounds over a history that grows each round. Marking the last block
;; of the last message extends the cached prefix over the whole conversation:
;; each round re-reads prior rounds at ~0.1× and pays the write only on its own
;; tail. These tests pin where the marker lands, because a marker on the wrong
;; block silently caches nothing — the request succeeds either way.
;; ---------------------------------------------------------------------------

(defn- marked-blocks
  "All blocks carrying a cache_control marker, across the encoded history."
  [encoded]
  (->> encoded
       (mapcat #(let [c (:content %)] (if (vector? c) c [])))
       (filterv :cache_control)))

(t/deftest breakpoint-lands-on-the-last-block-of-the-last-message
  (let [history [{:role :user :text "hi"}
                 {:role :assistant :text "hello" :tool-calls []}
                 {:role :user :text "make it blue"}]
        out     (agent/mark-history-breakpoint (agent/encode-anthropic history))]
    (t/testing "exactly one marker in the whole history"
      (t/is (= 1 (count (marked-blocks out)))))
    (t/testing "it is the final block of the final message"
      (let [last-content (:content (peek out))]
        (t/is (vector? last-content) "a string content is promoted to blocks to carry it")
        (t/is (= {:type "ephemeral"} (:cache_control (peek last-content))))
        (t/is (= "make it blue" (:text (peek last-content)) ))))
    (t/testing "earlier messages are untouched"
      (t/is (= (agent/encode-anthropic (butlast history))
               (vec (butlast out)))))))

(t/deftest breakpoint-marks-a-tool-result-tail
  (t/testing "mid-turn the last message is tool-results — the common case"
    (let [history (conj turn-with-dangling-calls
                        {:role :tool-results
                         :results [{:id "call_1" :content "{}"}
                                   {:id "call_2" :content "{}"}]})
          out     (agent/mark-history-breakpoint (agent/encode-anthropic history))
          blocks  (:content (peek out))]
      (t/is (= 1 (count (marked-blocks out))))
      (t/is (= "tool_result" (:type (peek blocks))))
      (t/is (= {:type "ephemeral"} (:cache_control (peek blocks))))
      (t/testing "its sibling result is not marked"
        (t/is (nil? (:cache_control (first blocks))))))))

(t/deftest breakpoint-never-creates-an-empty-text-block
  (t/testing "Anthropic rejects an empty text block — better unmarked than 400"
    (let [out (agent/mark-history-breakpoint
               (agent/encode-anthropic [{:role :user :text ""}]))]
      (t/is (empty? (marked-blocks out)))
      (t/is (= "" (:content (peek out))) "the message itself is left as it was"))))

(t/deftest breakpoint-on-empty-history-is-a-no-op
  (t/is (= [] (agent/mark-history-breakpoint []))))

(t/deftest breakpoint-marks-an-image-tail
  (t/testing "a user message of only images ends in an image block; marking it is
              valid and still extends the prefix over everything before it"
    (let [out (agent/mark-history-breakpoint
               (agent/encode-anthropic [{:role :user :text "" :images [png]}]))
          blocks (:content (peek out))]
      (t/is (= "image" (:type (peek blocks))))
      (t/is (= {:type "ephemeral"} (:cache_control (peek blocks)))))))

;; ---------------------------------------------------------------------------
;; Images coming back FROM a tool (render_board).
;;
;; A different seam from user attachments and a genuinely asymmetric one:
;; Anthropic's tool_result content is block-capable, so the render goes
;; straight back. The OpenAI dialect has nowhere to put an image in a `tool`
;; message at all — dropping it silently would leave the model answering as if
;; it had seen the picture, so it is told instead.
;; ---------------------------------------------------------------------------

(def ^:private render-result
  [{:role :user :text "does the card look right?"}
   {:role :assistant :text "" :tool-calls [{:id "c1" :name "render_board" :input {}}]}
   {:role :tool-results
    :results [{:id "c1"
               :content "{\"rendered\":[{\"name\":\"Card\"}]}"
               :images [png jpg]}]}])

(t/deftest anthropic-tool-result-carries-image-blocks
  (let [content (->> (agent/encode-anthropic render-result) (last) :content (first))]
    (t/is (= "tool_result" (:type content)))
    (t/is (= "c1" (:tool_use_id content)))
    (t/testing "text first, then the images it captions"
      (t/is (= ["text" "image" "image"] (mapv :type (:content content)))))
    (t/testing "bare base64 + separate media_type, same as a user image"
      (t/is (= {:type "base64" :media_type "image/png" :data "iVBORw0KGgo="}
               (:source (second (:content content))))))))

(t/deftest anthropic-tool-result-without-images-stays-a-string
  (t/testing "the overwhelmingly common case must not become blocks"
    (let [history [{:role :user :text "hi"}
                   {:role :assistant :text "" :tool-calls [{:id "c1" :name "read_design" :input {}}]}
                   {:role :tool-results :results [{:id "c1" :content "{}"}]}]
          content (->> (agent/encode-anthropic history) (last) :content (first))]
      (t/is (= "{}" (:content content))))))

(t/deftest openai-tool-result-says-the-image-could-not-be-shown
  (t/testing "silently dropping it would have the model describe what it never saw"
    (let [msg (->> (agent/encode-openai "sys" render-result)
                   (filter #(= "tool" (:role %)))
                   (first))]
      (t/is (str/includes? (:content msg) "rendered"))
      (t/is (str/includes? (:content msg) "cannot be shown"))
      (t/is (str/includes? (:content msg) "2") "says how many"))))

(t/deftest tool-result-images-are-stripped-for-a-blind-model
  (let [out (agent/strip-images render-result)
        res (first (:results (last out)))]
    (t/is (nil? (:images res)))
    (t/is (str/includes? (:content res) "omitted"))
    (t/testing "and nothing image-shaped survives to the wire"
      (t/is (empty? (->> (agent/encode-anthropic out)
                         (mapcat :content)
                         (filter map?)
                         (mapcat #(if (vector? (:content %)) (:content %) []))
                         (filter #(= "image" (:type %)))))))))

(t/deftest prune-drops-old-renders-too
  (t/testing "a render is an image like any other — left alone it is re-uploaded
              on every round for the life of the conversation"
    (let [old     (vec (mapcat (fn [i]
                                 [{:role :user :text (str "q" i)}
                                  {:role :assistant :text "" :tool-calls [{:id (str "c" i) :name "render_board" :input {}}]}
                                  {:role :tool-results :results [{:id (str "c" i) :content "{}" :images [png]}]}])
                               (range 5)))
          out     (agent/prune-history-images old)
          renders (->> out (filter #(= :tool-results (:role %)))
                       (mapcat :results)
                       (filter :images)
                       (count))]
      (t/is (= 2 renders) "only the last 2 turns keep their renders"))))
