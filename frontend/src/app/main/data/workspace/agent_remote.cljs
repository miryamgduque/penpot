;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-remote
  "The agent turn, run by the agent service instead of in the browser.

  The loop, the model calls and the conversation history live in the service
  (built on DeepSeek Harness); the panel sends one user message and consumes
  the service's event stream through the backend, which is where the provider
  key is attached. Tools still execute here: the service emits a `tool-call`,
  the panel runs it through the same registry and enforcement as the browser
  loop, and posts the result back.

  `run-turn` emits the same turn events as `agent/run-turn`, so the panel
  cannot tell the two apart."
  (:require
   [app.main.data.session-actor :as sa]
   [app.main.data.workspace.agent :as agent]
   [app.main.data.workspace.agent-tools :as at]
   [app.main.repo :as rp]
   [app.util.sse :as sse]
   [beicon.v2.core :as rx]))

(defn- parse-json
  [s]
  (try
    (js->clj (js/JSON.parse s) :keywordize-keys true)
    (catch :default _ nil)))

(defn turn-payload
  "The JSON body of a turn: the conversation the service should continue, the
  model, the system prompt, the user's message and the tool declarations."
  [settings user-msg system]
  (js/JSON.stringify
   (clj->js {:sessionId (str (:session-id (:service settings)))
             :model (:model settings)
             :system system
             :text (agent/user-text user-msg)
             :images (mapv (fn [{:keys [mtype data]}] {:mediaType mtype :data data})
                           (:images user-msg))
             :tools (mapv (fn [t] {:name (:name t)
                                   :description (:description t)
                                   :inputSchema (:input-schema t)})
                          at/tool-specs)})))

(defn- run-tool
  "Runs one tool call exactly as the browser loop does → observable of one
  outcome map."
  [settings call]
  (let [action (sa/begin-agent-action! settings)]
    (->> (at/execute-tool (:name call) (:input call))
         (rx/map (fn [result]
                   (sa/end-agent-action! action)
                   {:call call
                    :status :ok
                    :images (:images result)
                    :content (agent/result->content (dissoc result :images))}))
         (rx/catch (fn [cause]
                     (sa/end-agent-action! action)
                     (let [rule (:rule (ex-data cause))]
                       (rx/of {:call call
                               :status (if rule :rejected :error)
                               :rule rule
                               :detail (ex-message cause)
                               :content (or (ex-message cause) "tool error")
                               :error? true})))))))

(defn- post-result
  [turn-id outcome]
  (rp/cmd! :agent-service-tool-result
           {:turn-id turn-id
            :payload (js/JSON.stringify
                      (clj->js {:callId (:id (:call outcome))
                                :content (:content outcome)
                                :isError (boolean (:error? outcome))
                                :images (mapv (fn [{:keys [mtype data]}]
                                                {:mediaType mtype :data data})
                                              (:images outcome))}))}))

(defn- usage
  [{:keys [input output cacheRead cacheWrite]}]
  {:input-tokens (or input 0)
   :output-tokens (or output 0)
   :cache-read-tokens (or cacheRead 0)
   :cache-write-tokens (or cacheWrite 0)
   :requests 1})

(def last-tool-timings*
  "Per-tool round-trip times of the last finished turn, as the service
  measured them. A measurement aid for comparing the bridge with the
  in-browser loop; nothing reads it at runtime."
  (atom nil))

(defn run-turn
  "→ observable of turn events. `history` ends in the user message to send;
  what came before it is already in the service's session."
  [settings history system]
  (let [user-msg (peek (vec history))
        turn-id* (atom nil)
        text*    (atom "")]
    (->> (rx/mapcat (fn [_]
                      (rp/cmd! ::sse/agent-service-turn
                               {:provider (:provider settings)
                                :file-id (:file-id (:service settings))
                                :payload (turn-payload settings user-msg system)}))
                    (rx/of nil))
         (rx/mapcat
          (fn [event]
            (if (not= "event" (sse/get-type event))
              (rx/empty)
              (let [ev (parse-json (sse/get-payload event))]
                (case (:type ev)
                  "turn"
                  (do (reset! turn-id* (:turnId ev)) (rx/empty))

                  "text-delta"
                  (do (swap! text* str (:text ev))
                      (rx/of {:kind :assistant-delta :text (:text ev)}))

                  "thinking-delta"
                  (rx/of {:kind :thinking-delta :text (:text ev)})

                  "tool-call"
                  (->> (run-tool settings {:id (:callId ev)
                                           :name (:name ev)
                                           :input (or (:input ev) {})})
                       (rx/mapcat
                        (fn [outcome]
                          (rx/concat
                           (rx/of (agent/tool-outcome->event outcome))
                           (->> (post-result @turn-id* outcome)
                                (rx/ignore))))))

                  "usage"
                  (rx/of {:kind :usage :usage (usage (:usage ev))})

                  "done"
                  ;; the service owns the real history; what is kept here is
                  ;; the readable shadow the panel persists and restores
                  (do
                    (reset! last-tool-timings* (:toolTimings ev))
                    (rx/of {:kind :done
                            :history (conj (vec history)
                                           {:role :assistant :text @text* :tool-calls []})}))

                  "error"
                  (rx/throw (ex-info (or (:message ev) "agent service error")
                                     {:hint (:message ev)}))

                  (rx/empty)))))))))
