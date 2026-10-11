;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns exporter-tests.wasm-layout-test
  "Pins layout under Node: the headless render-wasm module, loaded with a
  page's layout properties, places shapes the way the workspace's engine does.

  Needs the built render-wasm artifact; without it the tests say so and pass,
  as the artifact is not part of a plain checkout."
  (:require
   ["node:fs" :as fs]
   [app.common.geom.shapes :as gsh]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.wasm :as wasm]
   [app.wasm.layout :as layout]
   [cljs.test :refer [deftest is async]]
   [promesa.core :as p]))

(def ^:private board-id (uuid/next))
(def ^:private a-id (uuid/next))
(def ^:private b-id (uuid/next))
(def ^:private c-id (uuid/next))

(defn- rect
  [id name x y]
  (cts/setup-shape {:id id :type :rect :name name
                    :x x :y y :width 50 :height 50
                    :parent-id board-id :frame-id board-id
                    :layout-item-h-sizing :fix
                    :layout-item-v-sizing :fix}))

;; A row with 20px of left padding and a 10px gap, holding three 50px squares
;; that are stored far from where the layout wants them.
(def ^:private objects
  {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                               :x 0 :y 0 :width 0.01 :height 0.01
                               :shapes [board-id]})
   board-id  (cts/setup-shape {:id board-id :type :frame :name "Row"
                               :x 100 :y 200 :width 400 :height 50
                               :parent-id uuid/zero :frame-id uuid/zero
                               :shapes [a-id b-id c-id]
                               :layout :flex
                               :layout-flex-dir :row
                               :layout-wrap-type :nowrap
                               :layout-align-items :start
                               :layout-align-content :stretch
                               :layout-justify-content :start
                               :layout-justify-items :start
                               :layout-gap {:row-gap 0 :column-gap 10}
                               :layout-gap-type :multiple
                               :layout-padding {:p1 0 :p2 0 :p3 0 :p4 20}
                               :layout-padding-type :multiple
                               :layout-item-h-sizing :fix
                               :layout-item-v-sizing :fix})
   a-id      (rect a-id "a" 900 900)
   b-id      (rect b-id "b" 700 10)
   c-id      (rect c-id "c" 5 600)})

(defn- placed
  "Where the children end up once `transforms` are applied → {name [x y]}."
  [objects transforms]
  (into {}
        (map (fn [id]
               (let [shape (cond-> (get objects id)
                             (contains? transforms id)
                             (gsh/apply-transform (get transforms id)))]
                 [(:name shape) [(js/Math.round (:x shape)) (js/Math.round (:y shape))]])))
        [a-id b-id c-id]))

(defn- with-engine
  [done f]
  (if-not (.existsSync fs "resources/wasm/render-wasm.wasm")
    (do (println "    render-wasm artifact not built, skipping")
        (is true)
        (done))
    (->> (wasm/init!)
         (p/fmap (fn [_] (f)))
         (p/fnly (fn [_ cause]
                   (is (nil? cause) (some-> cause ex-message))
                   (done))))))

