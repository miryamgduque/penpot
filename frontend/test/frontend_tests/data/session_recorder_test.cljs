;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.session-recorder-test
  "Pins the recorder lifecycle (phase 04 of the design-session-recording plan):
  start, capture, cap, stop.

  The governing rule is **no silent caps**. When a ceiling is hit the session has
  to say so — an unexplained truncation reads as \"this is everything that
  happened\", and the phase-09 critique would then reason from a false record.
  Same principle the agent's own round cap follows."
  (:require
   [app.main.data.workspace.session-recorder :as sr]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private file-id (uuid "00000000-0000-0000-0000-0000000000f1"))
(def ^:private other-file-id (uuid "00000000-0000-0000-0000-0000000000f2"))
(def ^:private page-id (uuid "00000000-0000-0000-0000-0000000000aa"))
(def ^:private shape-id (uuid "00000000-0000-0000-0000-000000000001"))
(def ^:private group-1 (uuid "00000000-0000-0000-0000-0000000000b1"))
(def ^:private group-2 (uuid "00000000-0000-0000-0000-0000000000b2"))

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

(defn- start
  ([] (start base-state))
  ([state] (ptk/update (sr/start-recording) state)))

(defn- feed
  "Apply a sequence of commits through the recorder's per-commit fold."
  [state commits]
  (reduce (fn [st c] (ptk/update (sr/record-commit c) st)) state commits))

;; --- start

(t/deftest start-recording-seeds-a-session
  (let [state (start)
        s     (sr/session state)]
    (t/is (sr/recording? state))
    (t/is (some? (:id s)) "a client-generated session id, so saves are idempotent")
    (t/is (= file-id (:file-id s)))
    (t/is (some? (:started-at s)))
    (t/is (= [] (:events s)))
    (t/is (nil? (:stop-reason s)))))

(t/deftest starting-without-a-file-records-nothing
  (t/testing "a recording is meaningless with no file open"
    (let [state (ptk/update (sr/start-recording) {})]
      (t/is (not (sr/recording? state))))))

;; --- capture

(t/deftest commits-are-ignored-while-inactive
  (t/testing "the stream is global; an unstarted recorder must stay empty"
    (let [state (feed base-state [(commit)])]
      (t/is (nil? (sr/session state))))))

(t/deftest commits-are-captured-while-active
  (let [state (feed (start) [(commit)])]
    (t/is (= 1 (count (sr/events state))))
    (t/is (= :move (:kind (first (sr/events state)))))))

(t/deftest noise-is-filtered-out
  (t/testing "machine writebacks are not interactions — see session-events"
    (let [state (feed (start)
                      [(commit :tags #{:position-data})
                       (commit :origin :app.main.data.workspace.fix-deleted-fonts/fix-deleted-fonts-for-page)])]
      (t/is (= 0 (count (sr/events state)))))))

(t/deftest one-gesture-coalesces-into-one-event
  (t/testing "a drag emits dozens of commits; the timeline shows the gesture"
    (let [state (feed (start)
                      [(commit :created-at 1 :undo-group group-1)
                       (commit :created-at 2 :undo-group group-1)
                       (commit :created-at 3 :undo-group group-1)])
          ev    (first (sr/events state))]
      (t/is (= 1 (count (sr/events state))))
      (t/is (= 3 (:commits ev)) "the tally accumulates across an incremental fold")
      (t/is (= 1 (:at ev)) "the gesture keeps its first timestamp"))))

(t/deftest distinct-gestures-stay-distinct
  (let [state (feed (start)
                    [(commit :created-at 1 :undo-group group-1)
                     (commit :created-at 2 :undo-group group-2)])]
    (t/is (= 2 (count (sr/events state))))))

(t/deftest commits-for-another-file-are-ignored
  (t/testing "guard against recording a file we are not recording"
    (let [state (feed (start) [(commit :file-id other-file-id)])]
      (t/is (= 0 (count (sr/events state)))))))

;; --- raw buffer

(t/deftest raw-commits-back-the-session-while-recording
  (t/testing "raw is the source of truth during the recording (the discovery
              decision was raw + derived summary)"
    (let [state (feed (start) [(commit) (commit)])]
      (t/is (= 2 (count (:raw (sr/session state))))))))

