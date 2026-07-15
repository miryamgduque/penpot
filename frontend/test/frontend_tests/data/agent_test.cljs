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
