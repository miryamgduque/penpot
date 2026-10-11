;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-structure-test
  "The structure tools with a stand-in engine: what they refuse, what they do
  to the tree, and which boards they ask the engine to lay out again."
  (:require
   [app.common.agent.structure :as cas]
   [app.common.files.changes :as cfc]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private file-id (uuid/next))
(def ^:private page-id (uuid/next))
(def ^:private row-id (uuid/next))
(def ^:private a-id (uuid/next))
(def ^:private b-id (uuid/next))
(def ^:private box-id (uuid/next))
(def ^:private c-id (uuid/next))

(defn- rect
  [id name parent x]
  (cts/setup-shape {:id id :type :rect :name name :x x :y 0 :width 50 :height 50
                    :parent-id parent :frame-id parent}))

(def ^:private objects
  {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                               :x 0 :y 0 :width 0.01 :height 0.01
                               :shapes [row-id box-id]})
   row-id    (cts/setup-shape {:id row-id :type :frame :name "Row"
                               :x 0 :y 0 :width 400 :height 100
                               :parent-id uuid/zero :frame-id uuid/zero
                               :shapes [a-id b-id]
                               :layout :flex :layout-flex-dir :row})
   a-id      (rect a-id "a" row-id 0)
   b-id      (rect b-id "b" row-id 100)
   box-id    (cts/setup-shape {:id box-id :type :frame :name "Box"
                               :x 500 :y 0 :width 200 :height 200
                               :parent-id uuid/zero :frame-id uuid/zero
                               :shapes [c-id]})
   c-id      (rect c-id "c" box-id 510)})

(def ^:private data
  (-> (ctf/make-file-data file-id page-id)
      (assoc-in [:pages-index page-id :objects] objects)))

(def ^:private view
  {:file-id file-id
   :page-id page-id
   :data data
   :objects objects
   :libraries {file-id {:id file-id :data data}}})

(defn- engine
  [calls]
  (fn [_objects entries]
    (swap! calls into (map first) entries)
    {}))

(defn- after
  [changes]
  (get-in (cfc/process-changes data changes) [:pages-index page-id :objects]))

(defn- run
  ([tool input] (run tool input (atom [])))
  ([tool input calls] (tool view input {:guard {:enforced? false} :propagate (engine calls)})))

