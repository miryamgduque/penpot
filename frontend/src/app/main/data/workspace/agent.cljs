;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent
  "The Agents chat model, routed through the Penpot backend proxy
  (`:ai-agent-round`) — provider keys live with the profile on the server and
  never reach the browser. This is the native CLJS port of
  `ai-skills/src/ui/agent.ts`.

  The conversation history is kept in ONE canonical form and re-encoded into
  the wire form of whichever provider is currently selected (Anthropic
  Messages for Claude models; OpenAI chat.completions for OpenAI-compatible
  providers). That is what lets a user switch models — even across providers —
  mid-conversation and carry the whole history.

  Phase 01 is text-only: no tools, a single round per turn. The tool
  declarations and the multi-round tool loop arrive in later phases (there are
  clearly-marked seams below)."
  (:require
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

;; --- Canonical conversation model
;;
;; A message is one of:
;;   {:role :user       :text "…"}
;;   {:role :assistant  :text "…" :tool-calls [{:id :name :input}]}
;;   {:role :tool-results :results [{:id :content :error?}]}   ; later phases

(defn- anthropic?
  "Providers that speak the Anthropic Messages API; the rest are
  OpenAI-compatible (chat.completions)."
  [provider]
  (= provider "anthropic"))

;; --- Tools (empty until Phase 02; the loop and encoders already thread them)

(def ^:private tools [])

;; --- Wire encoding

(defn- encode-anthropic
  [messages]
  (->> messages
       (mapv (fn [{:keys [role text tool-calls results]}]
               (case role
                 :user
                 {:role "user" :content text}

                 :assistant
                 (let [blocks (cond-> []
                                (seq text)
                                (conj {:type "text" :text text})
                                :always
                                (into (map (fn [c]
                                             {:type "tool_use"
                                              :id (:id c)
                                              :name (:name c)
                                              :input (:input c)}))
                                      tool-calls))]
                   ;; an assistant message must carry at least one block
                   {:role "assistant"
                    :content (if (seq blocks) blocks [{:type "text" :text "…"}])})

                 :tool-results
                 {:role "user"
                  :content (mapv (fn [r]
                                   (cond-> {:type "tool_result"
                                            :tool_use_id (:id r)
                                            :content (:content r)}
                                     (:error? r) (assoc :is_error true)))
                                 results)})))))

(defn- encode-openai
  [system messages]
  (into [{:role "system" :content system}]
        (mapcat (fn [{:keys [role text tool-calls results]}]
                  (case role
                    :user
                    [{:role "user" :content text}]

                    :assistant
                    [(cond-> {:role "assistant" :content (when (seq text) text)}
                       (seq tool-calls)
                       (assoc :tool_calls
                              (mapv (fn [c]
                                      {:id (:id c)
                                       :type "function"
                                       :function {:name (:name c)
                                                  :arguments (js/JSON.stringify
                                                              (clj->js (or (:input c) {})))}})
                                    tool-calls)))]

                    :tool-results
                    (mapv (fn [r]
                            {:role "tool" :tool_call_id (:id r) :content (:content r)})
                          results))))
        messages))

(defn- build-round-body
  [{:keys [provider model]} messages system]
  (js/JSON.stringify
   (clj->js
    (if (anthropic? provider)
      (cond-> {:model model
               ;; generous budget: adaptive-thinking models can spend a chunk
               ;; reasoning before any visible output; a small budget yields a
               ;; silent empty reply
               :max_tokens 32000
               ;; the ephemeral marker caches tools+system across rounds
               :system [{:type "text" :text system
                         :cache_control {:type "ephemeral"}}]
               :messages (encode-anthropic messages)}
        (seq tools) (assoc :tools tools))
      (cond-> {:model model
               :messages (encode-openai system messages)}
        (seq tools)         (assoc :tools tools)
        (= provider "openai") (assoc :max_completion_tokens 16000)
        (not= provider "openai") (assoc :max_tokens 16000))))))

;; --- Response parsing / decoding

(defn- parse-round
  "Parses the provider response body, throwing a readable error on failure."
  [{:keys [status body]}]
  (let [parsed (try
                 (js->clj (js/JSON.parse body) :keywordize-keys true)
                 (catch :default _
                   (throw (ex-info (str "The provider returned a non-JSON response (status " status ").")
                                   {:status status}))))]
    (when (or (< status 200) (>= status 300))
      (let [message (or (get-in parsed [:error :message])
                        (:message parsed)
                        (str "provider error (status " status ")"))]
        (throw (ex-info (str message) {:status status}))))
    parsed))

(defn- decode-anthropic
  [parsed]
  (let [content (:content parsed)]
    {:text (->> content
                (filter #(= "text" (:type %)))
                (map #(or (:text %) ""))
                (str/join ""))
     :tool-calls (->> content
                      (filter #(= "tool_use" (:type %)))
                      (mapv (fn [b] {:id (:id b) :name (:name b) :input (or (:input b) {})})))
     :stopped-for-length? (= "max_tokens" (:stop_reason parsed))}))

(defn- decode-openai
  [parsed]
  (let [message (get-in parsed [:choices 0 :message])
        finish  (get-in parsed [:choices 0 :finish_reason])]
    {:text (let [c (:content message)] (if (string? c) c ""))
     :tool-calls (->> (:tool_calls message)
                      (mapv (fn [c]
                              {:id (:id c)
                               :name (get-in c [:function :name])
                               :input (try
                                        (js->clj (js/JSON.parse (or (get-in c [:function :arguments]) "{}"))
                                                 :keywordize-keys true)
                                        (catch :default _ {}))})))
     :stopped-for-length? (= "length" finish)}))

;; --- System prompt (scaffold; skills/rules sections arrive in Phase 07)

(defn build-system-prompt
  [context]
  (str/join "\n"
            ["You are the design agent embedded in Penpot (the open-source design tool), working on the user's current file."
             ""
             "## Operating modes (governance)"
             "- Suggest: audits/reviews propose changes as a report; touch nothing."
             "- Apply-with-review (default for generative work): make the change, then summarize what changed and pause for direction on large next steps."
             "- Auto-fix without asking ONLY for the safe set: renaming auto-named layers, loss-less raw-value→token swaps, adding documentation/metadata."
             "- Never without explicit approval: deleting/restructuring components or shared assets, large destructive geometry changes."
             ""
             ;; SEAM: Phase 07 injects the effective skills routing index, the
             ;; rules manifest, and local skill bodies here.
             "## Current design context"
             "```json"
             (js/JSON.stringify (clj->js context))
             "```"
             ""
             "Keep replies short — you live in a narrow side panel. Work in small steps and reference shapes by name."]))

;; --- Turn runner
;;
;; Phase 01: one round, text only. `run-round` returns an rx observable of the
;; assistant text (or errors, surfaced to the caller). The multi-round tool
;; loop (execute tools → feed results back → repeat) is added in Phase 02.

(defn run-round
  [settings messages system]
  (->> (rp/cmd! :ai-agent-round
                {:provider (:provider settings)
                 :payload (build-round-body settings messages system)})
       (rx/map (fn [round]
                 (let [parsed  (parse-round round)
                       outcome (if (anthropic? (:provider settings))
                                 (decode-anthropic parsed)
                                 (decode-openai parsed))
                       text    (:text outcome)]
                   (cond
                     (seq text) text
                     (:stopped-for-length? outcome)
                     "⚠️ I ran out of output budget before finishing — please send the request again."
                     :else
                     "⚠️ The model returned no visible output — try rephrasing the request."))))))
