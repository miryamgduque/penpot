;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.ai-panel
  "Open/closed state for the All-In Penpot (Agents) panel.

  The state is bound to the file and kept in root app state under
  `[:ai-panel <file-id> :open?]` — like `[:recent-colors <file-id>]`. It is
  in-memory only (never persisted): it survives in-app navigation between
  pages/boards and dashboard round-trips (`finalize-workspace` does not
  dissoc it, `initialize-workspace` does not reseed it), and a hard browser
  refresh resets it to closed. Chat content persistence is handled
  separately (phase 05; hard-refresh survival is story #5)."
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.agent :as agent]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- open?
  [state]
  (when-let [file-id (:current-file-id state)]
    (dm/get-in state [:ai-panel file-id :open?])))

(defn- set-open
  [state open?]
  (if-let [file-id (:current-file-id state)]
    (assoc-in state [:ai-panel file-id :open?] (boolean open?))
    state))

(defn close-panel
  []
  (ptk/reify ::close-panel
    ptk/UpdateEvent
    (update [_ state]
      (set-open state false))))

(defn toggle-panel
  []
  (ptk/reify ::toggle-panel
    ptk/UpdateEvent
    (update [_ state]
      (set-open state (not (open? state))))))

;; --- Chat transcript
;;
;; Per-file, in-memory chat messages (`[:ai-panel <file-id> :messages]`, a
;; vector of `{:role :content}`). Persists across navigation like the open
;; state; hard-refresh survival is out of scope here (story #5). The live
;; agent turn that produces assistant replies is the CLJS port of the
;; `ai-skills` agent — a separate plan; this only stores/renders messages.

(defn append-message
  [role content]
  (ptk/reify ::append-message
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (update-in state [:ai-panel file-id :messages]
                   (fnil conj []) {:role role :content content})
        state))))

(defn set-busy
  [busy?]
  (ptk/reify ::set-busy
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (assoc-in state [:ai-panel file-id :busy?] busy?)
        state))))

(defn- display->canonical
  "The rendered transcript stores `{:role \"user\"/\"assistant\" :content}`;
  the agent's canonical history uses `{:role :user/:assistant :text}`. For the
  text-only phase this mapping is 1:1 (tool_use/tool_result blocks arrive with
  a dedicated canonical history in a later phase)."
  [{:keys [role content]}]
  {:role (keyword role) :text content})

(defn send-message
  "Runs one user turn: appends the user message, runs the agent round through
  the backend proxy, and appends the assistant reply (or a readable error).
  `context` is the current page + selection surfaced to the system prompt."
  [settings text context]
  (ptk/reify ::send-message
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)
            history (-> (mapv display->canonical
                              (dm/get-in state [:ai-panel file-id :messages]))
                        (conj {:role :user :text text}))
            system  (agent/build-system-prompt context)]
        (rx/concat
         (rx/of (append-message "user" text)
                (set-busy true))
         (rx/concat
          (->> (agent/run-round settings history system)
               (rx/map (fn [reply] (append-message "assistant" reply)))
               (rx/catch (fn [cause]
                           (let [data (ex-data cause)
                                 msg  (or (:hint data)
                                          (some-> (:code data) name)
                                          (ex-message cause)
                                          "request failed")]
                             (rx/of (append-message "assistant" (dm/str "⚠️ " msg)))))))
          (rx/of (set-busy false))))))))
