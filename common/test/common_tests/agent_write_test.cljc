;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-write-test
  "The write tools with a stand-in engine: what they refuse, what they hand
  the engine, and what they commit. Where shapes really land is the engine's
  business and is pinned where the engine runs."
  (:require
   [app.common.agent.tools :as cat]
   [app.common.agent.write :as caw]
   [app.common.files.changes :as cfc]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private page-id (uuid/next))
(def ^:private board-id (uuid/next))
(def ^:private child-id (uuid/next))
(def ^:private text-id (uuid/next))
(def ^:private grid-id (uuid/next))

(def ^:private objects
  {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                               :x 0 :y 0 :width 0.01 :height 0.01
                               :shapes [board-id text-id grid-id]})
   board-id  (cts/setup-shape {:id board-id :type :frame :name "Row"
                               :x 100 :y 200 :width 400 :height 50
                               :parent-id uuid/zero :frame-id uuid/zero
                               :shapes [child-id]
                               :layout :flex :layout-flex-dir :row})
   child-id  (cts/setup-shape {:id child-id :type :rect :name "a"
                               :x 120 :y 200 :width 50 :height 50
                               :parent-id board-id :frame-id board-id})
   text-id   (cts/setup-shape {:id text-id :type :text :name "title"
                               :x 0 :y 0 :width 80 :height 20
                               :parent-id uuid/zero :frame-id uuid/zero})
   grid-id   (cts/setup-shape {:id grid-id :type :frame :name "Grid"
                               :x 0 :y 400 :width 100 :height 100
                               :parent-id uuid/zero :frame-id uuid/zero
                               :layout :grid
                               :layout-grid-dir :row
                               :layout-grid-rows []
                               :layout-grid-columns []
                               :layout-grid-cells {}})})

(def ^:private view {:page-id page-id :objects objects})

(defn- engine
  "A stand-in engine: records what it was asked and moves every entry's shape
  by (7, 0)."
  [calls]
  (fn [_objects entries]
    (swap! calls conj (mapv (fn [[id data]] [id (:kind data)]) entries))
    (into {} (map (fn [[id _]] [id (gmt/translate-matrix (gpt/point 7 0))])) entries)))

(defn- apply-changes
  [changes]
  (-> {:pages [page-id] :pages-index {page-id {:id page-id :objects objects}}}
      (cfc/process-changes changes)
      (get-in [:pages-index page-id :objects])))

(def ^:private no-guard {:enforced? false})

(t/deftest create-shape-on-the-page-needs-no-layout
  (let [calls (atom [])
        out   (caw/create-shape view {:type "rect" :x 10 :y 20 :width 30 :height 40 :name "probe"}
                                {:guard no-guard :propagate (engine calls)})
        after (apply-changes (:changes out))
        id    (uuid/parse (get-in out [:result :id]))]
    (t/is (= [] @calls))
    (t/is (= "probe" (:name (get after id))))
    (t/is (= :rect (:type (get after id))))
    (t/is (nil? (get-in out [:result :parentId])))
    (t/is (= "created — verify geometry with read_design" (get-in out [:result :note])))))

(t/deftest create-shape-in-a-flex-board-flows-into-it
  (let [calls (atom [])
        out   (caw/create-shape view {:type "rect" :x 0 :y 0 :width 50 :height 50 :parentId (str board-id)}
                                {:guard no-guard :propagate (engine calls)})
        after (apply-changes (:changes out))
        id    (uuid/parse (get-in out [:result :id]))]
    ;; the engine is asked to lay the parent out again, once
    (t/is (= [[[board-id :parent]]] @calls))
    (t/is (= (str board-id) (get-in out [:result :parentId])))
    ;; first in the vector is last in a row's flow: creation order is reading order
    (t/is (= [id child-id] (:shapes (get after board-id))))
    (t/is (= board-id (:frame-id (get after id))))
    ;; what the engine answered is what gets committed
    (t/is (== 107 (:x (get after board-id))))))

