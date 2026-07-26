;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.session-persist-test
  "Pins the flush lifecycle (phase 07 of the design-session-recording plan).

  The rule that shapes all of it: **never lose events, and never quietly keep
  them.** If a flush fails, retry; if it keeps failing, say the recording is
  local-only from here. A recording that silently stopped persisting halfway is a
  false record, and the phase-09 critique would reason from it — exactly the
  failure this feature exists to catch in humans.

  The flush *decisions* are pure functions so they can be pinned without mocking
  the RPC layer; the event that calls `rp/cmd!` is thin wiring over them."
  (:require
   [app.main.data.workspace.session-persist :as sp]
   [app.main.data.workspace.session-recorder :as sr]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private file-id (uuid "00000000-0000-0000-0000-0000000000f1"))
(def ^:private page-id (uuid "00000000-0000-0000-0000-0000000000aa"))
(def ^:private shape-id (uuid "00000000-0000-0000-0000-000000000001"))

(def ^:private base-state
  {:current-file-id file-id
   :current-page-id page-id})

(defn- commit
  [& {:as overrides}]
  (merge {:id (uuid "00000000-0000-0000-0000-0000000000ff")
          :file-id file-id
          :created-at 1000
          :source :local
          :origin :potok.v2.core/undefined
          :who :user
          :tags #{}
          :undo-group nil
          :redo-changes [{:type :mod-obj
                          :id shape-id
                          :page-id page-id
                          :operations [{:type :set :attr :x :val 1}]}]}
         overrides))

(defn- session-after-one-commit
  []
  (-> (ptk/update (sr/start-recording) base-state)
      (as-> st (ptk/update (sr/record-commit (commit)) st))
      (sr/session)))

;; --- dirty tracking decides whether to flush at all

(t/deftest a-fresh-session-needs-its-row-created
  (t/testing "the row should exist before events are flushed, so a recording is
              visible as soon as it starts"
    (let [s (sr/session (ptk/update (sr/start-recording) base-state))]
      (t/is (true? (sp/should-flush? s))
            "the create IS the first flush — phase 06 collapsed create into upsert"))))

(t/deftest recording-a-commit-marks-the-session-dirty
  (let [s (session-after-one-commit)]
    (t/is (true? (:dirty? s)))
    (t/is (true? (sp/should-flush? s)))))

(t/deftest a-clean-session-is-not-reflushed
  (t/testing "the whole timeline rides every flush, so re-sending an unchanged
              one is pure waste"
    (let [s (sp/mark-flushed (session-after-one-commit))]
      (t/is (false? (:dirty? s)))
      (t/is (false? (sp/should-flush? s))))))

(t/deftest a-new-commit-after-a-flush-makes-it-dirty-again
  (let [state (-> (ptk/update (sr/start-recording) base-state)
                  (as-> st (ptk/update (sr/record-commit (commit)) st))
                  (update-in [:session-recorder file-id] sp/mark-flushed)
                  (as-> st (ptk/update (sr/record-commit (commit :created-at 2000)) st)))]
    (t/is (true? (sp/should-flush? (sr/session state))))))

;; --- failure handling: retry, then disclose

