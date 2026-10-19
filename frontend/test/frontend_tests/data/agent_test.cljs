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
   [app.main.data.workspace.agent-skills :as ask]
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

;; ---------------------------------------------------------------------------
;; Streaming accumulators — they must rebuild exactly what the buffered
;; decoders used to return, since the turn loop still branches on that shape.
;; ---------------------------------------------------------------------------

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

(t/deftest anthropic-encodes-an-image-block
  (let [content (a-content {:role :user :text "what is this?" :images [png]})]
    (t/is (= [{:type "image"
               :source {:type "base64" :media_type "image/png" :data "iVBORw0KGgo="}}
              {:type "text" :text "what is this?"}]
             content)
          "bare base64 + separate media_type, image first")))

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
;; The side-context runner (constructible/pure parts).
;;
;; run-side-turn is a bounded, READ-ONLY tool loop on a cheap model whose
;; whole conversation lives outside the main history. The allowlist is a
;; safety boundary, so it is enforced twice: at construction (a write tool in
;; the requested set is a code bug — fail fast) and per call (a model naming a
;; tool it was never offered gets an error result, not an execution).
;; ---------------------------------------------------------------------------

(t/deftest side-turn-rejects-write-tools-at-construction
  (t/is (thrown-with-msg? js/Error #"read-only"
                          (agent/run-side-turn {:model "m" :system "s" :user-text "u"
                                                :tools ["read_design" "create_shape"]}))))

(t/deftest side-turn-accepts-the-readonly-set
  (t/testing "constructing with only read tools returns an observable (no throw)"
    (t/is (some? (agent/run-side-turn {:model "m" :system "s" :user-text "u"
                                       :tools ["read_design" "find_shapes"
                                               "audit_file" "get_design_skills"]})))))

(defn- buffered-response
  [payload]
  {:status 200 :body (js/JSON.stringify (clj->js payload))})

(t/deftest decode-buffered-round-parses-text-tools-and-usage
  (let [out (agent/decode-buffered-round
             (buffered-response
              {:content [{:type "text" :text "Hel"}
                         {:type "text" :text "lo"}
                         {:type "tool_use" :id "c1" :name "read_design" :input {:depth 2}}]
               :usage {:input_tokens 10 :output_tokens 25
                       :cache_read_input_tokens 4 :cache_creation_input_tokens 2}}))]
    (t/is (= "Hello" (:text out)) "text blocks concatenate")
    (t/is (= [{:id "c1" :name "read_design" :input {:depth 2}}] (:tool-calls out)))
    (t/is (= {:input-tokens 10 :output-tokens 25 :cache-read-tokens 4
              :cache-write-tokens 2 :requests 1}
             (:usage out)))))

(t/deftest decode-buffered-round-throws-on-provider-error
  (t/is (thrown-with-msg? js/Error #"nope"
                          (agent/decode-buffered-round
                           {:status 400
                            :body (js/JSON.stringify
                                   (clj->js {:error {:message "nope"}}))}))))

;; ---------------------------------------------------------------------------
;; Auto-compaction (the pure halves).
;;
;; Past a size threshold the history is replaced by [one summary message + the
;; last turn]. The summarizer call itself follows detect-round's pattern and is
;; exercised live; what is pinned here is the split — everything before the
;; last turn goes, the last turn survives INTACT (its tool pairs included),
;; and the summary arrives as a user message flagged :compacted?.
;; ---------------------------------------------------------------------------

;; --- Direction nudge (phase 04 of the OSS-prompt-learnings plan)
;;
;; A first-message BUILD task in a file with no standing direction (no
;; foundations, no components, no tokens lib) gets the anti-generic nudge
;; injected into the user message — the volatile slot, never the prefix.

(defn- nudge-state
  [data]
  {:current-file-id "f1" :files {"f1" {:data data}}})

(t/deftest direction-nudge-fires-on-a-bare-file
  (t/is (some? (agent/direction-nudge (nudge-state {}) "penpot-build-screen"))))

(t/deftest direction-nudge-needs-a-build-playbook
  (t/is (nil? (agent/direction-nudge (nudge-state {}) "penpot-audit-accessibility")))
  (t/is (nil? (agent/direction-nudge (nudge-state {}) nil))))

(t/deftest direction-nudge-respects-existing-direction
  (t/testing "foundations, components or a tokens lib each silence it"
    (t/is (nil? (agent/direction-nudge
                 (nudge-state {:plugin-data {:penpot-vibes {"design-md" "# vibes"}}})
                 "penpot-build-screen")))
    (t/is (nil? (agent/direction-nudge
                 (nudge-state {:components {"c1" {:id "c1"}}})
                 "penpot-build-screen")))
    (t/is (nil? (agent/direction-nudge
                 (nudge-state {:tokens-lib :lib})
                 "penpot-build-screen")))))

