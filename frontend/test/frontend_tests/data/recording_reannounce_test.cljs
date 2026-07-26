;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.recording-reannounce-test
  "Pins the re-announcement of an in-progress recording.

  `:recording-update` is fire-and-forget pub/sub — the server holds no record of
  which files are being recorded — so a single announcement at start reaches
  only the sessions connected at that instant. Two people are therefore left
  unwarned while their activity is captured, which is precisely the failure the
  indicator exists to prevent:

  - whoever OPENS the file after recording began;
  - everyone, once our websocket drops, because each peer drops our presence
    entry (and the recording flag with it) and nothing restores it.

  So we re-announce on join and on reconnect. These tests pin that the
  announcement is conditional on actually recording — a re-announce from a
  session that is not recording would light up a false REC for the whole file."
  (:require
   [app.main.data.workspace.notifications :as dwn]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private file-id (uuid "00000000-0000-0000-0000-0000000000f1"))

(defn- emitted
  "The event types `reannounce-recording` produces for `state`.

  `watch` returns nil when there is nothing to announce, which is the quiet
  case these tests care about most."
  [state]
  (let [out (atom [])]
    (when-let [ob (ptk/watch (dwn/reannounce-recording) state nil)]
      (rx/subscribe ob (fn [e] (swap! out conj (ptk/type e)))))
    @out))

(t/deftest a-recording-session-reannounces
  (t/testing "the late joiner and the reconnect both depend on this firing"
    (let [state {:current-file-id file-id
                 :session-recorder {file-id {:active? true}}}]
      (t/is (= [:app.main.data.workspace.notifications/broadcast-recording]
               (emitted state))))))

(t/deftest a-session-that-is-not-recording-stays-quiet
  (t/testing "a stray re-announce would tell the whole file it is being
              recorded when it is not — a false REC is its own harm"
    (t/is (empty? (emitted {:current-file-id file-id
                            :session-recorder {file-id {:active? false}}})))
    (t/is (empty? (emitted {:current-file-id file-id})))))

(t/deftest a-recording-on-another-file-does-not-reannounce-here
  (t/testing "the recorder is keyed by file; announcing for the wrong file
              would mark a file nobody is recording"
    (let [other (uuid "00000000-0000-0000-0000-0000000000f2")]
      (t/is (empty? (emitted {:current-file-id file-id
                              :session-recorder {other {:active? true}}}))))))

(t/deftest an-update-for-an-unknown-session-is-ignored
  (t/testing "a recording-update arriving before its join would otherwise
              create a presence entry with no profile behind it"
    (let [state  {:workspace-presence {}}
          state' (ptk/update (dwn/handle-recording-update
                              {:session-id "s1" :recording? true})
                             state)]
      (t/is (= {} (:workspace-presence state'))))))

(t/deftest a-known-session-is-annotated-both-ways
  (let [state {:workspace-presence {"s1" {:profile-id "p1"}}}
        on    (ptk/update (dwn/handle-recording-update
                           {:session-id "s1" :recording? true}) state)
        off   (ptk/update (dwn/handle-recording-update
                           {:session-id "s1" :recording? false}) on)]
    (t/is (true? (get-in on [:workspace-presence "s1" :recording?]))
          "a repeated announcement is idempotent, which is what lets us
           re-announce freely on join and reconnect")
    (t/is (false? (get-in off [:workspace-presence "s1" :recording?]))
          "stopping must clear it, not just leave it stale")))
