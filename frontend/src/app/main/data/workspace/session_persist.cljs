;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.session-persist
  "Moves a live recording to the backend (see `app.rpc.commands.design-sessions`).

  Phase 07 of the design-session-recording plan. The recorder
  (`session-recorder`) owns the live session in app state; this namespace decides
  when to send it, what to send, and what to do when sending fails.

  ## Never lose events, and never quietly keep them

  A failed flush retries. If it keeps failing, the session is marked
  `:local-only?` and says so, and recording continues. Both halves matter: losing
  the rest of the recording would be worse than not persisting it, and a
  recording that silently stopped persisting halfway is a *false record* — the
  phase-09 critique would reason from it, which is precisely the failure this
  feature exists to catch in humans.

  ## Whole-timeline replace, not append

  The RPC is one idempotent upsert (phase 06): the client re-sends its bounded
  timeline and the row is replaced. Three consequences, all of them
  simplifications:

  - a retried or replayed flush cannot duplicate anything;
  - **a flush landing mid-gesture self-heals.** Phases 01 and 04 flagged that
    `absorb` folds into the *last* event, so a flush could split one drag into two
    events. With replace, the next flush re-sends the merged tail and the split
    disappears. No provisional-tail bookkeeping is needed;
  - re-sending an unchanged timeline is pure waste, hence `:dirty?`.

  ## Reload

  A browser refresh is not a stop. The active session id is kept in
  `storage/user`, so a reload can `resume-recording`: fetch the row, seed the
  recorder from it, and carry on appending. The alternative — leaving a row with
  `stopped_at` NULL forever — would produce sessions that never end and never get
  reviewed.

  Raw ops are deliberately NOT resumed. They are ephemeral by design and the
  browser that held them is gone; the timeline is what survives.

  NOTE: nothing calls into this namespace yet — the recorder is not in the
  `:main` build until phase 08 wires the record control (see phase 04's notes)."
  (:require
   [app.main.data.changes :as dch]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.session-recorder :as sr]
   [app.main.repo :as rp]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; `resume-recording` re-emits it, and it is defined further down
(declare start-persisting)

(def max-flush-failures
  "How many consecutive failed flushes before the recording stops trying and
  declares itself local-only. Low on purpose: the point is to tell the user
  quickly, not to keep hammering a backend that is not answering."
  5)

(def flush-debounce-ms
  "Quiet period before a flush. Generous because the whole timeline rides every
  request, so flushing per commit would re-send everything dozens of times during
  a single drag."
  3000)

;; --- pure decisions

(defn should-flush?
  [s]
  (boolean (and s (:dirty? s) (not (:local-only? s)))))

(defn mark-flushed
  "The server accepted this timeline: nothing pending, and any failure streak is
  over."
  [s]
  (assoc s :dirty? false :flush-failures 0))

(defn mark-flush-failed
  "Keep the events and stay dirty so the next attempt retries. At the ceiling,
  give up on persisting and disclose it — recording itself continues."
  [s]
  (let [failures (inc (:flush-failures s 0))]
    (assoc s
           :dirty? true
           :flush-failures failures
           :local-only? (>= failures max-flush-failures))))

(defn flush-payload
  "The upsert params for a session. `stop-reason` is a string because the column
  is text and the reason is data — it travels to a bot, not to code."
  [s]
  {:id (:id s)
   :file-id (:file-id s)
   :session-id (:session-id s)
   :events (:events s [])
   :raw-dropped (:raw-dropped s 0)
   :stop-reason (some-> (:stop-reason s) name)})

;; --- the active session id, so a reload can find it again

(defn- remember-active!
  [file-id session-row-id]
  (swap! storage/user assoc ::active {:file-id file-id :id session-row-id}))

(defn- forget-active!
  []
  (swap! storage/user dissoc ::active))

(defn active-session-id
  "The row id of a recording that was in progress when the page went away, if it
  belongs to `file-id`."
  [file-id]
  (let [{:keys [id] :as active} (::active storage/user)]
    (when (= file-id (:file-id active))
      id)))

;; --- state events

(defn- flushed
  [file-id]
  (ptk/reify ::flushed
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:session-recorder file-id] #(some-> % mark-flushed)))))

(defn- flush-failed
  [file-id]
  (ptk/reify ::flush-failed
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:session-recorder file-id] #(some-> % mark-flush-failed)))))

(defn flush-session
  "Send the current timeline if there is anything to send.

  Deliberately does not retry inside one attempt: the recorder flushes on a
  debounce while recording, and the closing flush is driven by `stop-recording`,
  so the next scheduled attempt IS the retry. That keeps a failing backend from
  turning one flush into a burst."
  []
  (ptk/reify ::flush-session
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)
            s       (sr/session state file-id)]
        (when (should-flush? s)
          (->> (rp/cmd! :upsert-design-session (flush-payload s))
               (rx/mapcat (fn [_]
                            (when-not (:active? s) (forget-active!))
                            (rx/of (flushed file-id))))
               (rx/catch (fn [_] (rx/of (flush-failed file-id))))))))))

