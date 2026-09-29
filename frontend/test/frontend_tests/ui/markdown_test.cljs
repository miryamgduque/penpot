;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.markdown-test
  "Pins the transcript markdown renderer's `shape:` ref contract (phase 05 of
  the OSS-prompt-learnings plan): a well-formed `shape:<uuid>` href becomes a
  clickable ref, everything else stays plain text — the same fail-closed
  posture as the https/mailto allowlist."
  (:require
   [app.main.ui.components.markdown :as md]
   [cljs.test :as t :include-macros true]))

(def ^:private uuid-str "00000000-0000-0000-0000-000000000001")

(t/deftest shape-ref-id-accepts-a-shape-uuid
  (t/is (= uuid-str (md/shape-ref-id (str "shape:" uuid-str))))
  (t/testing "scheme is case-insensitive, like real URL schemes"
    (t/is (= uuid-str (md/shape-ref-id (str "SHAPE:" uuid-str))))))

(t/deftest shape-ref-id-rejects-everything-else
  (t/testing "malformed ids and foreign schemes render as plain text"
    (t/is (nil? (md/shape-ref-id "shape:not-a-uuid")))
    (t/is (nil? (md/shape-ref-id "shape:")))
    (t/is (nil? (md/shape-ref-id (str "shape:" uuid-str "/extra"))))
    (t/is (nil? (md/shape-ref-id (str "shape: " uuid-str))))
    (t/is (nil? (md/shape-ref-id (str "https://example.com/" uuid-str))))
    (t/is (nil? (md/shape-ref-id "javascript:alert(1)")))
    (t/is (nil? (md/shape-ref-id nil)))))
