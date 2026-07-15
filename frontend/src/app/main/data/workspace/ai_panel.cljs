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
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent :as agent]
   [app.main.data.workspace.agent-tools :as at]
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

(declare start-watcher)
(declare stop-watcher)

(defn close-panel
  []
  (ptk/reify ::close-panel
    ptk/UpdateEvent
    (update [_ state]
      (set-open state false))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (stop-watcher)))))

(defn toggle-panel
  []
  (ptk/reify ::toggle-panel
    ptk/UpdateEvent
    (update [_ state]
      (set-open state (not (open? state))))

    ;; potok runs `update` before `watch` on the same event, so `state` here
    ;; already reflects the toggle: open → start the violations watcher,
    ;; closed → stop it. Toggle semantics guarantee we never start twice.
    ptk/WatchEvent
    (watch [_ state _]
      (if (open? state)
        (rx/of (start-watcher))
        (rx/of (stop-watcher))))))

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
  "`images` (a vector of `{:mtype :data}`) is rendered in the user's own bubble.
  The transcript must not lie about what was sent: if the image left the
  browser, the user needs to see that it did."
  ([role content] (append-message role content nil))
  ([role content images]
   (ptk/reify ::append-message
     ptk/UpdateEvent
     (update [_ state]
       (if-let [file-id (:current-file-id state)]
         (update-in state [:ai-panel file-id :messages]
                    (fnil conj []) (cond-> {:role role :content content}
                                     (seq images) (assoc :images images)))
         state)))))

(defn append-delta
  "Appends streamed text to the open assistant bubble, opening one first if the
  round hasn't produced text yet.

  That fallback is the bubble's start signal — after a user message or a run of
  tool chips the last message isn't an assistant one, so the round's first
  delta opens a fresh bubble and the rest extend it. Updating the last message
  in place is O(1) on a vector, so text can arrive token by token without
  rebuilding the transcript."
  [text]
  (ptk/reify ::append-delta
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (update-in state [:ai-panel file-id :messages]
                   (fn [messages]
                     (let [messages (vec messages)
                           idx      (dec (count messages))]
                       (if (and (>= idx 0) (= "assistant" (:role (nth messages idx))))
                         (update messages idx update :content str text)
                         (conj messages {:role "assistant" :content text})))))
        state))))

(defn append-tool
  "Appends a tool-call marker to the rendered transcript (a chip).

  Takes the turn event itself (minus `:kind`) rather than positional args —
  it already carries everything the chip shows, and new fields come for free."
  [{:keys [status rule detail input result] tool-name :name}]
  (ptk/reify ::append-tool
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (update-in state [:ai-panel file-id :messages]
                   (fnil conj []) {:role "tool"
                                   :name tool-name
                                   :status (some-> status name)
                                   :rule rule
                                   :detail detail
                                   :input input
                                   :result result})
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

;; --- Live violations watcher (auto-fix)
;;
;; Keeps `[:ai-panel <file-id> :violations]` current while the panel is open:
;; every mutation flows through `dch/commit` (an IDeref event carrying
;; `:redo-changes`), so the watcher filters those off the global stream,
;; debounces, and re-runs the deterministic scan (`at/audit-violations`).
;; The scan is over CURRENT state, so deletions and user corrections prune
;; themselves — there is no ledger to invalidate. Deliberately re-scans the
;; whole page (≤1000 shapes, pure in-memory) rather than scoping to touched
;; ids; the `:dirty-ids` set exists only to keep the semantic tick's LLM
;; payload small (phase 05), never for correctness.
;;
;; Consent = panel open: `toggle-panel`/`close-panel` start and stop the
;; subscription, and the update events additionally no-op while the panel is
;; closed so a stray subscription can never churn state for a file whose
;; panel the user is not looking at.

(def ^:private watcher-debounce-ms 500)

(defn- touched-shape-ids
  "Shape ids named by a commit's redo-changes (`:id` on add/mod/del forms,
  `:shapes` on mov/reg forms)."
  [redo-changes]
  (into #{}
        (mapcat (fn [{:keys [id shapes]}]
                  (cond-> []
                    (some? id)   (conj id)
                    (seq shapes) (into shapes))))
        redo-changes))

(defn refresh-violations
  "Recompute the deterministic violations for the current file. Prunes ids
  from `:dirty-ids` that no longer exist (deleted shapes must not ride into
  a semantic tick)."
  []
  (ptk/reify ::refresh-violations
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)]
        (if (and file-id (open? state))
          (update-in state [:ai-panel file-id]
                     (fn [panel]
                       (let [objects (dsh/lookup-page-objects state)]
                         (-> panel
                             (assoc :violations (at/audit-violations state))
                             (update :dirty-ids
                                     (fn [ids]
                                       (into #{} (filter #(contains? objects %)) ids)))))))
          state)))))

(defn- track-dirty
  "Accumulate touched shape ids for the semantic tick (phase 05)."
  [ids]
  (ptk/reify ::track-dirty
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)]
        (if (and file-id (open? state) (seq ids))
          (update-in state [:ai-panel file-id :dirty-ids] (fnil into #{}) ids)
          state)))))

(defn- stop-watcher
  []
  (ptk/reify ::stop-watcher))

(defn- start-watcher
  "Subscribe to commits until `::stop-watcher`. Emits an immediate initial
  scan (a file can already be messy when the panel opens), tracks dirty ids
  per commit, and re-scans debounced. Also re-scans when the enforced-rules
  set changes, so toggling a rule updates the live set without an edit."
  []
  (ptk/reify ::start-watcher
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/filter (ptk/type? ::stop-watcher) stream)
            commits (->> stream
                         (rx/filter (ptk/type? ::dch/commit))
                         (rx/map deref))]
        (->> (rx/merge
              (rx/of (refresh-violations))
              (->> commits
                   (rx/map (fn [{:keys [redo-changes]}]
                             (track-dirty (touched-shape-ids redo-changes)))))
              (->> (rx/merge commits
                             (rx/filter (ptk/type? ::set-enforced-rules) stream))
                   (rx/debounce watcher-debounce-ms)
                   (rx/map (fn [_] (refresh-violations)))))
             (rx/take-until stopper))))))

