;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.recording-disclosure-test
  "Pins the disclosure rule behind the workspace recording indicator.

  A design session captures identifiable activity by people who did not start
  it. The consent story therefore depends on one thing being true: everyone on
  the file can tell a recording is running, *including* someone who never opens
  the AI panel. `recording-in-progress?` is what the always-visible indicator
  reads, so the collaborator case below is the one that matters — the local case
  is the easy half and was never at risk."
  (:require
   [app.main.refs :as refs]
   [cljs.test :as t :include-macros true]))

(def ^:private file-id (uuid "00000000-0000-0000-0000-0000000000f1"))

(defn- state
  [& {:as overrides}]
  (merge {:current-file-id file-id} overrides))

(t/deftest nothing-recording-shows-nothing
  (t/is (false? (refs/recording-in-progress? (state))))
  (t/is (false? (refs/recording-in-progress?
                 (state :session-recorder {file-id {:active? false}}
                        :workspace-recording #{})))))

(t/deftest our-own-recording-is-disclosed
  (t/is (true? (refs/recording-in-progress?
                (state :session-recorder {file-id {:active? true}})))))

(t/deftest a-collaborators-recording-is-disclosed
  (t/testing "the case the indicator exists for: someone else pressed record,
              we are being recorded, and we may never open the panel"
    (t/is (true? (refs/recording-in-progress?
                  (state :workspace-recording #{"s2"}))))))

(t/deftest leaving-clears-the-disclosure
  (t/testing ":workspace-recording is cleared on disconnect / leave-file, so a
              collaborator closing the tab mid-recording must not leave the
              indicator stuck on — a permanent false REC is its own harm"
    (t/is (false? (refs/recording-in-progress?
                   (state :workspace-recording #{}))))))

(t/deftest a-recording-on-another-file-is-not-ours
  (t/testing "the local recorder is keyed by file, so a session running in
              another tab's file must not light up this one"
    (let [other (uuid "00000000-0000-0000-0000-0000000000f2")]
      (t/is (false? (refs/recording-in-progress?
                     (state :session-recorder {other {:active? true}})))))))
