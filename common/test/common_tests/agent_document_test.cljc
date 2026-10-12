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
   [app.common.types.shape :as cts]
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

;; --- generate_code: which shapes a call means

(def ^:private board-id (uuid/next))
(def ^:private child-id (uuid/next))
(def ^:private other-id (uuid/next))

(def ^:private objects
  {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame" :shapes [board-id other-id]})
   board-id  (cts/setup-shape {:id board-id :type :frame :name "Card" :parent-id uuid/zero :frame-id uuid/zero
                               :shapes [child-id]})
   child-id  (cts/setup-shape {:id child-id :type :rect :name "bg" :parent-id board-id :frame-id board-id})
   other-id  (cts/setup-shape {:id other-id :type :rect :name "loose" :parent-id uuid/zero :frame-id uuid/zero})})

(t/deftest code-plan-names-the-shapes-to-write-out
  (let [plan (cad/code-plan objects nil {:shapeIds [(str board-id)]})]
    (t/is (= "html" (:type plan)))
    (t/is (= [board-id] (mapv :id (:roots plan))))
    ;; with its children, unless told otherwise
    (t/is (= #{board-id child-id} (set (map :id (:shapes plan)))))
    (t/is (= [board-id]
             (mapv :id (:shapes (cad/code-plan objects nil {:shapeIds [(str board-id)] :includeChildren false})))))
    ;; nothing named: what the user has selected
    (t/is (= [other-id] (mapv :id (:roots (cad/code-plan objects [other-id] {})))))
    (t/is (= "svg" (:type (cad/code-plan objects nil {:shapeIds [(str other-id)] :type "svg"}))))))

(t/deftest code-plan-refusals
  (t/is (re-find #"nothing to inspect" (:problem (cad/code-plan objects nil {}))))
  (t/is (re-find #"\"pdf\" is not a markup type — use one of: html, svg"
                 (:problem (cad/code-plan objects nil {:shapeIds [(str board-id)] :type "pdf"}))))
  (t/is (some? (:problem (cad/code-plan objects nil {:shapeIds [(str (uuid/next))]}))))
  (t/is (= 2 (:shapes (cad/code-result "<div/>" ".a{}" [1 2])))))