(t/deftest the-raw-buffer-is-bounded-and-discloses-what-it-dropped
  (t/testing "raw rolls rather than growing without bound — but the drop is
              COUNTED, never silent"
    (let [[raw dropped] (sr/trim-raw (vec (range (+ sr/max-raw 5))))]
      (t/is (= sr/max-raw (count raw)) "bounded")
      (t/is (= 5 dropped) "and it says how many it let go")
      (t/is (= 5 (first raw)) "oldest out, newest kept"))))

(t/deftest an-under-full-raw-buffer-is-left-alone
  (let [v (vec (range 10))
        [raw dropped] (sr/trim-raw v)]
    (t/is (= v raw))
    (t/is (= 0 dropped))))

(t/deftest a-rolling-raw-buffer-does-not-end-the-recording
  (t/testing "raw is ephemeral; the timeline is the durable part and is intact"
    (let [s (assoc (sr/session (start))
                   :raw (vec (range sr/max-raw)))
          s' (sr/absorb-into s (commit))]
      (t/is (:active? s'))
      (t/is (= 1 (:raw-dropped s')))
      (t/is (= 1 (count (:events s')))))))

;; --- caps

(t/deftest hitting-the-event-cap-stops-recording-with-a-reason
  (t/testing "the timeline is the durable record, so it must never silently
              truncate: recording ends and says why"
    (let [;; one short of the ceiling, then one more commit tips it
          s  (assoc (sr/session (start))
                    :events (vec (repeat (dec sr/max-events)
                                         {:kind :move :undo-group nil :commits 1})))
          s' (sr/absorb-into s (commit))]
      (t/is (false? (:active? s')))
      (t/is (= :event-cap (:stop-reason s')))
      (t/is (= sr/max-events (count (:events s')))
            "capped at the ceiling, not one past it")
      (t/is (some? (:stopped-at s'))))))

(t/deftest below-the-event-cap-recording-continues
  (let [s  (assoc (sr/session (start))
                  :events (vec (repeat (- sr/max-events 2)
                                       {:kind :move :undo-group nil :commits 1})))
        s' (sr/absorb-into s (commit))]
    (t/is (:active? s'))
    (t/is (nil? (:stop-reason s')))))

(t/deftest a-stopped-recording-ignores-further-commits
  (let [state (-> (start)
                  (as-> st (ptk/update (sr/stop-recording) st))
                  (feed [(commit)]))]
    (t/is (= 0 (count (sr/events state))))))

(t/deftest a-session-past-the-time-ceiling-stops
  (t/testing "a wall-clock ceiling, checked on the next commit rather than by a
              timer — an idle recording is capturing nothing anyway"
    (let [state (-> (start)
                    ;; started at the epoch, so any real clock is past the ceiling
                    (assoc-in [:session-recorder file-id :started-at] 0)
                    (feed [(commit)]))]
      (t/is (not (sr/recording? state)))
      (t/is (= :time-cap (:stop-reason (sr/session state)))))))

;; --- stop

(t/deftest stop-recording-finalizes-and-keeps-the-timeline
  (let [state (-> (start)
                  (feed [(commit)])
                  (as-> st (ptk/update (sr/stop-recording) st)))
        s     (sr/session state)]
    (t/is (not (sr/recording? state)))
    (t/is (= :manual (:stop-reason s)))
    (t/is (some? (:stopped-at s)))
    (t/is (= 1 (count (:events s))) "the timeline survives the stop")))

(t/deftest stop-drops-the-raw-buffer
  (t/testing "raw is ephemeral by decision — it exists for the recording, the
              semantic timeline is what persists"
    (let [state (-> (start)
                    (feed [(commit) (commit)])
                    (as-> st (ptk/update (sr/stop-recording) st)))]
      (t/is (empty? (:raw (sr/session state))))
      (t/is (seq (:events (sr/session state)))))))

(t/deftest stop-records-an-explicit-reason
  (t/testing "a file closing mid-recording is a different story from a person
              pressing stop, and the critique should be able to tell"
    (let [state (-> (start)
                    (as-> st (ptk/update (sr/stop-recording :file-closed) st)))]
      (t/is (= :file-closed (:stop-reason (sr/session state)))))))

(t/deftest stopping-twice-keeps-the-first-reason
  (t/testing "a cap stop followed by the teardown stop must not be relabelled"
    (let [state (-> (start)
                    (as-> st (ptk/update (sr/stop-recording :time-cap) st))
                    (as-> st (ptk/update (sr/stop-recording :file-closed) st)))]
      (t/is (= :time-cap (:stop-reason (sr/session state)))))))