(t/deftest a-failed-flush-keeps-the-events-and-stays-dirty
  (t/testing "events must survive a failed flush — the next one retries"
    (let [s  (session-after-one-commit)
          s' (sp/mark-flush-failed s)]
      (t/is (true? (:dirty? s')) "still pending, so it will be retried")
      (t/is (= 1 (:flush-failures s')))
      (t/is (seq (:events s')) "and nothing was dropped")
      (t/is (not (:local-only? s'))))))

(t/deftest repeated-failures-degrade-to-local-only-and-say-so
  (t/testing "the honest end state: stop pretending it is persisting, tell the
              user, and keep recording"
    (let [s (reduce (fn [s _] (sp/mark-flush-failed s))
                    (session-after-one-commit)
                    (range sp/max-flush-failures))]
      (t/is (true? (:local-only? s))
            "disclosed — a half-persisted recording must not look complete")
      (t/is (false? (sp/should-flush? s))
            "and it stops hammering a backend that is not answering")
      (t/is (true? (:active? s))
            "but recording continues; losing the rest would be worse"))))

(t/deftest a-successful-flush-clears-a-failure-streak
  (let [s (-> (session-after-one-commit)
              (sp/mark-flush-failed)
              (sp/mark-flush-failed)
              (sp/mark-flushed))]
    (t/is (= 0 (:flush-failures s)))
    (t/is (false? (:dirty? s)))))

(defn- degraded-session
  "A session that hit the failure ceiling and went local-only."
  []
  (reduce (fn [s _] (sp/mark-flush-failed s))
          (session-after-one-commit)
          (range sp/max-flush-failures)))

(t/deftest the-closing-flush-is-attempted-even-when-local-only
  (t/testing "local-only exists to stop hammering an unresponsive backend, and
              one attempt on stop is not hammering. Without this the row keeps
              stopped_at NULL forever once a mid-recording blip trips the flag —
              the very outcome the resume path exists to avoid"
    (let [s (-> (degraded-session)
                (assoc :active? false :stopped-at 2000 :stop-reason :manual :dirty? true))]
      (t/is (true? (:local-only? s)))
      (t/is (true? (sp/should-flush? s))
            "the closing flush gets its attempt"))))

(t/deftest local-only-still-suppresses-flushes-while-recording
  (t/testing "the exception is the closing flush only — an open session that
              gave up must not resume hammering"
    (t/is (false? (sp/should-flush? (degraded-session))))))

(t/deftest a-successful-flush-lifts-local-only
  (t/testing "the backend answered, so the session is demonstrably not
              local-only — leaving the flag set would keep telling the user a
              recording did not persist when it did"
    (let [s (sp/mark-flushed (degraded-session))]
      (t/is (false? (:local-only? s))))))

;; --- the payload

(t/deftest the-payload-carries-what-the-backend-needs
  (let [s (session-after-one-commit)
        p (sp/flush-payload s)]
    (t/are [k] (contains? p k)
      :id :file-id :session-id :events :raw-dropped)
    (t/is (nil? (:stop-reason p)) "an open session sends no stop-reason")))

(t/deftest the-payload-discloses-a-rolled-raw-buffer
  (t/testing "raw-dropped must reach the row, or a partial raw record looks complete"
    (let [s (assoc (session-after-one-commit) :raw-dropped 42)]
      (t/is (= 42 (:raw-dropped (sp/flush-payload s)))))))

(t/deftest a-stopped-session-sends-its-stop-reason
  (t/testing "the closing flush is what finishes the row — and a cap must not
              look like a clean stop"
    (let [state (-> (ptk/update (sr/start-recording) base-state)
                    (as-> st (ptk/update (sr/record-commit (commit)) st))
                    (as-> st (ptk/update (sr/stop-recording :event-cap) st)))
          p     (sp/flush-payload (sr/session state))]
      (t/is (= "event-cap" (:stop-reason p))
            "sent as a string: the column is text and the reason is data, not code"))))

(t/deftest stopping-forces-a-final-flush-even-if-clean
  (t/testing "the stop must reach the server even when no new events arrived
              since the last flush, or the row never closes"
    (let [state (-> (ptk/update (sr/start-recording) base-state)
                    (as-> st (ptk/update (sr/record-commit (commit)) st))
                    (update-in [:session-recorder file-id] sp/mark-flushed)
                    (as-> st (ptk/update (sr/stop-recording) st)))]
      (t/is (true? (sp/should-flush? (sr/session state)))
            "a stop dirties the session so the closing upsert is sent"))))

;; --- reload

(t/deftest an-unfinished-session-can-be-resumed-from-a-server-row
  (t/testing "a browser refresh is not a stop: the recording resumes rather than
              leaving a row that never closes"
    (let [row   {:id (uuid "00000000-0000-0000-0000-0000000000e1")
                 :file-id file-id
                 :session-id (uuid "00000000-0000-0000-0000-0000000000e2")
                 :events [{:kind :create :label "created 1 shape" :who :user :at 1}]
                 :raw-dropped 3
                 :stop-reason nil}
          state (ptk/update (sp/seed-resumed-session row) base-state)
          s     (sr/session state)]
      (t/is (true? (:active? s)) "recording again")
      (t/is (= 1 (count (:events s))) "with the timeline it had")
      (t/is (= 3 (:raw-dropped s)) "and its disclosed raw loss")
      (t/is (false? (:dirty? s)) "nothing new to send yet")
      (t/is (empty? (:raw s))
            "raw is NOT resumed — it is ephemeral and the browser that held it is gone"))))

(t/deftest resuming-restarts-both-capture-and-flushing
  (t/testing "REGRESSION found in live verification (phase 08): resume restarted
              capture ONLY, so a resumed recording went on collecting events but
              never flushed again and its row stayed `stopped_at IS NULL` forever
              — the exact 'sessions that never end' failure the resume design
              exists to prevent. Unit tests missed it because each piece worked;
              only the composition was wrong."
    (let [row    {:id (uuid "00000000-0000-0000-0000-0000000000e1")
                  :file-id file-id
                  :session-id (uuid "00000000-0000-0000-0000-0000000000e2")
                  :events []
                  :stop-reason nil}
          types  (mapv ptk/type (sp/resume-events row))]
      (t/is (= 3 (count types))
            "seed + capture + flush; two of the three is the bug")
      (t/is (contains? (set types) :app.main.data.workspace.session-persist/seed-resumed-session))
      (t/is (contains? (set types) :app.main.data.workspace.session-recorder/watch-commits)
            "capture, or the resumed recording records nothing")
      (t/is (contains? (set types) :app.main.data.workspace.session-persist/start-persisting)
            "flushing, or the row never closes"))))

(t/deftest a-finished-or-missing-row-yields-no-resume-events
  (t/is (nil? (sp/resume-events nil)))
  (t/is (nil? (sp/resume-events {:id (uuid "00000000-0000-0000-0000-0000000000e1")
                                 :file-id file-id
                                 :stop-reason "manual"}))))

(t/deftest a-finished-session-is-never-resumed
  (t/testing "resuming a closed recording would reopen an immutable row, which
              the backend rejects anyway"
    (let [row   {:id (uuid "00000000-0000-0000-0000-0000000000e1")
                 :file-id file-id
                 :session-id (uuid "00000000-0000-0000-0000-0000000000e2")
                 :events []
                 :stop-reason "manual"}
          state (ptk/update (sp/seed-resumed-session row) base-state)]
      (t/is (nil? (sr/session state))))))
