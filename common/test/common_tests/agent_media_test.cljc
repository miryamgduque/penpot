;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-media-test
  "The media tools with stand-in resources: what they ask the host to fetch,
  what they refuse, and what they add to the page."
  (:require
   [app.common.agent.media :as cam]
   [app.common.files.changes :as cfc]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private file-id (uuid/next))
(def ^:private page-id (uuid/next))
(def ^:private row-id (uuid/next))
(def ^:private a-id (uuid/next))
(def ^:private text-id (uuid/next))

(def ^:private objects
  {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                               :x 0 :y 0 :width 0.01 :height 0.01
                               :shapes [row-id text-id]})
   row-id    (cts/setup-shape {:id row-id :type :frame :name "Row"
                               :x 0 :y 0 :width 400 :height 100
                               :parent-id uuid/zero :frame-id uuid/zero
                               :shapes [a-id]
                               :layout :flex :layout-flex-dir :row})
   a-id      (cts/setup-shape {:id a-id :type :rect :name "a" :x 0 :y 0 :width 50 :height 50
                               :parent-id row-id :frame-id row-id})
   text-id   (-> (cts/setup-shape {:id text-id :type :text :name "label"
                                   :x 0 :y 300 :width 40 :height 20
                                   :grow-type :auto-width
                                   :parent-id uuid/zero :frame-id uuid/zero})
                 (update :content txt/change-text "hello" {}))})

(def ^:private data
  (-> (ctf/make-file-data file-id page-id)
      (assoc-in [:pages-index page-id :objects] objects)))

(def ^:private fonts
  {"sourcesanspro" {:id "sourcesanspro" :family "sourcesanspro" :backend :builtin
                    :variants [{:id "regular" :weight "400" :style "normal"}
                               {:id "bold" :weight "700" :style "normal"}]}
   "gfont-inter"   {:id "gfont-inter" :family "Inter" :backend :google
                    :variants [{:id "regular" :weight "400" :style "normal"}
                               {:id "600" :weight "600" :style "normal"}]}
   "custom-1"      {:id "custom-1" :family "House Sans" :backend :custom
                    :variants [{:id "normal-400" :weight "400" :style "normal"}]}})

(def ^:private view
  {:file-id file-id :page-id page-id :data data :objects objects :fonts fonts})

(defn- after
  [changes]
  (get-in (cfc/process-changes data changes) [:pages-index page-id :objects]))

(defn- options
  [calls resources]
  {:guard {:enforced? false}
   :resources resources
   :propagate (fn [_ entries] (swap! calls into (map first) entries) {})
   :measure-text (fn [_ _] {:width 80 :height 24})
   :parse-svg (fn [body]
                (when (= "<svg/>" body)
                  {:tag :svg :attrs {:width "24" :height "24" :viewBox "0 0 24 24"}
                   :content [{:tag :path :attrs {:d "M0 0L10 10"} :content []}]}))})

(def ^:private url "https://example.com/a.png")