;; --- the file's session list

(defn- sessions-fetched
  [file-id rows]
  (ptk/reify ::sessions-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:design-sessions file-id] (vec rows)))))

(defn fetch-sessions
  "Load this file's recorded sessions (metadata only).

  The backend returns every session for an admin and only your own otherwise, so
  the client does no filtering — it renders what it is given."
  []
  (ptk/reify ::fetch-sessions
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [file-id (:current-file-id state)]
        (->> (rp/cmd! :get-design-sessions {:file-id file-id})
             (rx/map (fn [rows] (sessions-fetched file-id rows)))
             ;; a missing sessions database is not an error worth toasting: the
             ;; list is simply empty
             (rx/catch (fn [_] (rx/of (sessions-fetched file-id [])))))))))

(defn load-session-for-review
  "Fetch one session's full timeline and hand it to `on-ready`.

  The list carries metadata only, so a review has to pull the events. `on-ready`
  receives `[session nil]` or `[nil error-code]`."
  [id on-ready]
  (ptk/reify ::load-session-for-review
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-design-session {:id id})
           (rx/map (fn [row] (on-ready row nil) nil))
           (rx/filter some?)
           (rx/catch (fn [cause]
                       (on-ready nil (or (:code (ex-data cause)) :load-failed))
                       (rx/empty)))))))

;; --- export, the bot-facing path
;;
;; Admins only, enforced by the backend. The bundle is structured events rather
;; than prose so it can be fed straight to an agent.

(defn export-sessions
  "Fetch the export bundle for this file and hand it to `on-ready` as pretty JSON.
  `ids` narrows it; omit for everything."
  ([on-ready] (export-sessions on-ready nil))
  ([on-ready ids]
   (ptk/reify ::export-sessions
     ptk/WatchEvent
     (watch [_ state _]
       (when-let [file-id (:current-file-id state)]
         (->> (rp/cmd! :export-design-sessions
                       (cond-> {:file-id file-id}
                         (seq ids) (assoc :ids (vec ids))))
              (rx/map (fn [bundle]
                        (on-ready (js/JSON.stringify (clj->js bundle) nil 2))
                        nil))
              (rx/filter some?)
              (rx/catch (fn [cause]
                          (on-ready nil (or (:code (ex-data cause)) :export-failed))
                          (rx/empty)))))))))

;; --- resume after a reload

(defn- at-ms
  "The row's `started-at` arrives as a date, but the recorder's wall-clock cap
  subtracts it as a number. Normalize, and fall back to now rather than to 0 — a
  resumed session must not look two hours old and stop itself immediately."
  [v]
  (cond
    (number? v) v
    (some? v)   (try (inst-ms v) (catch :default _ nil))
    :else       nil))