(t/deftest direction-nudge-rides-the-user-message
  (let [msgs [{:role :user :text "build a pricing page"
               :direction-nudge "COMMIT TO A DIRECTION"}]
        body (get-in (agent/encode-anthropic msgs) [0 :content])]
    (t/is (str/includes? body "Visual direction"))
    (t/is (str/includes? body "COMMIT TO A DIRECTION"))
    (t/is (str/includes? body "build a pricing page"))))

(t/deftest inner-knowledge-carries-the-scope-doctrine
  (t/testing "phase 02 of the OSS-prompt-learnings plan: minimal change,
              hands off other people's work, foundations kept in sync"
    (t/is (str/includes? ask/inner-knowledge "smallest change that satisfies the ask"))
    (t/is (str/includes? ask/inner-knowledge "someone's work in progress"))
    (t/is (str/includes? ask/inner-knowledge "update that foundation"))))

(t/deftest compact-system-keeps-lessons-and-anchors
  (t/testing "the summarizer prompt carries the Learned section and the
              update-the-prior-anchor instruction"
    (t/is (str/includes? agent/compact-system "## Learned"))
    (t/is (str/includes? agent/compact-system "previous compaction"))))

;; ---------------------------------------------------------------------------
;; Fresh-chat handoff (the pure halves).
;;
;; Past `handoff-notice-chars` the panel suggests summarizing into a NEW chat
;; (the old one stays in the saved list — chat management is per-file now).
;; Unlike auto-compaction, the summary here covers the WHOLE conversation —
;; nothing of the old history rides along, so the last turn must be in the
;; summarizer's transcript, not carried verbatim.
;; ---------------------------------------------------------------------------

(t/deftest handoff-seed-is-one-flagged-user-message
  (let [seed (agent/handoff-seed "the summary")]
    (t/is (= 1 (count seed)))
    (t/is (= :user (:role (first seed))))
    (t/is (true? (:compacted? (first seed))))
    (t/is (str/includes? (:text (first seed)) "the summary"))
    (t/is (str/includes? (str/lower (:text (first seed))) "earlier conversation")
          "framed as carried-over context, not as the user's words")))

(t/deftest conversation-transcript-includes-the-last-turn
  (t/testing "the whole conversation is summarized, the newest work included"
    (let [history [{:role :user :text "first ask"}
                   {:role :assistant :text "did it" :tool-calls []}
                   {:role :user :text "newest ask"}
                   {:role :assistant :text "newest reply" :tool-calls []}]
          all     (agent/conversation-transcript history)]
      (t/is (str/includes? all "newest ask"))
      (t/is (str/includes? all "newest reply")))))

(t/deftest history-chars-counts-text-and-results
  (let [history (vec (turn-with-result 0 (apply str (repeat 500 "x"))))]
    (t/is (> (agent/history-chars history) 500))
    (t/is (zero? (agent/history-chars [])))))

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

;; --- Read-only (#148, #154): someone who may only view the file

(t/deftest a-viewers-prompt-says-the-agent-cannot-change-the-file
  (let [viewer (agent/build-system-prompt {:permissions {:can-edit false :can-read true}})
        editor (agent/build-system-prompt {:permissions {:can-edit true}})]
    (t/is (str/includes? viewer "## Read-only"))
    (t/is (str/includes? viewer "cannot apply the fixes"))
    (t/is (str/includes? viewer "leave_comment"))
    (t/is (not (str/includes? editor "## Read-only")))))

(t/deftest a-viewers-change-request-is-redirected-not-refused
  ;; #155: one line saying why, the nearest things it can do, no tool names
  (let [viewer (agent/build-system-prompt {:permissions {:can-edit false :can-read true}})]
    (t/is (str/includes? viewer "Do not refuse and stop"))
    ;; a live Haiku turn opened with "I'll help you make the button blue"
    ;; and looked things up before saying it could not
    (t/is (str/includes? viewer "your first sentence says you cannot make it"))
    (t/is (str/includes? viewer "before you look anything up"))
    (t/is (str/includes? viewer "never open with an offer"))
    (t/is (str/includes? viewer "because they have view access"))
    (t/is (str/includes? viewer "describe the change precisely so an editor can make it"))
    (t/is (str/includes? viewer "leave it on the file as a comment"))
    (t/is (str/includes? viewer "show the current spec"))
    (t/is (str/includes? viewer "Never name a tool"))
    (t/is (str/includes? viewer "the conversation goes on"))))