(t/deftest create-shape-is-refused-for-a-raw-color-under-the-rule
  (let [guard {:enforced? true :allowed #{"#6366f1"}}
        out   (caw/create-shape view {:type "rect" :x 0 :y 0 :width 1 :height 1 :fill "#ff0000"}
                                {:guard guard :propagate (engine (atom []))})]
    (t/is (= "token-only-colors" (:rule out)))
    (t/is (re-find #"raw color #ff0000 is not a design token" (:problem out)))
    (t/is (re-find #"allowed: #6366f1" (:problem out)))
    (t/is (nil? (:changes out))))
  (t/testing "a token's color passes, however it is written"
    (let [out (caw/create-shape view {:type "rect" :x 0 :y 0 :width 1 :height 1 :fill "#6366F1"}
                                {:guard {:enforced? true :allowed #{"#6366f1"}}
                                 :propagate (engine (atom []))})]
      (t/is (some? (:changes out))))))

(t/deftest create-shape-refuses-a-fill-that-is-not-a-color
  ;; a token's name written as a fill made a shape the engine could not take
  (let [run #(caw/create-shape view (merge {:type "rect" :x 0 :y 0 :width 10 :height 10} %)
                               {:guard no-guard :propagate (engine (atom []))})]
    (t/is (re-find #"\"color.bg.default\" is not a color" (:problem (run {:fill "color.bg.default"}))))
    (t/is (re-find #"\"brand\" is not a color" (:problem (run {:fill {:type "solid" :color "brand"}}))))
    (t/is (nil? (:problem (run {:fill "#6366f1"}))))
    (t/is (nil? (:problem (run {:fill {:type "solid" :color "#6366f1"}}))))))

(t/deftest create-shape-in-a-grid-gets-a-cell-and-flows-into-it
  (let [calls (atom [])
        out   (caw/create-shape view {:type "rect" :x 0 :y 0 :width 10 :height 10 :parentId (str grid-id)}
                                {:guard no-guard :propagate (engine calls)})
        after (apply-changes (:changes out))
        id    (uuid/parse (get-in out [:result :id]))
        cells (vals (:layout-grid-cells (get after grid-id)))]
    (t/is (= [[[grid-id :parent]]] @calls))
    ;; the grid seated it in a cell before the engine was asked
    (t/is (some #(= [id] (:shapes %)) cells))))

(t/deftest modify-shape-names-what-is-wrong
  (let [run #(caw/modify-shape view % {:guard no-guard :propagate (engine (atom []))})]
    (t/is (= "modify_shape: missing or invalid shapeId" (:problem (run {:shapeId "nope"}))))
    (t/is (re-find #"no shape with id" (:problem (run {:shapeId (str (uuid/next))}))))
    (t/is (re-find #"strokeStyle \"wavy\" is not valid" (:problem (run {:shapeId (str child-id) :strokeStyle "wavy"}))))
    (t/is (re-find #"\"plaid\" is not a fill type" (:problem (run {:shapeId (str child-id) :fill {:type "plaid"}}))))))

(t/deftest modify-shape-writes-attributes-without-the-engine
  (let [calls (atom [])
        out   (caw/modify-shape view {:shapeId (str child-id) :name "renamed" :radius 8 :opacity 0.5
                                      :fill "#112233" :stroke "#000000" :strokeWidth 2 :hidden true}
                                {:guard no-guard :propagate (engine calls)})
        shape (get (apply-changes (:changes out)) child-id)]
    (t/is (= [] @calls))
    (t/is (= "renamed" (:name shape)))
    (t/is (= [8 8 8 8] (mapv #(get shape %) [:r1 :r2 :r3 :r4])))
    (t/is (= 0.5 (:opacity shape)))
    (t/is (= "#112233" (:fill-color (first (:fills shape)))))
    (t/is (= 2 (:stroke-width (first (:strokes shape)))))
    (t/is (true? (:hidden shape)))
    (t/is (= {:id (str child-id) :note "modified — verify with read_design"} (:result out)))))

(t/deftest modify-shape-asks-the-engine-for-each-geometry-write
  (let [calls (atom [])
        out   (caw/modify-shape view {:shapeId (str child-id) :x 30 :width 90}
                                {:guard no-guard :propagate (engine calls)})]
    ;; one engine call per geometry write, each seeing the result of the last
    (t/is (= [[[child-id :child]] [[child-id :child]]] @calls))
    (t/is (some? (get-in out [:result :settled])))
    ;; the stand-in engine did not put it where it was asked: that is reported
    (t/is (re-find #"SETTLED at a different geometry" (get-in out [:result :note])))))

(t/deftest modify-shape-respects-a-lock
  (let [locked (assoc-in view [:objects child-id :blocked] true)
        run    #(caw/modify-shape locked % {:guard no-guard :propagate (engine (atom []))})]
    (t/is (re-find #"is locked" (:problem (run {:shapeId (str child-id) :name "x"}))))
    (t/is (some? (:changes (run {:shapeId (str child-id) :locked false}))))))

(t/deftest modify-shape-leaves-what-it-cannot-do-yet-to-the-workspace
  (let [run #(caw/modify-shape view % {:guard no-guard :propagate (engine (atom []))})]
    (t/is (string? (:fallback (run {:shapeId (str text-id) :width 120}))))
    ;; but a text can still be renamed or moved
    (t/is (some? (:changes (run {:shapeId (str text-id) :name "heading"}))))))

(t/deftest modify-shape-rotates-to-an-absolute-angle
  (let [calls (atom [])
        out   (caw/modify-shape view {:shapeId (str child-id) :rotation 30}
                                {:guard no-guard :propagate (engine calls)})
        shape (get (apply-changes (:changes out)) child-id)]
    (t/is (= [[[child-id :child]]] @calls))
    ;; the angle is written on the shape, not only its geometry
    (t/is (== 30 (:rotation shape))))
  (t/testing "from where it already is"
    (let [turned (assoc-in view [:objects child-id :rotation] 20)
          seen   (atom nil)
          out    (caw/modify-shape turned {:shapeId (str child-id) :rotation 30}
                                   {:guard no-guard
                                    :propagate (fn [_ entries] (reset! seen entries) {})})]
      (t/is (some? (:changes out)))
      (t/is (= 1 (count @seen))))))

(t/deftest modify-shape-flips-through-the-engine
  (let [calls (atom [])
        out   (caw/modify-shape view {:shapeId (str child-id) :flipH true :flipV true}
                                {:guard no-guard :propagate (engine calls)})]
    (t/is (= 2 (count @calls)))
    (t/is (some? (:changes out)))))

;; --- The design rules, checked before any tool runs

(t/deftest every-call-that-asks-for-a-color-is-held-to-the-rule
  (let [guard   {:enforced? true :allowed #{"#0f766e"}}
        refused (fn [tool input] (cat/rule-problem tool input guard))]
    (t/is (= "token-only-colors" (:rule (refused "create_shape" {:type "rect" :fill "#ff0000"}))))
    (t/is (re-find #"raw color #ff0000 is not a design token" (:problem (refused "modify_shape" {:stroke "#ff0000"}))))
    (t/is (some? (refused "update_shapes" {:updates [{:shapeId "x" :fill "#0f766e"} {:shapeId "y" :shadow {:color "#ff0000"}}]})))
    (t/is (some? (refused "build_tree" {:tree {:type "board" :children [{:type "rect" :fill "#ff0000"}]}})))
    ;; allowed colors, a text's own color, and tools that carry no color pass
    (t/is (nil? (refused "create_shape" {:type "rect" :fill "#0F766E"})))
    (t/is (nil? (refused "build_tree" {:tree {:type "board" :children [{:type "text" :text "x" :fill "#ff0000"}]}})))
    (t/is (nil? (refused "delete_shape" {:shapeIds ["x"]})))
    ;; not enforced, nothing is refused
    (t/is (nil? (cat/rule-problem "create_shape" {:fill "#ff0000"} {:enforced? false})))))

(t/deftest a-refused-call-never-reaches-its-tool
  (let [out (cat/run "create_shape" {} {:type "rect" :fill "#ff0000"}
                     {:guard {:enforced? true :allowed #{}}})]
    (t/is (= "token-only-colors" (:rule out)))
    (t/is (nil? (:changes out)))))
