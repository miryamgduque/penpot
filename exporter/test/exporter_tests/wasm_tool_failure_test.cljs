;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns exporter-tests.wasm-tool-failure-test
  (:require
   [app.wasm.tool-failure :as tool-failure]
   [cljs.test :as t :include-macros true]))

(t/deftest a-change-the-file-rejects-is-told-to-the-model
  ;; what a token name passed as a fill used to end in: a 500 and a lost worker
  (let [answer (tool-failure/answer "create_shape"
                                    (ex-info "expected valid shape"
                                             {:type :internal :code :data-validation :hint "expected valid shape"}))]
    (t/is (true? (:isError answer)))
    (t/is (re-find #"^create_shape: expected valid shape" (:content answer)))
    (t/is (re-find #"invalid" (:content answer)))))

(t/deftest a-failure-that-is-not-the-tools-still-fails-the-call
  (t/is (nil? (tool-failure/answer "create_shape"
                                   (ex-info "refused" {:code :agent-backend-refused :status 400}))))
  (t/is (nil? (tool-failure/answer "create_shape"
                                   (ex-info "bad token" {:type :validation :code :invalid-turn-token}))))
  ;; the engine itself breaking carries no data
  (t/is (nil? (tool-failure/answer "create_shape" (js/Error. "RuntimeError: unreachable")))))