(defn seed-resumed-session
  "Put a server row back into app state as the live recording.

  Refuses a finished session: reopening one would be rejected by the backend
  anyway (a closed row is immutable), and a recording that already ended should
  not silently continue."
  [{:keys [id file-id session-id events raw-dropped stop-reason] :as row}]
  (ptk/reify ::seed-resumed-session
    ptk/UpdateEvent
    (update [_ state]
      (if (or (nil? row) (some? stop-reason))
        state
        (assoc-in state [:session-recorder file-id]
                  {:id id
                   :file-id file-id
                   :session-id session-id
                   :started-at (or (at-ms (:started-at row))
                                   (inst-ms (js/Date.)))
                   :stopped-at nil
                   :stop-reason nil
                   :active? true
                   :events (vec events)
                   ;; raw is ephemeral; the browser that held it is gone
                   :raw []
                   :raw-dropped (or raw-dropped 0)
                   :dirty? false
                   :flush-failures 0
                   :local-only? false})))))

(defn resume-events
  "What to emit for a fetched row, or nil when it must not be resumed.

  Pure, so the composition is testable — and it needs to be, because getting it
  wrong is invisible until a row never closes. **All three events are required:**
  seeding the state alone leaves a recording that captures nothing, and seeding
  plus capture alone leaves one that never flushes and therefore never finishes.
  That second mistake shipped and was caught in live verification."
  [row]
  (when (and (some? row) (nil? (:stop-reason row)))
    [(seed-resumed-session row)
     (sr/watch-commits)
     (start-persisting)]))

(defn resume-recording
  "If this file had a recording in progress when the page went away, fetch it and
  carry on. A no-op otherwise, and a no-op if the row has since been finished or
  deleted.

  Phase 08 calls this on mount."
  []
  (ptk/reify ::resume-recording
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (when-let [row-id (some-> file-id active-session-id)]
          (->> (rp/cmd! :get-design-session {:id row-id})
               (rx/mapcat (fn [row]
                            (if-let [events (resume-events row)]
                              (rx/from events)
                              (do (forget-active!) (rx/empty)))))
               (rx/catch (fn [_]
                           ;; the row is gone, or unreadable by this profile:
                           ;; forget it rather than retrying forever
                           (forget-active!)
                           (rx/empty)))))))))

;; --- the flush subscription
;;
;; Deliberately SEPARATE from the recorder's capture subscription rather than
;; merged into it. The recorder must not depend on persistence — it works
;; perfectly well without a backend (phase 04) — and persistence needs to read
;; the recorder's state, so folding them together would put a require cycle
;; between the two. Phase 08 emits both.

(defn toggle-session-recording
  "Start or stop a recording, wiring persistence when starting.

  The right entry point for UI: `session-recorder/toggle-recording` alone would
  start capture with nothing flushing it. Lives here rather than in the recorder
  because the recorder must not depend on persistence (it works without a backend
  at all), and this direction of the dependency already exists."
  []
  (ptk/reify ::toggle-session-recording
    ptk/WatchEvent
    (watch [_ state _]
      (if (sr/recording? state)
        (rx/of (sr/stop-recording))
        (rx/of (sr/start-recording) (start-persisting))))))

(defn start-persisting
  "Flush the recording on a debounce, and once more the moment it stops.

  The debounced stream is the retry mechanism too: a failed flush leaves the
  session dirty, so the next scheduled attempt sends it again. Stops on the
  file closing, which the recorder also treats as the end of a recording."
  []
  (ptk/reify ::start-persisting
    ptk/WatchEvent
    (watch [_ state stream]
      (let [closed  (rx/filter (ptk/type? ::dw/finalize-workspace) stream)
            stops   (rx/filter (ptk/type? ::sr/stop-recording) stream)
            commits (rx/filter dch/commit? stream)]
        ;; remember the row id so a reload can find this recording again
        (when-let [s (sr/session state)]
          (remember-active! (:file-id s) (:id s)))
        (rx/merge
         ;; create the row immediately, so a recording is visible from the start
         (rx/of (flush-session))
         (->> commits
              (rx/debounce flush-debounce-ms)
              (rx/map (fn [_] (flush-session)))
              (rx/take-until (rx/merge stops closed)))
         ;; the closing flush: this is the call that finishes the row
         (->> (rx/merge stops closed)
              (rx/take 1)
              (rx/map (fn [_] (flush-session)))))))))
