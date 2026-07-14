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
   [cljs.test :as t :include-macros true]))

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
