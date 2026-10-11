;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-read-test
  (:require
   [app.common.agent.read :as car]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private page-id (uuid/next))
(def ^:private card-id (uuid/next))
(def ^:private title-id (uuid/next))

(def ^:private objects
  {uuid/zero {:id uuid/zero :type :frame :name "Root Frame" :shapes [card-id]}
   card-id   {:id card-id :type :frame :name "Card" :x 0 :y 0 :width 200 :height 100
              :shapes [title-id]
              :fills [{:fill-color "#ffffff" :fill-opacity 1}]}
   ;; no radius, opacity or rotation attrs at all, as stored files often are
   title-id  {:id title-id :type :text :name "Card title" :x 8 :y 8 :width 100 :height 20}})

(def ^:private data
  {:pages [page-id]
   :pages-index {page-id {:id page-id :name "Home" :objects objects}}})

(def ^:private view
  {:file-id (uuid/next)
   :file-name "Shop"
   :data data
   :page-id page-id
   :libraries {}
   :selected [title-id]})

(t/deftest summarize-shape-tolerates-missing-attrs
  (t/is (= {:id (str title-id) :name "Card title" :type "text" :x 8 :y 8 :width 100 :height 20}
           (car/summarize-shape objects title-id {:look? true}))))

(t/deftest find-shapes-names-a-bad-query
  (t/is (re-find #"pass a name and/or a type" (car/find-shapes-problem {})))
  (t/is (re-find #"\"widget\" is not a shape type" (car/find-shapes-problem {:type "widget"})))
  (t/is (nil? (car/find-shapes-problem {:name "card"})))
  (t/is (nil? (car/find-shapes-problem {:type "Board"}))))

(t/deftest find-shapes-matches-by-name-and-type
  (t/is (= ["Card" "Card title"] (mapv :name (:matches (car/find-shapes objects {:name "card"})))))
  ;; the page root is a frame too and is listed with the boards: that is how
  ;; the workspace tool has always answered, and it is kept as is
  (t/is (= ["Card" "Root Frame"] (mapv :name (:matches (car/find-shapes objects {:type "board"})))))
  (t/is (= 0 (:found (car/find-shapes objects {:name "nothing"})))))

(t/deftest read-design-describes-the-page-in-view
  (let [out (car/read-design view nil)]
    (t/is (= "Shop" (:file out)))
    (t/is (= "Home" (:page out)))
    (t/is (= [{:id (str page-id) :name "Home" :current true}] (:pages out)))
    (t/is (= ["Card"] (mapv :name (:shapes out))))
    (t/is (= 1 (:childCount (first (:shapes out)))))
    (t/is (= ["Card title"] (mapv :name (:selection out))))
    (t/is (= {} (:tokens out)))
    ;; nothing a workspace would add, and nothing held back
    (t/is (not (contains? out :skills)))
    (t/is (not (contains? out :omitted)))))

(t/deftest read-design-merges-what-the-workspace-knows
  (let [out (car/read-design view {:openViolations 2 :hasDesignDoc true})]
    (t/is (= 2 (:openViolations out)))
    (t/is (true? (:hasDesignDoc out)))))

(t/deftest read-design-ignores-a-selection-that-is-not-on-the-page
  (t/is (= [] (:selection (car/read-design (assoc view :selected [(uuid/next)]) nil)))))

(t/deftest read-design-says-when-a-list-was-cut
  (let [ids     (repeatedly 70 uuid/next)
        objects (into {uuid/zero {:id uuid/zero :type :frame :shapes (vec ids)}}
                      (map (fn [id] [id {:id id :type :rect :name "r"}]))
                      ids)
        out     (car/read-design (assoc-in view [:data :pages-index page-id :objects] objects) nil)]
    (t/is (= 60 (count (:shapes out))))
    (t/is (re-find #"showing 60 of 70 top-level shapes" (get-in out [:omitted :shapes])))))

(t/deftest has-design-doc-reads-the-vibes-foundation
  (t/is (false? (car/has-design-doc? data)))
  (t/is (false? (car/has-design-doc? (assoc-in data [:plugin-data :penpot-vibes "design-md"] "  "))))
  (t/is (true? (car/has-design-doc? (assoc-in data [:plugin-data :penpot-vibes "design-md"] "# Vibes")))))

(t/deftest render-targets-resolve-names-ids-and-the-selection
  (t/is (= [{:name "Card" :id card-id}] (car/render-targets objects ["Card"] nil)))
  (t/is (= [{:name "Card" :id card-id}] (car/render-targets objects [(str card-id)] nil)))
  ;; nothing named: what the user has selected
  (t/is (= [card-id] (mapv :id (car/render-targets objects nil [card-id]))))
  (t/is (= [] (car/render-targets objects nil nil)))
  (t/is (= "no shape with that name or id" (:error (first (car/render-targets objects ["Nope"] nil)))))
  (t/is (re-find #"cannot be rendered — name the boards you want instead, e.g. Card"
                 (:error (first (car/render-targets objects [(str uuid/zero)] nil)))))
  (t/is (= car/max-render-boards (count (car/render-targets objects (repeat 9 "Card") nil))))
  (t/is (= [1 2 4] (mapv car/render-scale [0 nil 9]))))

(t/deftest audit-checks-only-the-active-rules
  (let [named  (assoc-in objects [card-id :name] "Rectangle 2")
        rules  (fn [enforced]
                 (mapv :rule (car/audit-violations named {:enforced enforced
                                                          :allowed #{}
                                                          :normalize identity})))]
    (t/is (= [] (rules #{})))
    (t/is (= ["layer-naming"] (rules #{"layer-naming"})))
    (t/is (= ["token-only-colors"] (rules #{"token-only-colors"})))
    (t/is (= 0 (:count (car/audit-result []))))))
