;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-compose-test
  "The composition tools with a stand-in engine: one call runs several shape
  tools in a row, each on what the previous one left."
  (:require
   [app.common.agent.compose :as cap]
   [app.common.files.changes :as cfc]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private file-id (uuid/next))
(def ^:private page-id (uuid/next))
(def ^:private card-id (uuid/next))
(def ^:private title-id (uuid/next))
(def ^:private photo-id (uuid/next))
(def ^:private list-id (uuid/next))

(def ^:private objects
  {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                               :x 0 :y 0 :width 0.01 :height 0.01
                               :shapes [card-id list-id]})
   card-id   (cts/setup-shape {:id card-id :type :frame :name "Card"
                               :x 0 :y 0 :width 200 :height 100
                               :parent-id uuid/zero :frame-id uuid/zero
                               :shapes [title-id photo-id]})
   title-id  (-> (cts/setup-shape {:id title-id :type :text :name "title"
                                   :x 10 :y 10 :width 40 :height 20 :grow-type :auto-width
                                   :parent-id card-id :frame-id card-id})
                 (update :content txt/change-text "Title" {}))
   photo-id  (cts/setup-shape {:id photo-id :type :rect :name "photo"
                               :x 10 :y 40 :width 50 :height 50
                               :parent-id card-id :frame-id card-id})
   list-id   (cts/setup-shape {:id list-id :type :frame :name "List"
                               :x 0 :y 300 :width 600 :height 120
                               :parent-id uuid/zero :frame-id uuid/zero
                               :shapes []
                               :layout :flex :layout-flex-dir :row})})

(def ^:private data
  (-> (ctf/make-file-data file-id page-id)
      (assoc-in [:pages-index page-id :objects] objects)))

(def ^:private view
  {:file-id file-id :page-id page-id :data data :objects objects
   :libraries {file-id {:id file-id :data data}}
   :fonts {"gfont-inter" {:id "gfont-inter" :family "Inter" :backend :google
                          :variants [{:id "regular" :weight "400" :style "normal"}]}}})

(defn- after
  [changes]
  (get-in (cfc/process-changes data changes) [:pages-index page-id :objects]))

(defn- options
  ([] (options {}))
  ([resources]
   {:guard {:enforced? false}
    :resources resources
    :propagate (fn [_ _] {})
    :measure-text (fn [_ _] {:width 80 :height 24})}))

(def ^:private url "https://example.com/a.png")

