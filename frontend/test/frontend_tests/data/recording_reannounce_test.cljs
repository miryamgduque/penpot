;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.recording-reannounce-test
  "Pins the re-announcement of an in-progress recording, and the ROUTING that
  triggers it.

  `:recording-update` is fire-and-forget pub/sub — the server holds no record of
  which files are being recorded — so a single announcement at start reaches
  only the sessions connected at that instant. Whoever opens the file later, and
  everyone at all once our socket drops, would be recorded with no indication.
  So the recorder re-announces on `:join-file` and on reconnect.

  An earlier version of these tests only called the leaf predicate, and would
  have passed with the routing pointed back at the old handler — the bug they
  were written for. Hence `the-join-branch-*` below: they go through
  `process-message`, so reverting the `case` branch fails a test."
  (:require
   [app.main.data.workspace.notifications :as dwn]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private file-id (uuid "00000000-0000-0000-0000-0000000000f1"))

(defn- emitted
  "The event types `event` produces from `state`.

  Assumes the watch stream is synchronous, which it is — every producer here is
  built from `rx/of`. If one ever gains a delay this returns `[]` and the
  positive tests below fail loudly rather than passing vacuously."
  [event state]
  (let [out (atom [])]
    (when-let [ob (ptk/watch event state nil)]
      (rx/subscribe ob (fn [e] (swap! out conj (ptk/type e)))))
    @out))

(def ^:private recording-state
  {:current-file-id file-id
   :session-recorder {file-id {:active? true}}})

;; --- the routing: what actually triggers a re-announcement

(t/deftest the-join-branch-reannounces-an-active-recording
  (t/testing "a session joining mid-recording must be told; pointing this
              branch back at plain handle-presence is the bug this catches"
    (let [ev (dwn/process-message {:type :join-file :session-id "s2"})]
      (t/is (= :app.main.data.workspace.notifications/handle-join-file
               (ptk/type ev)))
      (t/is (= #{:app.main.data.workspace.notifications/handle-presence
                 :app.main.data.workspace.notifications/reannounce-recording}
               (set (emitted ev recording-state)))
            "presence first, then the announcement"))))

(t/deftest the-join-branch-still-tracks-presence-when-not-recording
  (t/testing "the re-announcement must not cost us the presence bookkeeping
              that this branch was doing before"
    (let [ev (dwn/process-message {:type :join-file :session-id "s2"})]
      (t/is (contains? (set (emitted ev {:current-file-id file-id}))
                       :app.main.data.workspace.notifications/handle-presence)))))

;; --- the announcement itself

(t/deftest a-recording-session-reannounces
  (t/is (= [:app.main.data.workspace.notifications/broadcast-recording]
           (emitted (dwn/reannounce-recording) recording-state))))

(t/deftest a-session-that-is-not-recording-stays-quiet
  (t/testing "a stray re-announce would tell the whole file it is being
              recorded when it is not — a false REC is its own harm"
    (t/is (empty? (emitted (dwn/reannounce-recording)
                           {:current-file-id file-id
                            :session-recorder {file-id {:active? false}}})))
    (t/is (empty? (emitted (dwn/reannounce-recording)
                           {:current-file-id file-id})))))

(t/deftest a-recording-on-another-file-does-not-reannounce-here
  (let [other (uuid "00000000-0000-0000-0000-0000000000f2")]
    (t/is (empty? (emitted (dwn/reannounce-recording)
                           {:current-file-id file-id
                            :session-recorder {other {:active? true}}})))))

;; --- receiving an announcement

(t/deftest an-update-is-accepted-before-its-join
  (t/testing "the whole reason recording state is not kept on the presence
              entry: dropping an early update would leave someone recorded and
              unwarned for the rest of their session, with nothing to re-send it"
    (let [state (ptk/update (dwn/handle-recording-update
                             {:session-id "s1" :recording? true})
                            {})]
      (t/is (contains? (:workspace-recording state) "s1")))))

(t/deftest stopping-clears-only-the-session-that-stopped
  (t/testing "two people can record at once; one stopping must not clear the
              indicator while the other is still going"
    (let [state (-> {}
                    (->> (ptk/update (dwn/handle-recording-update
                                      {:session-id "s1" :recording? true})))
                    (->> (ptk/update (dwn/handle-recording-update
                                      {:session-id "s2" :recording? true})))
                    (->> (ptk/update (dwn/handle-recording-update
                                      {:session-id "s1" :recording? false}))))]
      (t/is (= #{"s2"} (:workspace-recording state))))))

(t/deftest leaving-clears-the-recording-flag
  (t/testing "a collaborator who closes the tab mid-recording must not leave
              the indicator stuck on"
    (t/are [type] (empty? (:workspace-recording
                           (ptk/update (dwn/handle-presence
                                        {:type type :session-id "s1"})
                                       {:workspace-recording #{"s1"}
                                        :workspace-presence {"s1" {}}})))
      :disconnect
      :leave-file)))
