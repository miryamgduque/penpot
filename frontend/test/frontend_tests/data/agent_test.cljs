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
