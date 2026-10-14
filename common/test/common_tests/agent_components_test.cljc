;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-components-test
  "The component and variant tools, run one after another on the same file the
  way an agent would: make components, combine them into a set, place and
  switch instances."
  (:require
   [app.common.agent.components :as cac]
   [app.common.agent.structure :as cas]
   [app.common.files.changes :as cfc]
   [app.common.files.variant :as cfv]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private file-id (uuid/next))
(def ^:private page-id (uuid/next))
(def ^:private compact-id (uuid/next))
(def ^:private large-id (uuid/next))
(def ^:private other-id (uuid/next))

(defn- board
  [id name x]
  (cts/setup-shape {:id id :type :frame :name name :x x :y 0 :width 100 :height 40
                    :parent-id uuid/zero :frame-id uuid/zero :shapes []}))

(def ^:private data
  (-> (ctf/make-file-data file-id page-id)
      (assoc-in [:pages-index page-id :objects]
                {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                                             :x 0 :y 0 :width 0.01 :height 0.01
                                             :shapes [compact-id large-id other-id]})
                 compact-id (board compact-id "Badge / Compact" 0)
                 large-id   (board large-id "Badge / Large" 200)
                 other-id   (board other-id "Chip" 400)})))

(defn- view
  [data]
  {:file-id file-id
   :page-id page-id
   :data data
   :objects (get-in data [:pages-index page-id :objects])
   :libraries {file-id {:id file-id :data data}}})

(def ^:private engine
  {:propagate (fn [_ _] {})})

(defn- run
  "Runs `tool` on `data` → `[data' answer]`."
  [data tool input]
  (let [out (tool (view data) input engine)]
    [(if (seq (:changes out)) (cfc/process-changes data (:changes out)) data) out]))

(defn- objects
  [data]
  (get-in data [:pages-index page-id :objects]))

(defn- with-components
  []
  (reduce (fn [data id] (first (run data cas/create-component {:shapeIds [(str id)]})))
          data
          [compact-id large-id other-id]))

(t/deftest a-variant-set-from-two-components
  (let [data       (with-components)
        [data out] (run data cac/create-variant {:shapeIds [(str compact-id) (str large-id)]})
        vid        (uuid/parse (get-in out [:result :variantId]))
        container  (get (objects data) vid)
        members    (cfv/find-variant-components data (objects data) vid)]
    (t/is (nil? (:problem out)))
    (t/is (= 2 (get-in out [:result :members])))
    (t/is (nil? (get-in out [:result :namingHint])))
    ;; the shared path names the set; what is left names the property values
    (t/is (ctc/is-variant-container? container))
    (t/is (= "Badge" (:name container)))
    (t/is (= #{compact-id large-id} (set (:shapes container))))
    (t/is (= #{"Compact" "Large"} (set (map #(get-in % [:variant-properties 0 :value]) members))))

    (t/testing "an axis is renamed for the whole set, a value for one member"
      (let [axis       (get-in (first members) [:variant-properties 0 :name])
            large      (first (filter #(= large-id (:main-instance-id %)) members))
            [data out] (run data cac/set-variant-property
                            {:variantId (str vid) :property axis :rename "Size"
                             :componentId (str (:id large)) :value "XL"})
            members    (cfv/find-variant-components data (objects data) vid)]
        (t/is (= "Size" (get-in out [:result :axis])))
        (t/is (= #{"Size"} (set (map #(get-in % [:variant-properties 0 :name]) members))))
        (t/is (= #{"Compact" "XL"} (set (map #(get-in % [:variant-properties 0 :value]) members))))))

    (t/testing "a member is added"
      (let [[data out] (run data cac/add-variant {:shapeId (str vid)})]
        (t/is (string? (get-in out [:result :shapeId])))
        (t/is (= 3 (count (:shapes (get (objects data) vid)))))))

    (t/testing "an instance is placed and switched to the other member"
      (let [compact    (first (filter #(= compact-id (:main-instance-id %)) members))
            axis       (get-in compact [:variant-properties 0 :name])
            [data out] (run data cac/create-instance {:componentId (str (:id compact)) :x 0 :y 500})
            copy-id    (uuid/parse (get-in out [:result :shapeId]))
            [data out] (run data cac/switch-variant {:shapeId (str copy-id) :property axis :value "Large"})
            large      (first (filter #(= large-id (:main-instance-id %)) members))
            copies     (->> (vals (objects data))
                            (filter #(and (ctc/instance-head? %) (not (ctc/main-instance? %)))))]
        (t/is (nil? (:problem out)))
        (t/is (= [(:id large)] (mapv :component-id copies)))
        (t/is (re-find #"no axis named \"Colour\""
                       (:problem (second (run data cac/switch-variant
                                              {:shapeId (str (:id (first copies))) :property "Colour" :value "x"})))))))))

(t/deftest variant-refusals-name-the-fix
  (let [data (with-components)]
    (t/is (re-find #"needs at least 2 main components"
                   (:problem (second (run data cac/create-variant {:shapeIds [(str compact-id)]})))))
    (t/testing "members with no shared path still combine, with a hint"
      (let [out (second (run data cac/create-variant {:shapeIds [(str compact-id) (str other-id)]}))]
        (t/is (nil? (:problem out)))
        (t/is (re-find #"share no common path" (get-in out [:result :namingHint])))))))

(t/deftest instances-of-a-plain-component
  (let [data       (with-components)
        chip       (first (filter #(= other-id (:main-instance-id %)) (ctkl/components-seq data)))
        badge      (first (filter #(= compact-id (:main-instance-id %)) (ctkl/components-seq data)))
        [data out] (run data cac/create-instance {:componentId (str (:id chip)) :x 0 :y 300})
        copy-id    (uuid/parse (get-in out [:result :shapeId]))]
    (t/is (= (:id chip) (:component-id (get (objects data) copy-id))))
    (t/is (re-find #"x and y are required" (:problem (second (run data cac/create-instance {:componentId (str (:id chip))})))))
    (t/is (re-find #"no component" (:problem (second (run data cac/create-instance {:componentId (str (uuid/next)) :x 0 :y 0})))))

    (t/testing "a copy is swapped for another component"
      (let [[data out] (run data cac/swap-component {:shapeId (str copy-id) :componentId (str (:id badge))})
            copies     (->> (vals (objects data))
                            (filter #(and (ctc/instance-head? %) (not (ctc/main-instance? %)))))]
        (t/is (nil? (:problem out)))
        (t/is (= [(:id badge)] (mapv :component-id copies)))))

    (t/testing "a main is not a copy"
      (t/is (re-find #"is a component MAIN, not a copy"
                     (:problem (second (run data cac/reset-overrides {:shapeId (str other-id)})))))
      (t/is (re-find #"is a component's MAIN"
                     (:problem (second (run data cac/detach-instance {:shapeId (str other-id)}))))))

    (t/testing "a copy is reset, then detached"
      (let [[data out] (run data cac/reset-overrides {:shapeId (str copy-id)})
            _          (t/is (nil? (:problem out)))
            [data out] (run data cac/detach-instance {:shapeId (str copy-id)})]
        (t/is (nil? (:problem out)))
        (t/is (nil? (:component-id (get (objects data) copy-id))))))))
