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

;; --- Skills-tab list filter (US #30)
;;
;; In-memory only, on purpose: it persists within the session (survives tab
;; switches + panel close/reopen) but resets to :enabled on reload — deliberately
;; unlike the localStorage-backed prefs. `refs/skills-filter` reads it, defaulting
;; to :enabled when unset.

(defn set-skills-filter
  "Set the Skills-tab list filter for this session (`:all` | `:enabled`)."
  [filter]
  (ptk/reify ::set-skills-filter
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :skills-filter filter))))

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

(defn append-tool
  "Appends a tool-call marker to the rendered transcript (a chip)."
  [tool-name status rule detail]
  (ptk/reify ::append-tool
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (update-in state [:ai-panel file-id :messages]
                   (fnil conj []) {:role "tool"
                                   :name tool-name
                                   :status (some-> status name)
                                   :rule rule
                                   :detail detail})
        state))))

(defn- store-history
  "Persists the canonical turn history (with tool_use/tool_result blocks) so
  the next turn carries full context — the rendered transcript can't
  reconstruct it."
  [history]
  (ptk/reify ::store-history
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (assoc-in state [:ai-panel file-id :history] history)
        state))))

(defn set-busy
  [busy?]
  (ptk/reify ::set-busy
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (assoc-in state [:ai-panel file-id :busy?] busy?)
        state))))

(defn- accumulate-usage
  "Adds one round's token usage into the file's running spend meter total."
  [usage]
  (ptk/reify ::accumulate-usage
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (update-in state [:ai-panel file-id :usage] agent/add-usage usage)
        state))))

(defn clear-chat
  "Starts a fresh session for the current file: drops the transcript, the
  canonical history and the spend meter. Guarded against running turns by the
  UI (the clear control is disabled while busy)."
  []
  (ptk/reify ::clear-chat
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (update-in state [:ai-panel file-id]
                   (fn [panel] (dissoc panel :messages :history :usage)))
        state))))

(defn set-enforced-rules
  "Records which rule names are enforced for the current file — the agent's
  color tools reject raw colors when `token-only-colors` is in this set. Wired
  to the backend skills resolution in a later phase."
  [rules]
  (ptk/reify ::set-enforced-rules
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (assoc-in state [:ai-panel file-id :enforced-rules] (set rules))
        state))))

(defn send-message
  "Runs one user turn: appends the user message, runs the agent turn through
  the backend proxy (executing native tools between rounds), and streams the
  assistant reply + tool chips into the transcript. `context` is the current
  page + selection surfaced to the system prompt. The full canonical history
  (with tool_use/tool_result blocks) is threaded across turns via `:history`."
  [settings text context]
  (ptk/reify ::send-message
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)
            prior   (dm/get-in state [:ai-panel file-id :history])
            history (conj (vec prior) {:role :user :text text})
            system  (agent/build-system-prompt state context)]
        (rx/concat
         (rx/of (append-message "user" text)
                (set-busy true))
         (rx/concat
          (->> (agent/run-turn settings history system)
               (rx/mapcat (fn [ev]
                            (case (:kind ev)
                              :assistant (rx/of (append-message "assistant" (:text ev)))
                              :tool      (rx/of (append-tool (:name ev) (:status ev)
                                                             (:rule ev) (:detail ev)))
                              :usage     (rx/of (accumulate-usage (:usage ev)))
                              :done      (rx/of (store-history (:history ev)))
                              (rx/empty))))
               (rx/catch (fn [cause]
                           (let [data (ex-data cause)
                                 msg  (or (:hint data)
                                          (some-> (:code data) name)
                                          (ex-message cause)
                                          "request failed")]
                             (rx/of (append-message "assistant" (dm/str "⚠️ " msg)))))))
          (rx/of (set-busy false))))))))