(defn cancel-turn
  "Stops the running turn. `send-message` watches the event stream for this."
  []
  (ptk/reify ::cancel-turn))

(defn send-message
  "Runs one user turn: appends the user message, runs the agent turn through
  the backend proxy (executing native tools between rounds), and streams the
  assistant reply + tool chips into the transcript. `context` is the current
  page + selection; it rides on the user message (the volatile slot) rather than
  the system prompt, which is the cached prefix — see `agent/user-content`. The
  full canonical history (with tool_use/tool_result blocks) is threaded across
  turns via `:history`.

  Cancellable via `cancel-turn`. A cancelled turn is still committed to the
  history — closed off by `agent/cancel-history` — because dropping it would
  leave the agent with no memory of an exchange the transcript still shows.

  `images` are base64 in memory and are never uploaded anywhere: they ride the
  turn and live only in this file's in-memory history, so a reload drops them."
  ([settings text context] (send-message settings text context nil))
  ([settings text context images]
   (ptk/reify ::send-message
     ptk/WatchEvent
     (watch [_ state stream]
       (let [file-id (:current-file-id state)
             prior   (dm/get-in state [:ai-panel file-id :history])
             ;; context rides on the user message (the volatile slot), while the
             ;; system prompt stays a stable, cacheable prefix built from `state`
             history (conj (vec prior) (cond-> {:role :user :text text :context context}
                                         (seq images) (assoc :images images)))
             system  (agent/build-system-prompt state)
             stopper (rx/filter (ptk/type? ::cancel-turn) stream)

             ;; the turn's history as it grows, so a cancel can close it off;
             ;; seeded with the user message so it survives an early stop
             latest* (atom history)
             ;; tells "ran to completion / errored" apart from "cancelled" —
             ;; `take-until` completes the stream either way
             ended?* (atom false)]
         (rx/concat
          (rx/of (append-message "user" text images)
                 (set-busy true))

          ;; `take-until` wraps the pipeline, errors included; `set-busy false`
          ;; must stay outside it or a cancel leaves the panel stuck busy
          (->> (agent/run-turn settings history system)
               (rx/mapcat (fn [ev]
                            (case (:kind ev)
                              :assistant       (rx/of (append-message "assistant" (:text ev)))
                              :assistant-delta (rx/of (append-delta (:text ev)))
                              :tool         (rx/of (append-tool (dissoc ev :kind)))
                              :usage        (rx/of (accumulate-usage (:usage ev)))
                              :turn-history (do (reset! latest* (:history ev))
                                                (rx/empty))
                              :done         (do (reset! ended?* true)
                                                (rx/of (store-history (:history ev))))
                              (rx/empty))))
               (rx/catch (fn [cause]
                           (reset! ended?* true)
                           (let [data (ex-data cause)
                                 msg  (or (:hint data)
                                          (some-> (:code data) name)
                                          (ex-message cause)
                                          "request failed")]
                             (rx/of (append-message "assistant" (dm/str "⚠️ " msg))))))
               (rx/take-until stopper))

          ;; deferred: `concat` subscribes here only once the turn is over, so
          ;; the atoms have settled by the time this decides what happened
          (->> (rx/of ::end)
               (rx/mapcat (fn [_]
                            (if @ended?*
                              (rx/of (set-busy false))
                              (rx/of (append-message "assistant" "⏹ Stopped.")
                                     (store-history (agent/cancel-history @latest*))
                                     (set-busy false))))))))))))
