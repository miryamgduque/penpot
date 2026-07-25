;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.session-recorder
  "Recording lifecycle for a design session: start, capture, cap, stop.

  Phase 04 of the design-session-recording plan. This is the last phase with no
  backend — a session lives in app state under `[:session-recorder <file-id>]`
  and is drivable from the browser console. Phase 07 adds persistence.

  ## What it captures

  Every commit on the global stream, local and remote alike, which is what makes
  a recording cover *everyone* on the file rather than only this browser. Each
  commit arrives already attributed (phases 02 and 03: `:profile-id`,
  `:session-id`, `:who`, `:provider`, `:model`), so the recorder does no
  attribution of its own — it filters noise and folds commits into the timeline
  via `session-events/absorb`.

  ## Raw versus timeline

  The discovery decision was \"raw ops as source of truth, derived summary for
  the model\", with raw ephemeral. So:

  - `:events` is the semantic timeline. It is the durable part, it never
    silently truncates, and it is what a reviewing model reads.
  - `:raw` is a bounded rolling buffer of commits, kept only for the life of the
    recording and dropped at stop. When it rolls, `:raw-dropped` counts what it
    let go.

  ## No silent caps

  Three ceilings, and each one that ends a recording records WHY
  (`:stop-reason`). An unexplained truncation reads as \"this is everything that
  happened\", and the phase-09 critique would then reason from a false record —
  which is precisely the failure this feature exists to catch in humans.

  The event cap ends the recording rather than dropping events, because the
  timeline is the durable record. The raw cap only rolls the buffer, because raw
  is ephemeral anyway and losing its oldest entries costs nothing the timeline
  has not already captured."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.session-events :as se]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def max-events
  "Ceiling on the semantic timeline. Reaching it stops the recording; the
  timeline is the durable record and must not silently lose its tail."
  5000)

(def max-raw
  "Ceiling on the retained raw commits. Rolls (oldest out) rather than stopping
  the recording — raw is ephemeral and the timeline has already absorbed it."
  20000)

(def max-duration-ms
  "Wall-clock ceiling on one recording: two hours. Checked when the next commit
  arrives rather than on a timer — an idle recording is capturing nothing, so
  there is nothing to protect against in the meantime. The consequence is that a
  session left open past the ceiling reads as active until something happens;
  the stop reason is still recorded truthfully when it does."
  (* 2 60 60 1000))

(defn- now-ms
  []
  (inst-ms (js/Date.)))

;; --- reading state

(defn session
  "The recording for the current file, if any."
  ([state] (session state (:current-file-id state)))
  ([state file-id]
   (get-in state [:session-recorder file-id])))

(defn recording?
  [state]
  (true? (:active? (session state))))

(defn events
  "The semantic timeline so far."
  [state]
  (:events (session state) []))

;; --- lifecycle

(defn- stop-session
  "Mark a session stopped, keeping the FIRST reason: a cap that ended the
  recording should not be relabelled by the teardown stop that follows it."
  [s reason]
  (if (:active? s)
    (assoc s
           :active? false
           :stopped-at (now-ms)
           :stop-reason reason
           ;; raw is ephemeral by decision — it existed to back the recording
           :raw [])
    s))

(defn stop-recording
  "End the current recording. `reason` distinguishes a person pressing stop from
  a ceiling or the file closing — the critique should be able to tell."
  ([] (stop-recording :manual))
  ([reason]
   (ptk/reify ::stop-recording
     ptk/UpdateEvent
     (update [_ state]
       (if-let [file-id (:current-file-id state)]
         (update-in state [:session-recorder file-id]
                    (fn [s] (when s (stop-session s reason))))
         state)))))

(defn trim-raw
  "Bound the raw buffer, oldest out. Returns `[raw dropped]` — the count is what
  keeps the roll disclosed rather than silent.

  Materializes rather than `subvec`-ing so repeated trims cannot chain views
  onto an ever-growing backing vector."
  [raw]
  (let [over (max 0 (- (count raw) max-raw))]
    (if (pos? over)
      [(into [] (drop over) raw) over]
      [raw 0])))

(defn absorb-into
  "Fold one commit into a session map, applying the raw bound and the event
  ceiling. Pure, so the cap rules are testable without driving thousands of
  events through the store."
  [s commit]
  (let [events' (se/absorb (:events s) commit)
        raw'    (cond-> (:raw s)
                  (se/recordable? commit) (conj commit))
        [raw dropped] (trim-raw raw')
        s'      (assoc s
                       :events events'
                       :raw raw
                       :raw-dropped (+ (:raw-dropped s 0) dropped))]
    (if (>= (count events') max-events)
      (stop-session s' :event-cap)
      s')))

(defn record-commit
  "Fold one commit into the active recording.

  Public because it is the recorder's core and both the subscription and the
  tests drive it. A no-op unless a recording is active for the commit's own
  file — the commit stream is global."
  [commit]
  (ptk/reify ::record-commit
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)
            s       (session state file-id)]
        (cond
          (not (and s (:active? s) (= file-id (:file-id commit))))
          state

          (> (- (now-ms) (:started-at s 0)) max-duration-ms)
          (update-in state [:session-recorder file-id] stop-session :time-cap)

          :else
          (assoc-in state [:session-recorder file-id] (absorb-into s commit)))))))

(defn- watch-commits
  "Subscribe to the commit stream until the recording ends or the file closes.

  Stops on `::stop-recording` and on `::dw/finalize-workspace`; the latter also
  emits an explicit stop so a recording can never outlive its file, nor be
  resumed by reopening one."
  []
  (ptk/reify ::watch-commits
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [closed  (rx/filter (ptk/type? ::dw/finalize-workspace) stream)
            stopper (rx/merge
                     (rx/filter (ptk/type? ::stop-recording) stream)
                     closed)]
        (rx/merge
         (->> stream
              (rx/filter dch/commit?)
              (rx/map deref)
              (rx/map record-commit)
              (rx/take-until stopper))
         (->> closed
              (rx/take 1)
              (rx/map (fn [_] (stop-recording :file-closed)))))))))

(defn start-recording
  "Begin recording the current file. A no-op with no file open — a recording is
  meaningless without one."
  []
  (ptk/reify ::start-recording
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (assoc-in state [:session-recorder file-id]
                  {:id (uuid/next)
                   :file-id file-id
                   :started-at (now-ms)
                   :stopped-at nil
                   :stop-reason nil
                   :active? true
                   :events []
                   :raw []
                   :raw-dropped 0})
        state))

    ptk/WatchEvent
    (watch [_ state _]
      ;; `update` runs before `watch` on the same event, so a missing file has
      ;; already been declined above and there is nothing to subscribe to
      (when (recording? state)
        (rx/of (watch-commits))))))

(defn toggle-recording
  []
  (ptk/reify ::toggle-recording
    ptk/WatchEvent
    (watch [_ state _]
      (rx/of (if (recording? state)
               (stop-recording)
               (start-recording))))))