(deftest reflow-places-children-by-the-layout
  (async done
    (with-engine done
      (fn []
        (layout/load-page! objects)
        (let [out (placed objects (layout/reflow [board-id]))]
          ;; padding, then 50px squares 10px apart, all on the board's top edge
          (is (= #{120 180 240} (set (map first (vals out)))))
          (is (= #{200} (set (map second (vals out)))))
          ;; a row flows in reverse vector order: the last child comes first
          (is (= [120 200] (get out "c")))
          (is (= [240 200] (get out "a"))))))))

(deftest a-displaced-child-is-put-back
  (async done
    (with-engine done
      (fn []
        (layout/load-page! objects)
        (let [settled (reduce-kv (fn [o id m] (update o id gsh/apply-transform m))
                                 objects
                                 (layout/reflow [board-id]))
              moved   (update settled b-id gsh/transform-shape (ctm/move-modifiers 37 11))]
          (layout/load-page! moved)
          (let [out (layout/reflow [board-id])]
            (is (= (placed settled {}) (placed moved out)))
            (is (= -37 (js/Math.round (:e (get out b-id)))))
            (is (= -11 (js/Math.round (:f (get out b-id)))))))))))

(deftest a-fixed-size-child-can-be-widened-and-its-siblings-make-room
  (async done
    (with-engine done
      (fn []
        (layout/load-page! objects)
        (let [settled (reduce-kv (fn [o id m] (update o id gsh/apply-transform m))
                                 objects
                                 (layout/reflow [board-id]))
              mods    (ctm/change-dimensions-modifiers (get settled b-id) :width 90)]
          (layout/load-page! settled)
          (let [out   (layout/propagate [[b-id {:transform (ctm/modifiers->transform mods) :kind :parent}]])
                after (reduce-kv (fn [o id m] (update o id gsh/apply-transform m)) settled out)]
            (is (= 90 (js/Math.round (:width (get after b-id)))))
            ;; c comes first and stays; b keeps its start; a moves 40px along
            (is (= [120 200] (get (placed after {}) "c")))
            (is (= [180 200] (get (placed after {}) "b")))
            (is (= [280 200] (get (placed after {}) "a")))))))))

;; --- Grid

(def ^:private grid-id (uuid/next))
(def ^:private g1 (uuid/next))
(def ^:private g2 (uuid/next))
(def ^:private g3 (uuid/next))

(defn- cell-rect
  [id name]
  (cts/setup-shape {:id id :type :rect :name name
                    :x 900 :y 900 :width 40 :height 40
                    :parent-id grid-id :frame-id grid-id
                    :layout-item-h-sizing :fix
                    :layout-item-v-sizing :fix}))

;; Two 100px columns and 60px rows; three 40px squares stored far away.
(def ^:private grid-objects
  (let [objects {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                                             :x 0 :y 0 :width 0.01 :height 0.01
                                             :shapes [grid-id]})
                 grid-id   (cts/setup-shape {:id grid-id :type :frame :name "Grid"
                                             :x 100 :y 200 :width 200 :height 120
                                             :parent-id uuid/zero :frame-id uuid/zero
                                             :shapes [g1 g2 g3]
                                             :layout :grid
                                             :layout-grid-dir :row
                                             :layout-grid-columns [{:type :fixed :value 100}
                                                                   {:type :fixed :value 100}]
                                             :layout-grid-rows [{:type :fixed :value 60}
                                                                {:type :fixed :value 60}]
                                             :layout-grid-cells {}
                                             :layout-align-items :start
                                             :layout-align-content :start
                                             :layout-justify-items :start
                                             :layout-justify-content :start
                                             :layout-gap {:row-gap 0 :column-gap 0}
                                             :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
                                             :layout-item-h-sizing :fix
                                             :layout-item-v-sizing :fix})
                 g1        (cell-rect g1 "g1")
                 g2        (cell-rect g2 "g2")
                 g3        (cell-rect g3 "g3")}]
    ;; seat the children in cells, as the workspace does when one is added
    (update objects grid-id #(-> (ctl/create-cells % [1 1 2 2])
                                 (ctl/assign-cells objects)))))

(deftest reflow-places-children-in-their-grid-cells
  (async done
    (with-engine done
      (fn []
        (layout/load-page! grid-objects)
        (let [out    (layout/reflow [grid-id])
              at     (fn [id]
                       (let [shape (cond-> (get grid-objects id)
                                     (contains? out id) (gsh/apply-transform (get out id)))]
                         [(js/Math.round (:x shape)) (js/Math.round (:y shape))]))
              places (set (map at [g1 g2 g3]))]
          ;; one square per cell, each at its cell's top-left corner
          (is (= 3 (count places)))
          (is (every? #{[100 200] [200 200] [100 260] [200 260]} places)))))))
