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
   [app.main.data.agent-access :as agent-access]
   [app.main.data.helpers :as dsh]
   [app.main.data.persistence :as dps]
   [app.main.data.session-actor :as sa]
   [app.main.data.workspace.agent :as agent]
   [app.main.data.workspace.agent-tools :as at]
   [app.main.data.workspace.agent-tools.read :as atr]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.sse :as sse]
   [beicon.v2.core :as rx]))

(defn- parse-json
  [s]
  (try
    (js->clj (js/JSON.parse s) :keywordize-keys true)
    (catch :default _ nil)))

;; The harness serves the skills itself, through its own tool.
(def ^:private harness-owned-tools #{"get_design_skills"})

(defn turn-payload
  "The JSON body of a turn: the conversation the service should continue, the
  model, the system prompt, the user's message, the tool declarations and the
  skills enabled on the file."
  [settings user-msg system]
  (js/JSON.stringify
   (clj->js {:sessionId (str (:session-id (:service settings)))
             :model (:model settings)
             :system system
             :text (agent/user-text user-msg)
             :images (mapv (fn [{:keys [mtype data]}] {:mediaType mtype :data data})
                           (:images user-msg))
             :skills (vec (:skills (:service settings)))
             :tools (into []
                          (comp (remove #(contains? harness-owned-tools (:name %)))
                                (map (fn [t] {:name (:name t)
                                              :description (:description t)
                                              :inputSchema (:input-schema t)})))
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

;; --- Server-side tools
;;
;; A tool the backend runs reads the file from storage, not from this tab. So
;; before each one the service asks the workspace two things: to save what it
;; has not saved yet — otherwise the tool would not see the agent's own last
;; change — and to say what the user is looking at.

(def ^:private sync-call "__sync")

;; A change committed by the previous tool reaches the save queue a moment
;; later; asking "is everything saved?" before that would answer yes too early.
(def ^:private settle-ms 80)

(def ^:private save-timeout-ms 15000)

(defn workspace-context
  "What a server-side tool cannot know without a workspace: the page in view,
  the selection, and the facts `read_design` reports from workspace state."
  [state]
  {:pageId (str (:current-page-id state))
   :selectedIds (mapv str (dsh/get-selected-ids state))
   ;; which rules the file enforces is workspace state for now
   :enforcedRules (vec (get-in state [:ai-panel (:current-file-id state) :enforced-rules]))
   :extras (select-keys (atr/workspace-extras state) [:skills :openViolations])})

(def ^:private catch-up-timeout-ms 8000)

(defn- caught-up
  "→ observable that emits once this workspace has the file at `revn` or
  later: a change the server committed reaches it a moment after the commit.
  Gives up quietly after a while — the tool that follows then says what it
  could not find, which the agent can act on."
  [revn]
  (let [at-revn? (fn [] (let [state @st/state]
                          (>= (or (get-in state [:files (:current-file-id state) :revn]) 0) revn)))]
    (if (or (nil? revn) (at-revn?))
      (rx/of true)
      (->> (rx/interval 40)
           (rx/filter at-revn?)
           (rx/take 1)
           (rx/timeout catch-up-timeout-ms (rx/of false))))))

(defn save-pending
  "→ observable that emits once what this workspace has not saved yet is
  saved. Whatever is about to look at the stored file, or change it, has to
  see the user's last edits first."
  []
  (st/emit! ::dps/force-persist)
  (->> (rx/timer settle-ms)
       (rx/mapcat (fn [_] (dps/wait-persisted-or-error save-timeout-ms)))))

(defn- sync-workspace
  "Saves pending changes, waits for the server's own last change when asked,
  then → observable of one outcome carrying the workspace context as its
  content."
  [call]
  (->> (save-pending)
       (rx/mapcat (fn [_] (caught-up (:minRevn (:input call)))))
       (rx/map (fn [_]
                 {:call call
                  :status :ok
                  :content (js/JSON.stringify (clj->js (workspace-context @st/state)))}))
       (rx/catch (fn [cause]
                   (rx/of {:call call
                           :status :error
                           :error? true
                           :content (str "The workspace could not save its changes: "
                                         (or (ex-message cause) "unknown error"))})))))

(defn tool-display-name
  "A tool's name as the transcript shows it. A tool of a service the user
  connected is `mcp__<service>__<tool>` on the wire; it reads better, and
  says whose it is, as `<service>: <tool>`."
  [name]
  (if-let [[_ service tool] (re-matches #"^mcp__(.+?)__(.+)$" (str name))]
    (str service ": " tool)
    name))

(defn- server-tool-event
  "A tool the server ran, as the transcript shows any other tool: refused by
  a design rule, failed, or done."
  [{:keys [name input content isError rule]}]
  (agent/tool-outcome->event
   {:call {:name (tool-display-name name) :input (or input {})}
    :status (cond (some? rule) :rejected isError :error :else :ok)
    :rule rule
    :detail (when isError content)
    :content content
    :error? (boolean isError)}))

(def ^:private reported-refusals*
  "The connected services already said to be left out, so each is said once
  and not on every turn."
  (atom #{}))

(defn refused-service-events
  "One transcript row per connected service the agent service left out of the
  turn (its address stopped being reachable, its name is not usable), the
  first time it happens."
  [{:keys [refused]}]
  (into []
        (comp (map (fn [{:keys [name reason]}] [(str name) (str reason)]))
              (remove @reported-refusals*)
              (map (fn [[name reason :as refusal]]
                     (swap! reported-refusals* conj refusal)
                     (agent/tool-outcome->event
                      {:call {:name name :input {}}
                       :status :error
                       :detail (str "This connected service was left out: " reason)
                       :content reason
                       :error? true}))))
        refused))

(defn- usage
  [{:keys [input output cacheRead cacheWrite]}]
  {:input-tokens (or input 0)
   :output-tokens (or output 0)
   :cache-read-tokens (or cacheRead 0)
   :cache-write-tokens (or cacheWrite 0)
   :requests 1})

(def last-turn-ref*
  "The backend's id for the last turn: `get-agent-changes` lists what the
  turn changed by it."
  (atom nil))

(def last-tool-timings*
  "Per-tool round-trip times of the last finished turn, as the service
  measured them. A measurement aid for comparing the bridge with the
  in-browser loop; nothing reads it at runtime."
  (atom nil))

(def last-server-tool*
  "The last tool the server ran, with its whole result. A verification aid
  for comparing a server-side tool with its workspace twin; nothing reads it
  at runtime."
  (atom nil))

(defn run-turn
  "→ observable of turn events. `history` ends in the user message to send;
  what came before it is already in the service's session."
  [settings history system]
  (let [user-msg (peek (vec history))
        turn-id* (atom nil)
        text*    (atom "")]
    (->> (agent-access/guard-model-call
          #(rp/cmd! ::sse/agent-service-turn
                    {:provider (:provider settings)
                     :file-id (:file-id (:service settings))
                     :payload (turn-payload settings user-msg system)}))
         (rx/mapcat
          (fn [event]
            (if (not= "event" (sse/get-type event))
              (rx/empty)
              (let [ev (parse-json (sse/get-payload event))]
                (case (:type ev)
                  "turn"
                  (do (reset! turn-id* (:turnId ev)) (rx/empty))

                  ;; the id the backend records this turn's changes under
                  "turn-ref"
                  (do (reset! last-turn-ref* (:turnRef ev)) (rx/empty))

                  "text-delta"
                  (do (swap! text* str (:text ev))
                      (rx/of {:kind :assistant-delta :text (:text ev)}))

                  "thinking-delta"
                  (rx/of {:kind :thinking-delta :text (:text ev)})

                  "tool-call"
                  (if (= sync-call (:name ev))
                    ;; plumbing, not a tool: nothing is shown in the transcript
                    (->> (sync-workspace {:id (:callId ev) :input (:input ev)})
                         (rx/mapcat #(post-result @turn-id* %))
                         (rx/ignore))
                    (->> (run-tool settings {:id (:callId ev)
                                             :name (:name ev)
                                             :input (or (:input ev) {})})
                         (rx/mapcat
                          (fn [outcome]
                            (rx/concat
                             (rx/of (agent/tool-outcome->event outcome))
                             (->> (post-result @turn-id* outcome)
                                  (rx/ignore)))))))

                  "server-tool"
                  (do
                    (reset! last-server-tool* ev)
                    (rx/of (server-tool-event ev)))

                  ;; which connected services are in play; one that was
                  ;; left out is worth a row
                  "mcp"
                  (rx/from (refused-service-events ev))

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
