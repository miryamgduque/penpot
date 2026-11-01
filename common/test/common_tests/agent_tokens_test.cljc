;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-tokens-test
  (:require
   [app.common.agent.token-status :as tst]
   [app.common.agent.tokens :as cato]
   [app.common.agent.write :as caw]
   [app.common.files.changes :as cfc]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private data (ctf/make-file-data (uuid/next) (uuid/next)))

(defn- run
  [data tool input]
  (tool {:data data} input nil))

(defn- commit
  [data out]
  (cfc/process-changes data (:changes out)))

(defn- token-names
  [data]
  (->> (tst/active-tokens data) (vals) (map :name) (set)))

(t/deftest create-token-names-what-is-wrong
  (t/is (re-find #"name is required" (:problem (run data cato/create-token {:type "color" :value "#fff"}))))
  (t/is (re-find #"value is required" (:problem (run data cato/create-token {:type "color" :name "c"}))))
  (t/is (re-find #"\"hue\" is not a token type" (:problem (run data cato/create-token {:type "hue" :name "c" :value "1"}))))
  (t/is (re-find #"set \"modes/dark\" does not exist — this file has no token sets yet"
                 (:problem (run data cato/create-token {:type "color" :name "c" :value "#fff" :set "modes / dark"})))))

(t/deftest the-first-token-makes-a-live-set
  (let [out   (run data cato/create-token {:type "color" :name "color.brand" :value "#6366f1"})
        after (commit data out)
        lib   (:tokens-lib after)]
    (t/is (= ["Global"] (mapv ctob/get-name (ctob/get-sets lib))))
    ;; the token is in an ACTIVE set: it resolves, and counts for token-only-colors
    (t/is (= #{"color.brand"} (token-names after)))
    (t/is (= "token created — bind it to shapes with apply_tokens" (get-in out [:result :note])))))

(t/deftest a-later-token-joins-the-existing-set
  (let [first-data (commit data (run data cato/create-token {:type "color" :name "a" :value "#111111"}))
        after      (commit first-data (run first-data cato/create-token {:type "spacing" :name "space.md" :value "16"}))]
    (t/is (= 1 (count (ctob/get-sets (:tokens-lib after)))))
    (t/is (= #{"a" "space.md"} (token-names after)))))

(t/deftest create-tokens-is-all-or-nothing-and-makes-one-set
  (let [out   (run data cato/create-tokens {:tokens [{:type "color" :name "a" :value "#111111"}
                                                     {:type "color" :name "b" :value "#222222"}
                                                     {:type "spacing" :name "s" :value "8"}]})
        after (commit data out)]
    (t/is (= 3 (get-in out [:result :created])))
    ;; one "Global" set for all of them, not one per entry
    (t/is (= 1 (count (ctob/get-sets (:tokens-lib after)))))
    (t/is (= #{"a" "b" "s"} (token-names after))))
  (let [out (run data cato/create-tokens {:tokens [{:type "color" :name "a" :value "#111111"}
                                                   {:type "color" :value "#222222"}]})]
    (t/is (re-find #"^tokens\[1\]: create_token: name is required" (:problem out)))
    (t/is (nil? (:changes out))))
  (t/is (re-find #"pass tokens" (:problem (run data cato/create-tokens {})))))

(t/deftest sets-and-themes
  (let [with-set (commit data (run data cato/create-token-set {:name "modes / dark"}))
        lib      (:tokens-lib with-set)]
    (t/is (= ["modes/dark"] (mapv ctob/get-name (ctob/get-sets lib))))
    (t/is (re-find #"already exists" (:problem (run with-set cato/create-token-set {:name "modes/dark"}))))
    (t/testing "a token aimed at a set by name lands in it"
      (let [after (commit with-set (run with-set cato/create-token
                                        {:type "color" :name "bg" :value "#000000" :set "modes/dark"}))
            dark  (first (ctob/get-sets (:tokens-lib after)))]
        (t/is (= ["bg"] (map :name (vals (ctob/get-tokens (:tokens-lib after) (ctob/get-id dark))))))))
    (t/testing "a theme enables sets that exist"
      (t/is (re-find #"no set named \"nope\"" (:problem (run with-set cato/create-token-theme {:name "Dark" :sets ["nope"]}))))
      (let [out   (run with-set cato/create-token-theme {:name "Dark" :sets ["modes/dark"]})
            after (commit with-set out)]
        (t/is (= "Dark" (get-in out [:result :theme])))
        (t/is (some #(= "Dark" (ctob/get-name %)) (ctob/get-themes (:tokens-lib after))))))))

;; --- apply_tokens / activate_theme

(def ^:private row-id (uuid/next))
(def ^:private box-id (uuid/next))
(def ^:private text-id (uuid/next))

(defn- with-page
  "`data` with a laid-out row holding a rect, and a free text."
  [data]
  (let [page-id (first (:pages data))]
    (assoc-in data [:pages-index page-id :objects]
              {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                                           :x 0 :y 0 :width 0.01 :height 0.01
                                           :shapes [row-id text-id]})
               row-id    (cts/setup-shape {:id row-id :type :frame :name "Row"
                                           :x 0 :y 0 :width 400 :height 100
                                           :parent-id uuid/zero :frame-id uuid/zero
                                           :shapes [box-id]
                                           :layout :flex :layout-flex-dir :row
                                           :layout-gap {:row-gap 0 :column-gap 0}
                                           :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}})
               box-id    (cts/setup-shape {:id box-id :type :rect :name "box" :x 0 :y 0 :width 50 :height 50
                                           :parent-id row-id :frame-id row-id})
               text-id   (-> (cts/setup-shape {:id text-id :type :text :name "label"
                                               :x 0 :y 300 :width 40 :height 20 :grow-type :auto-width
                                               :parent-id uuid/zero :frame-id uuid/zero})
                             (update :content txt/change-text "hello" {}))})))

(def ^:private tokens
  [{:type "color" :name "color.brand" :value "#6366F1"}
   {:type "color" :name "color.alias" :value "{color.brand}"}
   {:type "spacing" :name "space.md" :value "16px"}
   {:type "spacing" :name "space.sum" :value "{space.md} * 2"}
   {:type "borderRadius" :name "radius.sm" :value "4"}
   {:type "fontSizes" :name "font.lg" :value "24"}
   {:type "opacity" :name "fade" :value "0.5"}])

(def ^:private designed
  (-> (commit data (run data cato/create-tokens {:tokens tokens}))
      (with-page)))

(defn- view
  [data]
  (let [page-id (first (:pages data))]
    {:data data :page-id page-id :objects (get-in data [:pages-index page-id :objects])}))

(defn- engine
  [calls]
  {:propagate (fn [_ entries] (swap! calls into (map first) entries) {})
   :measure-text (fn [_ _] {:width 90 :height 30})})

(defn- applied
  ([applications] (applied applications (atom [])))
  ([applications calls]
   (let [out (cato/apply-tokens (view designed) {:applications applications} (engine calls))]
     [out (when (:changes out)
            (get-in (commit designed out) [:pages-index (first (:pages designed)) :objects]))])))

(t/deftest tokens-resolve-when-they-are-plain
  (let [active (tst/active-tokens designed)
        worth  #(cato/resolved-value active (get active %))]
    (t/is (= {:color "#6366f1" :opacity 1} (worth "color.brand")))
    (t/is (= {:color "#6366f1" :opacity 1} (worth "color.alias")))
    (t/is (= 16.0 (worth "space.md")))
    (t/is (= ::cato/unresolved (worth "space.sum")))))

(t/deftest apply-tokens-binds-and-writes-the-value
  (let [calls (atom [])
        [out objects]
        (applied [{:shapeId (str box-id) :tokenName "color.alias"}
                  {:shapeId (str box-id) :tokenName "radius.sm"}
                  {:shapeId (str box-id) :tokenName "fade"}
                  {:shapeId (str row-id) :tokenName "space.md" :properties ["columnGap" "paddingTop"]}
                  {:shapeId (str text-id) :tokenName "font.lg"}
                  {:shapeId (str text-id) :tokenName "color.brand"}]
                 calls)
        box  (get objects box-id)
        row  (get objects row-id)
        text (get objects text-id)
        runs (txt/node-seq txt/is-text-node? (:content text))]
    (t/is (every? :ok (-> out :result :results)))
    (t/is (= {:fill "color.alias" :r1 "radius.sm" :r2 "radius.sm" :r3 "radius.sm" :r4 "radius.sm" :opacity "fade"}
             (:applied-tokens box)))
    (t/is (= "#6366f1" (-> box :fills first :fill-color)))
    (t/is (= [4.0 4.0 4.0 4.0] (mapv #(double (get box %)) [:r1 :r2 :r3 :r4])))
    (t/is (= 0.5 (:opacity box)))
    (t/is (= 16.0 (-> row :layout-gap :column-gap)))
    (t/is (= 16.0 (-> row :layout-padding :p1)))
    (t/is (= #{"24"} (set (map :font-size runs))))
    (t/is (= #{"#6366f1"} (set (map #(-> % :fills first :fill-color) runs))))
    ;; the row was laid out again
    (t/is (some #{row-id} @calls))))

(t/deftest apply-tokens-reports-each-application
  (let [[out _] (applied [{:shapeId "nope" :tokenName "color.brand"}
                          {:shapeId (str box-id) :tokenName "missing"}
                          {:shapeId (str box-id) :tokenName "color.brand" :properties ["columnGap"]}
                          {:shapeId (str box-id) :tokenName "color.brand" :properties ["stroke"]}])
        rows    (-> out :result :results)]
    (t/is (= [false false false true] (mapv :ok rows)))
    (t/is (= "invalid shapeId" (:error (first rows))))
    (t/is (re-find #"no token named \"missing\"" (:error (second rows))))
    (t/is (re-find #"is a color token and cannot bind to columnGap" (:error (nth rows 2)))))
  (t/is (re-find #"no applications given" (:problem (cato/apply-tokens (view designed) {} (engine (atom [])))))))

(t/deftest what-cannot-be-resolved-here-goes-to-the-workspace
  (let [[out _] (applied [{:shapeId (str row-id) :tokenName "space.sum"}])]
    (t/is (some? (:fallback out)))
    (t/is (nil? (:changes out)))))

(t/deftest activating-a-theme-gives-bound-shapes-the-new-value
  (let [step    (fn [data tool input] (commit data (tool (view data) input (engine (atom [])))))
        themed  (-> designed
                    (step cato/apply-tokens {:applications [{:shapeId (str box-id) :tokenName "color.brand"}]})
                    (step cato/create-token-set {:name "dark"})
                    (step cato/create-token {:type "color" :name "color.brand" :value "#000000" :set "dark"})
                    (step cato/create-token-theme {:name "Dark" :sets ["Global" "dark"]}))
        fill    (fn [data] (-> (get-in data [:pages-index (first (:pages data)) :objects box-id]) :fills first :fill-color))
        out     (cato/activate-theme (view themed) {:name "Dark"} (engine (atom [])))]
    (t/is (= "#6366f1" (fill themed)))
    (t/is (= "#000000" (fill (commit themed out))))
    (t/is (re-find #"no theme named \"Nope\" — this file has: Dark"
                   (:problem (cato/activate-theme (view themed) {:name "Nope"} (engine (atom []))))))))

(t/deftest a-token-created-again-changes-and-what-is-bound-follows
  (let [step    (fn [data tool input] (commit data (tool (view data) input (engine (atom [])))))
        bound   (step designed cato/apply-tokens
                      {:applications [{:shapeId (str box-id) :tokenName "color.alias"}
                                      {:shapeId (str text-id) :tokenName "color.brand"}
                                      {:shapeId (str row-id) :tokenName "space.md" :properties ["columnGap"]}]})
        objects (fn [data] (get-in data [:pages-index (first (:pages data)) :objects]))
        fill    (fn [data] (-> (get (objects data) box-id) :fills first :fill-color))
        id-of   (fn [data] (:id (get (tst/active-tokens data) "color.brand")))
        out     (cato/create-tokens (view bound)
                                    {:tokens [{:type "color" :name "color.brand" :value "#ff0000"}
                                              {:type "color" :name "color.new" :value "#00ff00"}
                                              ;; the value it has already
                                              {:type "borderRadius" :name "radius.sm" :value "4"}]}
                                    (engine (atom [])))
        after   (commit bound out)]
    (t/is (= "#6366f1" (fill bound)))
    ;; the same token, not a second one of that name
    (t/is (= (id-of bound) (id-of after)))
    (t/is (= (inc (count (token-names bound))) (count (token-names after))))
    ;; the box is bound to a token that refers to the changed one, and follows
    (t/is (= "#ff0000" (fill after)))
    (t/is (= #{"#ff0000"} (->> (txt/node-seq txt/is-text-node? (:content (get (objects after) text-id)))
                               (map #(-> % :fills first :fill-color))
                               (set))))
    ;; what is bound to another token is left alone
    (t/is (= 16.0 (-> (get (objects after) row-id) :layout-gap :column-gap)))
    (t/is (= ["color.brand"] (-> out :result :changed)))
    (t/is (= 2 (-> out :result :restyled)))
    (t/is (re-find #"nothing needs re-applying" (-> out :result :note)))
    ;; each shape's changes are said once, however many of its properties follow
    (t/is (= 2 (count (filter #(= :mod-obj (:type %)) (:changes out)))))
    (t/is (= "#6366f1" (fill (cfc/process-changes after (caw/undo-of (:changes out))))))
    (t/testing "one token, by create_token"
      (let [out (cato/create-token (view bound) {:type "spacing" :name "space.md" :value "24"} (engine (atom [])))]
        (t/is (= 24.0 (-> (get (objects (commit bound out)) row-id) :layout-gap :column-gap)))
        (t/is (= 1 (-> out :result :restyled)))))
    (t/testing "a token nothing is bound to changes, and asks the host nothing"
      (let [out (cato/create-token (view bound) {:type "opacity" :name "fade" :value "0.2"}
                                   (assoc (engine (atom [])) :resolve-tokens (constantly nil)))]
        (t/is (nil? (:pending out)))
        (t/is (= 0 (-> out :result :restyled)))
        (t/is (= "0.2" (:value (get (tst/active-tokens (commit bound out)) "fade"))))))
    (t/testing "the host resolves the tokens as they are after the change"
      (let [asked (cato/create-token (view bound) {:type "spacing" :name "space.md" :value "{radius.sm} * 3"}
                                     (assoc (engine (atom [])) :resolve-tokens (constantly nil)))
            out   (cato/create-token (view bound) {:type "spacing" :name "space.md" :value "{radius.sm} * 3"}
                                     (assoc (engine (atom [])) :resolve-tokens (constantly {"space.md" {:resolved-value 12}})))]
        (t/is (= "{radius.sm} * 3" (-> asked :pending :tokens (get "space.md") :value)))
        (t/is (nil? (:changes asked)))
        (t/is (= 12 (-> (get (objects (commit bound out)) row-id) :layout-gap :column-gap)))))))

(t/deftest a-shadow-token-is-a-list-of-shadows
  (let [shadow  [{:offsetX 0 :offsetY 4 :blur 12 :spread 0 :color "rgba(15, 23, 42, 0.12)"}
                 {:x 2 :y "2px" :blur 0 :color "#11223380" :inset true}]
        step    (fn [data tool input] (commit data (tool (view data) input (engine (atom [])))))
        with    (step designed cato/create-tokens {:tokens [{:type "shadow" :name "shadow.md" :value shadow}]})
        token   (get (tst/active-tokens with) "shadow.md")
        applied (step with cato/apply-tokens {:applications [{:shapeId (str box-id) :tokenName "shadow.md"}]})
        box     (get-in applied [:pages-index (first (:pages applied)) :objects box-id])]
    ;; stored as the library stores one: strings, under its own keys
    (t/is (= [{:offset-x "0" :offset-y "4" :blur "12" :spread "0" :color "rgba(15, 23, 42, 0.12)" :inset false}
              {:offset-x "2" :offset-y "2" :blur "0" :spread "0" :color "#11223380" :inset true}]
             (:value token)))
    (t/is (= "shadow.md" (-> box :applied-tokens :shadow)))
    (t/is (= [[0.0 4.0 12.0 {:color "#0f172a" :opacity 0.12} :drop-shadow]
              [2.0 2.0 0.0 {:color "#112233" :opacity (/ 128 255.0)} :inner-shadow]]
             (mapv (juxt :offset-x :offset-y :blur :color :style) (:shadow box))))
    (t/is (re-find #"a shadow's value is a list of shadows"
                   (:problem (run designed cato/create-token {:type "shadow" :name "s" :value "0 4px 12px black"}))))
    (t/is (re-find #"a shadow's value is a list of shadows"
                   (:problem (run designed cato/create-token {:type "shadow" :name "s" :value [{:blur -1 :color "#000"}]}))))))

(t/deftest a-font-family-token-resolves-to-its-families
  (let [active {"font.body" {:name "font.body" :type :font-family :value ["Inter" "sans-serif"]}
                "font.alias" {:name "font.alias" :type :font-family :value "{font.body}"}
                "font.text" {:name "font.text" :type :font-family :value "Inter, sans-serif"}}]
    (t/is (= ["Inter" "sans-serif"] (cato/resolved-value active (get active "font.body"))))
    (t/is (= ["Inter" "sans-serif"] (cato/resolved-value active (get active "font.alias"))))
    (t/is (= ["Inter" "sans-serif"] (cato/resolved-value active (get active "font.text"))))))

;; --- With the resolver library a host hands over

(defn- resolver
  "Stands in for the host's resolver: knows these tokens once asked twice,
  the way an asynchronous one does."
  [answers]
  (let [asked (atom #{})]
    (fn [tokens]
      (if (contains? @asked tokens)
        answers
        (do (swap! asked conj tokens) nil)))))

(t/deftest a-host-resolver-is-asked-first-and-then-used
  (let [resolve (resolver {"space.sum" {:resolved-value 32 :unit "px"}
                           "color.brand" {:resolved-value "rgba(0,0,0,0.5)" :color {:color "#000000" :opacity 0.5}}
                           "fade" {:error :invalid-opacity}})
        apply!  #(cato/apply-tokens (view designed)
                                    {:applications [{:shapeId (str row-id) :tokenName "space.sum"}
                                                    {:shapeId (str box-id) :tokenName "color.brand"}
                                                    {:shapeId (str box-id) :tokenName "fade"}]}
                                    (assoc (engine (atom [])) :resolve-tokens resolve))
        asked   (apply!)
        applied (apply!)
        objects (get-in (commit designed applied) [:pages-index (first (:pages designed)) :objects])]
    ;; first the tool says which tokens it needs resolved, and changes nothing
    (t/is (= (tst/active-tokens designed) (-> asked :pending :tokens)))
    (t/is (nil? (:changes asked)))
    ;; then arithmetic and an rgba color are just values
    (t/is (= 32 (-> (get objects row-id) :layout-gap :column-gap)))
    (t/is (= {:fill-color "#000000" :fill-opacity 0.5}
             (-> (get objects box-id) :fills first (select-keys [:fill-color :fill-opacity]))))
    ;; a token that does not resolve is bound, and writes nothing
    (t/is (= "fade" (-> (get objects box-id) :applied-tokens :opacity)))
    (t/is (not= 0.5 (:opacity (get objects box-id))))))

(t/deftest composite-and-typography-values-are-written-part-by-part
  (let [fonts   {"gfont-inter" {:id "gfont-inter" :family "Inter" :backend :google
                                :variants [{:id "regular" :weight "400" :style "normal"}]}}
        data    (commit designed
                        (run designed cato/create-tokens
                             {:tokens [{:type "fontFamilies" :name "font.body" :value "Inter"}
                                       {:type "letterSpacing" :name "track" :value "2"}]}))
        answers {"font.body" {:resolved-value ["\"Inter\"" "sans-serif"]}
                 "track" {:resolved-value 1.5}}
        out     (cato/apply-tokens (assoc (view data) :fonts fonts)
                                   {:applications [{:shapeId (str text-id) :tokenName "font.body"}
                                                   {:shapeId (str text-id) :tokenName "track"}]}
                                   (assoc (engine (atom [])) :resolve-tokens (constantly answers)))
        runs    (->> (get-in (commit data out) [:pages-index (first (:pages data)) :objects text-id :content])
                     (txt/node-seq txt/is-text-node?))]
    (t/is (every? :ok (-> out :result :results)))
    (t/is (= #{"gfont-inter"} (set (map :font-id runs))))
    (t/is (= #{"Inter"} (set (map :font-family runs))))
    (t/is (= #{"1.5"} (set (map :letter-spacing runs))))
    ;; with no font list, a font family cannot be matched here
    (t/is (some? (:fallback (cato/apply-tokens (view data)
                                               {:applications [{:shapeId (str text-id) :tokenName "font.body"}]}
                                               (assoc (engine (atom [])) :resolve-tokens (constantly answers))))))))

(t/deftest a-theme-switch-asks-for-the-tokens-of-the-new-theme
  (let [step    (fn [data tool input] (commit data (tool (view data) input (engine (atom [])))))
        themed  (-> designed
                    (step cato/apply-tokens {:applications [{:shapeId (str row-id) :tokenName "space.md"}]})
                    (step cato/create-token-set {:name "dense"})
                    (step cato/create-token {:type "spacing" :name "space.md" :value "{space.md.base} / 2" :set "dense"})
                    (step cato/create-token-theme {:name "Dense" :sets ["Global" "dense"]}))
        asked   (cato/activate-theme (view themed) {:name "Dense"}
                                     (assoc (engine (atom [])) :resolve-tokens (constantly nil)))
        out     (cato/activate-theme (view themed) {:name "Dense"}
                                     (assoc (engine (atom [])) :resolve-tokens (constantly {"space.md" {:resolved-value 8}})))
        row     (get-in (commit themed out) [:pages-index (first (:pages themed)) :objects row-id])]
    ;; the tokens it asks about are the ones active AFTER the switch
    (t/is (= "{space.md.base} / 2" (-> asked :pending :tokens (get "space.md") :value)))
    (t/is (= 8 (-> row :layout-gap :column-gap)))))

(t/deftest a-font-family-token-is-stored-as-a-list-of-families
  (let [after  (commit data (run data cato/create-tokens
                                 {:tokens [{:type "fontFamilies" :name "font.body" :value "Inter, Helvetica Neue , sans-serif"}
                                           {:type "fontFamilies" :name "font.alias" :value "{font.body}"}
                                           {:type "spacing" :name "space.sm" :value "4, 8"}]}))
        tokens (tst/active-tokens after)]
    (t/is (= ["Inter" "Helvetica Neue" "sans-serif"] (:value (get tokens "font.body"))))
    ;; a reference stays what it is, and other types are not touched
    (t/is (= "{font.body}" (:value (get tokens "font.alias"))))
    (t/is (= "4, 8" (:value (get tokens "space.sm"))))))
