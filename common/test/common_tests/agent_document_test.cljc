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

(t/deftest set-foundation-stores-the-doc-in-the-file
  (let [saved   (cad/set-foundation {:data data} {:name "Tone of Voice!" :doc "  Be brief.  "} nil)
        after   (cfc/process-changes data (:changes saved))
        vibes   (cad/set-foundation {:data after} {:name "Vibes" :doc "Calm."} nil)
        after   (cfc/process-changes after (:changes vibes))
        removed (cad/set-foundation {:data after} {:name "tone of voice" :doc ""} nil)
        gone    (cfc/process-changes after (:changes removed))]
    (t/is (= {"foundation/tone-of-voice" "Be brief." "design-md" "Calm."}
             (get-in after [:plugin-data :penpot-vibes])))
    (t/is (= 9 (-> saved :result :chars)))
    (t/is (re-find #"'Tone of voice' saved" (-> saved :result :note)))
    (t/is (= {"design-md" "Calm."} (get-in gone [:plugin-data :penpot-vibes])))
    (t/is (re-find #"usable foundation name" (:problem (cad/set-foundation {:data data} {:name "!!" :doc "x"} nil))))
    (t/is (re-find #"keep it under 6000"
                   (:problem (cad/set-foundation {:data data} {:name "a" :doc (apply str (repeat 6001 "x"))} nil))))))

(t/deftest a-design-md-doc-needs-its-format-checked
  (let [doc "---\nname: Brand\n---\n# Overview"]
    (t/is (some? (:fallback (cad/set-foundation {:data data} {:name "vibes" :doc doc} nil))))
    (t/is (= "bad yaml" (:problem (cad/set-foundation {:data data} {:name "vibes" :doc doc}
                                                      {:check-design-doc (constantly "bad yaml")}))))
    (t/is (some? (:changes (cad/set-foundation {:data data} {:name "vibes" :doc doc}
                                               {:check-design-doc (constantly nil)}))))))