(t/deftest ids-are-checked-before-anything-is-touched
  (t/is (re-find #"not valid shape ids: \"nope\"" (:problem (run cas/delete-shape {:shapeIds [(str a-id) "nope"]}))))
  (t/is (re-find #"shapeIds is required" (:problem (run cas/group-shapes {:shapeIds []}))))
  (t/is (re-find #"no shape on this page with id" (:problem (run cas/duplicate-shape {:shapeIds [(str (uuid/next))]})))))

(t/deftest delete-shape-removes-the-subtree-and-reflows-the-parent
  (let [calls (atom [])
        out   (run cas/delete-shape {:shapeIds [(str a-id)]} calls)
        tree  (after (:changes out))]
    (t/is (nil? (get tree a-id)))
    (t/is (= [b-id] (:shapes (get tree row-id))))
    (t/is (= [row-id] @calls))
    (t/is (= 1 (get-in out [:result :deleted]))))
  (t/testing "deleting a board says what went with it"
    (let [out (run cas/delete-shape {:shapeIds [(str box-id)]})]
      (t/is (= 1 (get-in out [:result :childrenDeleted])))
      (t/is (re-find #"INCLUDING 1 nested child" (get-in out [:result :note])))
      (t/is (nil? (get (after (:changes out)) c-id))))))

(t/deftest duplicate-shape-copies-and-names-the-copy
  (let [out  (run cas/duplicate-shape {:shapeIds [(str box-id)]})
        tree (after (:changes out))
        copy (get tree (uuid/parse (get-in out [:result :shapeId])))]
    (t/is (= :frame (:type copy)))
    ;; a board lands to the right of its original
    (t/is (== (+ 500 200 50) (:x copy)))
    (t/is (= 1 (count (:shapes copy))))
    (t/is (= 1 (get-in out [:result :duplicated])))))

(t/deftest nest-shape-moves-into-a-board-and-reflows-both-parents
  (let [calls (atom [])
        out   (run cas/nest-shape {:shapeId (str c-id) :parentId (str row-id)} calls)
        tree  (after (:changes out))]
    (t/is (= row-id (:parent-id (get tree c-id))))
    (t/is (= [] (:shapes (get tree box-id))))
    ;; end of the flow: a row lays out in reverse vector order
    (t/is (= c-id (first (:shapes (get tree row-id)))))
    (t/is (= #{row-id box-id c-id} (set @calls)))
    (t/is (re-find #"the parent lays out its children" (get-in out [:result :note]))))
  (t/testing "a flow position is honoured"
    (let [tree (after (:changes (run cas/nest-shape {:shapeId (str c-id) :parentId (str row-id) :index 0})))]
      (t/is (= c-id (last (:shapes (get tree row-id)))))))
  (t/testing "a cycle is refused by name"
    (t/is (re-find #"cannot be nested into itself"
                   (:problem (run cas/nest-shape {:shapeId (str row-id) :parentId (str row-id)}))))))

(t/deftest group-and-ungroup-round-trip
  (let [out   (run cas/group-shapes {:shapeIds [(str a-id) (str b-id)]})
        gid   (uuid/parse (get-in out [:result :groupId]))
        tree  (after (:changes out))]
    (t/is (= :group (:type (get tree gid))))
    (t/is (= #{a-id b-id} (set (:shapes (get tree gid)))))
    (t/is (= [gid] (:shapes (get tree row-id))))
    (let [view'  (-> view
                     (assoc :objects tree)
                     (assoc-in [:data :pages-index page-id :objects] tree))
          out'   (cas/ungroup-shapes view' {:shapeIds [(str gid)]}
                                     {:guard {:enforced? false} :propagate (engine (atom []))})
          tree'  (get-in (cfc/process-changes (:data view') (:changes out')) [:pages-index page-id :objects])]
      (t/is (nil? (get tree' gid)))
      (t/is (= #{a-id b-id} (set (:shapes (get tree' row-id))))))))

(t/deftest ungroup-refuses-what-is-not-a-container
  (t/is (re-find #"only a group, a board or a boolean can be ungrouped"
                 (:problem (run cas/ungroup-shapes {:shapeIds [(str a-id)]})))))

(t/deftest mask-and-unmask
  (let [out  (run cas/mask-shapes {:shapeIds [(str a-id) (str b-id)]})
        tree (after (:changes out))
        mask (first (filter :masked-group (vals tree)))]
    (t/is (some? mask))
    (t/is (= #{a-id b-id} (set (:shapes mask))))
    (t/is (re-find #"only a shape created by mask_shapes can be unmasked"
                   (:problem (run cas/unmask-shapes {:shapeIds [(str a-id)]}))))))

(t/deftest create-line-makes-a-two-node-path
  (let [out  (run cas/create-line {:x1 0 :y1 0 :x2 100 :y2 50 :stroke "#ff0000" :strokeWidth 2 :name "rule"})
        line (get (after (:changes out)) (uuid/parse (get-in out [:result :id])))]
    (t/is (= :path (:type line)))
    (t/is (= "rule" (:name line)))
    (t/is (= 2 (:stroke-width (first (:strokes line)))))
    (t/is (== 100 (:width (:selrect line)))))
  (t/is (= "create_line: start and end are the same point — a line needs length"
           (:problem (run cas/create-line {:x1 1 :y1 1 :x2 1 :y2 1})))))

(t/deftest update-shapes-is-all-or-nothing
  (let [out (run cas/update-shapes {:updates [{:shapeId (str a-id) :name "first"}
                                              {:shapeId (str b-id) :name "second"}]})
        tree (after (:changes out))]
    (t/is (= ["first" "second"] (mapv #(:name (get tree %)) [a-id b-id])))
    (t/is (= 2 (get-in out [:result :updated]))))
  (let [out (run cas/update-shapes {:updates [{:shapeId (str a-id) :name "first"}
                                              {:shapeId "nope" :name "second"}]})]
    (t/is (re-find #"^updates\[1\]: update_shapes: missing or invalid shapeId" (:problem out)))
    (t/is (nil? (:changes out)))))
