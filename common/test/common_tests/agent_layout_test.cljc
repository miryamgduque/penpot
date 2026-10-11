;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-layout-test
  "The layout tools with a stand-in engine: what they refuse, what they write
  on the shapes, and that the engine is asked to lay the result out."
  (:require
   [app.common.agent.layout :as cal]
   [app.common.files.changes :as cfc]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private page-id (uuid/next))
(def ^:private board-id (uuid/next))
(def ^:private a-id (uuid/next))
(def ^:private b-id (uuid/next))
(def ^:private loose-id (uuid/next))

(defn- rect
  [id name parent x]
  (cts/setup-shape {:id id :type :rect :name name :x x :y 0 :width 50 :height 50
                    :parent-id parent :frame-id parent}))

(def ^:private objects
  {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                               :x 0 :y 0 :width 0.01 :height 0.01
                               :shapes [board-id loose-id]})
   board-id  (cts/setup-shape {:id board-id :type :frame :name "Board"
                               :x 0 :y 0 :width 400 :height 100
                               :parent-id uuid/zero :frame-id uuid/zero
                               :shapes [a-id b-id]})
   a-id      (rect a-id "a" board-id 0)
   b-id      (rect b-id "b" board-id 100)
   loose-id  (rect loose-id "loose" uuid/zero 900)})

(def ^:private view {:page-id page-id :objects objects})

(defn- engine
  [calls]
  (fn [_objects entries]
    (swap! calls conj (mapv first entries))
    {}))

(defn- after
  [objects changes]
  (-> {:pages [page-id] :pages-index {page-id {:id page-id :objects objects}}}
      (cfc/process-changes changes)
      (get-in [:pages-index page-id :objects])))

(t/deftest set-layout-names-what-is-wrong
  (let [run #(cal/set-layout view % {:propagate (engine (atom []))})]
    (t/is (= "set_layout: shapeId is required" (:problem (run {}))))
    (t/is (re-find #"only boards can have a layout" (:problem (run {:shapeId (str a-id)}))))
    (t/is (re-find #"has no layout to remove" (:problem (run {:shapeId (str board-id) :remove true}))))
    (t/is (re-find #"dir \"diagonal\" is not valid" (:problem (run {:shapeId (str board-id) :dir "diagonal"}))))))

(t/deftest set-layout-makes-a-flex-board-and-asks-for-a-reflow
  (let [calls (atom [])
        out   (cal/set-layout view {:shapeId (str board-id) :dir "column" :rowGap 12
                                    :padding {:top 8 :left 16}}
                              {:propagate (engine calls)})
        board (get (after objects (:changes out)) board-id)]
    (t/is (= [[board-id]] @calls))
    (t/is (ctl/flex-layout? board))
    (t/is (= :column (:layout-flex-dir board)))
    (t/is (= 12 (get-in board [:layout-gap :row-gap])))
    (t/is (= 8 (get-in board [:layout-padding :p1])))
    (t/is (= 16 (get-in board [:layout-padding :p4])))
    (t/is (re-find #"^flex layout applied" (get-in out [:result :note])))))

(t/deftest set-layout-with-columns-seats-children-in-reading-order
  (let [out   (cal/set-layout view {:shapeId (str board-id) :type "grid" :columns 2}
                              {:propagate (engine (atom []))})
        board (get (after objects (:changes out)) board-id)
        seat  (fn [id] (some (fn [cell] (when (= [id] (:shapes cell)) [(:row cell) (:column cell)]))
                             (vals (:layout-grid-cells board))))]
    (t/is (ctl/grid-layout? board))
    (t/is (= 2 (count (:layout-grid-columns board))))
    (t/is (= #{[1 1] [1 2]} (set (map seat [a-id b-id]))))
    (t/is (re-find #"^grid layout applied" (get-in out [:result :note])))))

(t/deftest set-layout-remove-strips-the-layout
  (let [laid  (after objects (:changes (cal/set-layout view {:shapeId (str board-id) :type "flex"}
                                                       {:propagate (engine (atom []))})))
        out   (cal/set-layout {:page-id page-id :objects laid} {:shapeId (str board-id) :remove true}
                              {:propagate (engine (atom []))})]
    (t/is (nil? (:layout (get (after laid (:changes out)) board-id))))
    (t/is (= "layout removed — children keep their positions" (get-in out [:result :note])))))

(t/deftest set-layout-child-needs-a-laid-out-parent
  (let [run #(cal/set-layout-child view % {:propagate (engine (atom []))})]
    (t/is (re-find #"has no layout, so these settings would be stored and do nothing"
                   (:problem (run {:shapeId (str a-id) :horizontalSizing "fill"}))))
    (t/is (re-find #"is not inside a board"
                   (:problem (run {:shapeId (str loose-id) :horizontalSizing "fill"}))))))

(t/deftest set-layout-child-writes-the-item-attrs-and-reflows
  (let [laid  (after objects (:changes (cal/set-layout view {:shapeId (str board-id) :type "flex"}
                                                       {:propagate (engine (atom []))})))
        calls (atom [])
        out   (cal/set-layout-child {:page-id page-id :objects laid}
                                    {:shapeId (str a-id) :horizontalSizing "fill"
                                     :margin {:top 4} :alignSelf "center"}
                                    {:propagate (engine calls)})
        shape (get (after laid (:changes out)) a-id)]
    (t/is (= [[a-id]] @calls))
    (t/is (= :fill (:layout-item-h-sizing shape)))
    (t/is (= :center (:layout-item-align-self shape)))
    (t/is (= 4 (get-in shape [:layout-item-margin :m1])))))
