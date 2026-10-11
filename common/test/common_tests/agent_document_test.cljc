;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-document-test
  (:require
   [app.common.agent.document :as cad]
   [app.common.files.changes :as cfc]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private data (ctf/make-file-data (uuid/next) (uuid/next)))

(defn- page-names
  [data]
  (mapv #(get-in data [:pages-index % :name]) (:pages data)))

(t/deftest create-page-adds-a-page-without-moving
  (let [named   (cad/create-page {:data data} {:name "  Playground "} nil)
        after   (cfc/process-changes data (:changes named))
        unnamed (cad/create-page {:data after} {} nil)
        after   (cfc/process-changes after (:changes unnamed))]
    (t/is (= ["Page 1" "Playground" "Page 2"] (page-names after)))
    (t/is (contains? (:pages-index after) (parse-uuid (-> named :result :pageId))))
    (t/is (re-find #"named \"Playground\" — you are NOT on it" (-> named :result :note)))))
