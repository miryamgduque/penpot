;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-undo-test
  "Every tool answers, with its changes, the changes that take them back.
  Applied after the tool's own, they have to leave the file as it was: that
  is what reverting an agent turn rests on."
  (:require
   [app.common.agent.token-status :as tst]
   [app.common.agent.tools :as cat]
   [app.common.agent.write :as caw]
   [app.common.files.changes :as cfc]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private file-id (uuid/next))
(def ^:private page-id (uuid/next))
(def ^:private row-id (uuid/next))
(def ^:private a-id (uuid/next))
(def ^:private b-id (uuid/next))
(def ^:private text-id (uuid/next))
(def ^:private box-id (uuid/next))

(defn- rect
  [id name parent x]
  (cts/setup-shape {:id id :type :rect :name name :x x :y 0 :width 50 :height 50
                    :parent-id parent :frame-id parent}))

(def ^:private objects
  {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                               :x 0 :y 0 :width 0.01 :height 0.01
                               :shapes [row-id box-id text-id]})
   row-id    (cts/setup-shape {:id row-id :type :frame :name "Row"
                               :x 0 :y 0 :width 400 :height 100
                               :parent-id uuid/zero :frame-id uuid/zero
                               :shapes [a-id b-id]
                               :layout :flex :layout-flex-dir :row
                               :layout-gap {:row-gap 0 :column-gap 0}
                               :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}})
   a-id      (rect a-id "a" row-id 0)
   b-id      (rect b-id "b" row-id 100)
   box-id    (cts/setup-shape {:id box-id :type :frame :name "Box"
                               :x 500 :y 0 :width 200 :height 200
                               :parent-id uuid/zero :frame-id uuid/zero
                               :shapes []})
   text-id   (-> (cts/setup-shape {:id text-id :type :text :name "label"
                                   :x 0 :y 300 :width 40 :height 20 :grow-type :auto-width
                                   :parent-id uuid/zero :frame-id uuid/zero})
                 (update :content txt/change-text "hello" {}))})

(def ^:private data
  (-> (ctf/make-file-data file-id page-id)
      (assoc-in [:pages-index page-id :objects] objects)))

(defn- view
  [data]
  {:file-id file-id
   :page-id page-id
   :data data
   :objects (get-in data [:pages-index page-id :objects])
   :libraries {file-id {:id file-id :data data}}
   :fonts {}})

(def ^:private options
  {:guard {:enforced? false}
   :propagate (fn [_ _] {})
   :measure-text (fn [_ _] {:width 80 :height 24})})

(defn- run
  [data tool input]
  (cat/run tool (view data) input options))

(defn- tokens-of
  "What a tokens library holds; a library that holds nothing is as good as
  none."
  [data]
  (when-let [lib (:tokens-lib data)]
    (let [held {:tokens (update-vals (ctob/get-all-tokens-map lib) #(select-keys % [:name :type :value]))
                :sets (set (map ctob/get-name (ctob/get-sets lib)))
                :themes (disj (set (map ctob/get-name (ctob/get-themes lib))) ctob/hidden-theme-name)
                :active (set (keys (tst/active-tokens data)))}]
      (when (some seq (vals held))
        held))))

(defn- comparable
  "What of a file has to come back: its pages and their shapes, its tokens,
  components and the data plugins keep in it. An empty container left behind
  where there was none is not a difference."
  [data]
  {:pages (:pages data)
   :pages-index (update-vals (:pages-index data)
                             ;; a shape that was touched and put back keeps
                             ;; nothing of the visit
                             #(update % :objects update-vals (fn [shape] (dissoc shape :touched))))
   :components (not-empty (:components data))
   :tokens (tokens-of data)
   :plugin-data (not-empty (into {} (remove (comp empty? val)) (:plugin-data data)))})

(defn- reverts?
  "Whether undoing `tool` leaves `data` as it was. Also false when the tool
  changed nothing: a test that proves nothing should not pass."
  [data tool input]
  (let [answer  (run data tool input)
        changes (:changes answer)
        after   (cfc/process-changes data changes false)]
    (and (seq changes)
         (not= (comparable data) (comparable after))
         (some? (caw/undo-of changes))
         (= (comparable data)
            (comparable (cfc/process-changes after (caw/undo-of changes) false))))))

(t/deftest each-tool-can-be-taken-back
  (t/are [tool input] (reverts? data tool input)
    "create_shape"     {:type "rect" :x 10 :y 10 :width 20 :height 20 :name "new"}
    "create_shape"     {:type "rect" :width 20 :height 20 :parentId (str row-id)}
    "modify_shape"     {:shapeId (str a-id) :width 80 :name "wide" :fill "#112233" :radius 4}
    "update_shapes"    {:updates [{:shapeId (str a-id) :name "a2"} {:shapeId (str b-id) :hidden true}]}
    "set_layout"       {:shapeId (str box-id) :type "flex" :dir "column" :rowGap 8}
    "set_layout_child" {:shapeId (str a-id) :alignSelf "center"}
    "nest_shape"       {:shapeId (str a-id) :parentId (str box-id)}
    "delete_shape"     {:shapeIds [(str row-id)]}
    "duplicate_shape"  {:shapeIds [(str a-id)]}
    "group_shapes"     {:shapeIds [(str a-id) (str b-id)]}
    "create_line"      {:x1 0 :y1 400 :x2 100 :y2 400}
    "create_text"      {:text "new words" :x 0 :y 500}
    "set_text"         {:shapeId (str text-id) :text "other words"}
    "create_component" {:shapeIds [(str box-id)]}
    "clone_shape"      {:shapeId (str row-id) :clones [{:name "copy"} {}]}
    "build_tree"       {:tree {:type "board" :name "Hero" :width 200 :height 100
                               :layout {:type "flex" :dir "column"}
                               :children [{:type "text" :text "Hi"} {:type "rect" :width 10 :height 10}]}}
    "create_page"      {:name "Another"}
    "create_token"     {:type "color" :name "color.brand" :value "#6366f1"}
    "create_token_set" {:name "dark"}
    "set_foundation"   {:name "vibes" :doc "Calm."}))

(t/deftest tools-run-one-after-another-are-taken-back-together
  (let [tokens  (run data "create_tokens" {:tokens [{:type "spacing" :name "gap" :value "16"}
                                                    {:type "color" :name "brand" :value "#6366f1"}]})
        tokened (cfc/process-changes data (:changes tokens) false)]
    (t/is (reverts? tokened "apply_tokens"
                    {:applications [{:shapeId (str row-id) :tokenName "gap" :properties ["columnGap"]}
                                    {:shapeId (str a-id) :tokenName "brand"}
                                    {:shapeId (str text-id) :tokenName "brand"}]}))
    ;; and two answers put together undo in the reverse order
    (let [first-answer  (run data "create_shape" {:type "rect" :width 20 :height 20 :parentId (str row-id)})
          after-first   (cfc/process-changes data (:changes first-answer) false)
          second-answer (run after-first "delete_shape" {:shapeIds [(str a-id)]})
          both          (caw/then-changes (:changes first-answer) (:changes second-answer))
          after-both    (cfc/process-changes data both false)]
      (t/is (= (comparable data)
               (comparable (cfc/process-changes after-both (caw/undo-of both) false)))))))