(t/deftest insert-image-says-what-it-needs
  (t/is (= [{:kind :image :url url :name "image"}] (cam/insert-image-needs {:url url})))
  (t/is (nil? (cam/insert-image-needs {:url "nope"})))
  (t/is (re-find #"absolute http" (:problem (cam/insert-image view {:url "nope"} (options (atom []) {})))))
  (t/is (some? (:fallback (cam/insert-image view {:url url} (options (atom []) {}))))))

(t/deftest insert-image-explains-a-failed-download
  (let [answer (cam/insert-image view {:url url} (options (atom []) {url {:error :unknown-size}}))]
    (t/is (re-find #"content-length" (:problem answer)))))

(t/deftest insert-image-flows-into-a-layout
  (let [calls  (atom [])
        media  {:id (uuid/next) :width 600 :height 400 :mtype "image/png"}
        answer (cam/insert-image view {:url url :parentId (str row-id) :width 300}
                                 (options calls {url media}))
        result (:result answer)
        shape  (get (after (:changes answer)) (parse-uuid (:id result)))]
    (t/is (= [300 200] [(:width result) (:height result)]))
    (t/is (= row-id (:parent-id shape)))
    (t/is (= (:id media) (-> shape :fills first :fill-image :id)))
    (t/is (= [row-id] @calls))))

(t/deftest search-icons-reads-the-service-answer
  (let [search (:url (first (cam/search-icons-needs {:query "home" :limit 2})))
        body   "{\"icons\":[\"a:b\",\"c:d\",\"e:f\"],\"total\":3}"
        ok     (cam/search-icons view {:query "home" :limit 2} (options (atom []) {search {:status 200 :body body}}))
        down   (cam/search-icons view {:query "home" :limit 2} (options (atom []) {search {:status 0 :body ""}}))]
    (t/is (re-find #"query=home&limit=32$" search))
    (t/is (= ["a:b" "c:d"] (-> ok :result :icons)))
    (t/is (nil? (:changes ok)))
    (t/is (re-find #"answered 0" (:problem down)))
    (t/is (re-find #"query is required" (:problem (cam/search-icons view {} (options (atom []) {})))))))

(t/deftest insert-icon-imports-the-svg
  (let [svg    (:url (first (cam/insert-icon-needs {:icon "lucide:house"})))
        ok     (cam/insert-icon view {:icon "lucide:house" :x 500 :y 500} (options (atom []) {svg {:status 200 :body "<svg/>"}}))
        shape  (get (after (:changes ok)) (parse-uuid (-> ok :result :id)))]
    (t/is (= "https://api.iconify.design/lucide/house.svg?height=24" svg))
    (t/is (= "lucide:house" (:name shape)))
    (t/is (re-find #"not found" (:problem (cam/insert-icon view {:icon "lucide:house"} (options (atom []) {svg {:status 404 :body ""}})))))
    (t/is (re-find #"did not return SVG" (:problem (cam/insert-icon view {:icon "lucide:house"} (options (atom []) {svg {:status 200 :body "nope"}})))))
    (t/is (re-find #"prefix:name" (:problem (cam/insert-icon view {:icon "house"} (options (atom []) {})))))))

(t/deftest search-fonts-looks-in-the-font-list
  (t/is (= ["Inter"] (->> (cam/search-fonts view {:query "int"} nil) :result :fonts (map :family))))
  (t/is (re-find #"shorter substring" (-> (cam/search-fonts view {:query "zzz"} nil) :result :note)))
  (t/is (some? (:fallback (cam/search-fonts (dissoc view :fonts) {:query "int"} nil)))))

(t/deftest set-font-refusals
  (let [run #(cam/set-font view % (options (atom []) {}))]
    (t/is (re-find #"non-empty array" (:problem (run {:family "Inter"}))))
    (t/is (re-find #"give a font" (:problem (run {:shapeIds [(str text-id)]}))))
    (t/is (re-find #"no font matches" (:problem (run {:shapeIds [(str text-id)] :family "Nope"}))))
    (t/is (re-find #"not a text shape" (:problem (run {:shapeIds [(str a-id)] :family "Inter"}))))
    (t/is (re-find #"does not exist for Inter" (:problem (run {:shapeIds [(str text-id)] :family "inter" :variantId "900"}))))
    ;; a font the team uploaded is a font like any other: the host loads it
    (t/is (= "House Sans" (-> (run {:shapeIds [(str text-id)] :family "house sans"}) :result :family)))))

(t/deftest set-font-rewrites-the-text-and-measures-it
  (let [answer (cam/set-font view {:shapeIds [(str text-id)] :family "inter" :variantId "600"}
                             (options (atom []) {}))
        shape  (get (after (:changes answer)) text-id)
        nodes  (txt/node-seq txt/is-text-node? (:content shape))]
    (t/is (= {:family "Inter" :variant "600" :shapes 1} (select-keys (:result answer) [:family :variant :shapes])))
    (t/is (= #{"gfont-inter"} (set (map :font-id nodes))))
    (t/is (= #{"600"} (set (map :font-weight nodes))))))