(t/deftest clone-shape-refusals
  (t/is (re-find #"missing or invalid shapeId" (:problem (cap/clone-shape view {:clones [{}]} (options)))))
  (t/is (re-find #"pass clones" (:problem (cap/clone-shape view {:shapeId (str card-id)} (options)))))
  (t/is (re-find #"at most 12" (:problem (cap/clone-shape view {:shapeId (str card-id) :clones (repeat 13 {})} (options)))))
  (t/is (re-find #"must be a board or a group"
                 (:problem (cap/clone-shape view {:shapeId (str card-id) :parentId (str photo-id) :clones [{}]} (options))))))

(t/deftest clone-shape-says-which-images-it-needs
  (let [input {:shapeId (str card-id)
               :clones [{:imageByName {:photo url}} {:imageByName {:photo url}} {:imageByName {:photo "an-id"}}]}]
    (t/is (= [{:kind :image :url url :name "clone-image"}] (cap/clone-shape-needs input)))
    (t/is (re-find #"content-length"
                   (:problem (cap/clone-shape view input (options {url {:error :unknown-size}})))))
    (t/is (some? (:fallback (cap/clone-shape view input (options)))))))

(t/deftest clone-shape-copies-overrides-and-appends
  (let [media  {:id (uuid/next) :width 10 :height 10 :mtype "image/png"}
        answer (cap/clone-shape view
                                {:shapeId (str card-id) :parentId (str list-id)
                                 :clones [{:name "One" :textByName {:title "First"}}
                                          {:name "Two" :imageByName {:photo url}}]}
                                (options {url media}))
        result (after (:changes answer))
        clones (mapv #(get result (parse-uuid (:shapeId %))) (-> answer :result :clones))
        child  (fn [clone nm] (some #(when (= nm (:name (get result %))) (get result %)) (:shapes clone)))]
    (t/is (= ["One" "Two"] (mapv :name clones)))
    (t/is (= #{list-id} (set (map :parent-id clones))))
    (t/is (= 2 (count (:shapes (get result list-id)))))
    (t/is (= "First" (txt/content->text (:content (child (first clones) "title")))))
    (t/is (= "Title" (txt/content->text (:content (child (second clones) "title")))))
    (t/is (= (:id media) (-> (child (second clones) "photo") :fills first :fill-image :id)))
    ;; the original is untouched
    (t/is (= "Title" (txt/content->text (:content (get result title-id)))))))

(t/deftest free-clones-fan-out-to-the-right
  (let [answer (cap/clone-shape view {:shapeId (str card-id) :clones [{} {}]} (options))
        result (after (:changes answer))]
    (t/is (= [240.0 480.0]
             (mapv #(double (:x (get result (parse-uuid (:shapeId %))))) (-> answer :result :clones))))))

(t/deftest build-tree-refusals
  (t/is (re-find #"pass tree" (:problem (cap/build-tree view {} (options)))))
  (t/is (re-find #"has type \"star\"" (:problem (cap/build-tree view {:tree {:type "star"}} (options)))))
  (t/is (re-find #"only boards contain children"
                 (:problem (cap/build-tree view {:tree {:type "rect" :children [{:type "rect"}]}} (options)))))
  (t/is (nil? (cap/build-tree-needs {:tree {:type "star"}}))))

(t/deftest build-tree-builds-the-whole-subtree
  (let [media  {:id (uuid/next) :width 100 :height 50 :mtype "image/png"}
        tree   {:type "board" :name "Hero" :width 400 :height 200
                :layout {:type "flex" :dir "column" :rowGap 8}
                :children [{:type "text" :name "Heading" :text "Hello" :font {:family "Inter"}}
                           {:type "image" :name "Pic" :url url}
                           {:type "rect" :width 20 :height 20 :layoutChild {:horizontalSizing "fill"}}]}
        answer (cap/build-tree view {:tree tree} (options {url media}))
        result (after (:changes answer))
        ids    (-> answer :result :ids)
        hero   (get result (parse-uuid (get ids "Hero")))]
    (t/is (= [{:kind :image :url url :name "Pic"}] (cap/build-tree-needs {:tree tree})))
    (t/is (= 4 (-> answer :result :created)))
    (t/is (= #{"Hero" "Heading" "Pic" "tree.children[2]"} (set (keys ids))))
    (t/is (= :flex (:layout hero)))
    (t/is (= 3 (count (:shapes hero))))
    (t/is (= "gfont-inter"
             (->> (get result (parse-uuid (get ids "Heading"))) :content (txt/node-seq txt/is-text-node?) first :font-id)))
    (t/is (= :fill (:layout-item-h-sizing (get result (parse-uuid (get ids "tree.children[2]"))))))))

(t/deftest a-refused-node-keeps-what-was-built
  (let [answer (cap/build-tree view
                               {:tree {:type "board" :name "Hero" :width 100 :height 100
                                       :children [{:type "rect" :name "ok" :width 10 :height 10}
                                                  {:type "text" :name "bad" :text "x" :font {:family "Nope"}}
                                                  {:type "rect" :name "never" :width 10 :height 10}]}}
                               (options))
        result (after (:changes answer))]
    (t/is (re-find #"build_tree at tree.children\[1\]: set_font: no font matches" (-> answer :result :error)))
    (t/is (= #{"Hero" "ok" "bad"} (set (keys (-> answer :result :ids)))))
    (t/is (every? #(contains? result (parse-uuid %)) (vals (-> answer :result :ids))))))

(t/deftest tokens-wait-for-the-token-tool
  (let [tree {:type "rect" :name "r" :width 10 :height 10 :tokens [{:name "color.brand"}]}]
    (t/is (some? (:fallback (cap/build-tree view {:tree tree} (options)))))
    (t/is (= 1 (-> (cap/build-tree view {:tree tree}
                                   (assoc (options) :apply-tokens (fn [_ _ _] {:changes [] :result {}})))
                   :result :created)))))

(t/deftest build-tree-makes-a-component-and-places-instances-of-it
  (let [tree   {:type "board" :name "Layers" :width 240 :height 300
                :layout {:type "flex" :dir "column"}
                :children [{:type "board" :name "Layer row" :component true :width 240 :height 28
                            :layout {:type "flex" :dir "row"}
                            :children [{:type "text" :name "Label" :text "Layer"}]}
                           {:type "instance" :of "Layer row" :name "Row hero" :texts {:Label "hero"}}
                           {:type "instance" :of "Layer row" :name "Row nav" :texts {"Label" "nav"}
                            :layoutChild {:horizontalSizing "fill"}}]}
        answer (cap/build-tree view {:tree tree} (options))
        result (after (:changes answer))
        ids    (-> answer :result :ids)
        shape  (fn [nm] (get result (parse-uuid (get ids nm))))
        label  (fn [nm] (some #(when (= "Label" (:name (get result %))) (get result %))
                              (:shapes (shape nm))))]
    (t/is (nil? (-> answer :result :error)))
    (t/is (= ["Layer row"] (keys (-> answer :result :components))))
    (t/is (true? (:main-instance (shape "Layer row"))))
    (t/is (= (:component-id (shape "Layer row")) (:component-id (shape "Row hero")) (:component-id (shape "Row nav"))))
    (t/is (not (:main-instance (shape "Row hero"))))
    (t/is (= 3 (count (:shapes (shape "Layers")))))
    (t/is (= "hero" (txt/content->text (:content (label "Row hero")))))
    (t/is (= "nav" (txt/content->text (:content (label "Row nav")))))
    (t/is (= "Layer" (txt/content->text (:content (label "Layer row")))))
    (t/is (= :fill (:layout-item-h-sizing (shape "Row nav")))))
  (t/is (re-find #"instance tree.children\[0\] needs `of`"
                 (:problem (cap/build-tree view {:tree {:type "board" :children [{:type "instance" :of "Nope"}]}} (options)))))
  (t/is (re-find #"only a board becomes a component"
                 (:problem (cap/build-tree view {:tree {:type "board" :children [{:type "rect" :name "r" :component true}]}} (options)))))
  (t/is (re-find #"marked component and needs a `name`"
                 (:problem (cap/build-tree view {:tree {:type "board" :component true}} (options))))))
