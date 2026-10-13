;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-recipe-build-test
  "build_recipe with a stand-in engine: one call builds what a recipe
  describes, with its tokens, its components and its variants."
  (:require
   #?(:clj [clojure.walk :as walk])
   [app.common.agent.media :as cam]
   [app.common.agent.read :as car]
   [app.common.agent.recipe-build :as carb]
   [app.common.agent.recipe-save :as cars-save]
   [app.common.agent.recipes :as cars]
   [app.common.agent.tokens :as cato]
   [app.common.agent.tools :as cat]
   [app.common.agent.usage :as cau]
   [app.common.files.changes :as cfc]
   [app.common.files.helpers :as cfh]
   [app.common.files.validate :as cfv]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private file-id (uuid/next))
(def ^:private page-id (uuid/next))
(def ^:private frame-id (uuid/next))

(def ^:private data
  (-> (ctf/make-file-data file-id page-id)
      (assoc-in [:pages-index page-id :objects]
                {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                                             :x 0 :y 0 :width 0.01 :height 0.01
                                             :shapes [frame-id]})
                 frame-id  (cts/setup-shape {:id frame-id :type :frame :name "Screen"
                                             :x 0 :y 0 :width 1200 :height 800
                                             :parent-id uuid/zero :frame-id uuid/zero
                                             :shapes []
                                             :layout :flex :layout-flex-dir :column})})))

(defn- view
  [data]
  {:file-id file-id :page-id page-id :data data
   :objects (get-in data [:pages-index page-id :objects])
   :libraries {file-id {:id file-id :data data}}
   :fonts {}})

(defn- resolve-tokens
  "Stands in for the workspace's resolver: every token is worth its value."
  [tokens]
  (into {}
        (map (fn [[nm token]]
               [nm (cond
                     (= :color (:type token))
                     {:resolved-value (:value token) :color {:color (:value token) :opacity 1}}

                     ;; a list of families, a list of shadows
                     (contains? #{:font-family :shadow} (:type token))
                     {:resolved-value (cato/resolved-value tokens token)}

                     :else
                     {:resolved-value (parse-double (:value token))})]))
        tokens))

(def ^:private recipes
  "The built-in recipes. A font weight is matched to a font's variants in
  JavaScript only, so on the JVM they are built without theirs."
  #?(:cljs cars/builtin
     :clj  (walk/postwalk #(cond-> % (map? %) (dissoc :fontWeight)) cars/builtin)))

(def ^:private options
  {:guard {:enforced? false}
   :resources {}
   :recipes recipes
   :resolve-tokens resolve-tokens
   :propagate (fn [_ _] {})
   :measure-text (fn [_ _] {:width 80 :height 24})})

(defn- build
  "Runs build_recipe on `data` → `[data' answer]`."
  ([data input] (build data input options))
  ([data input options]
   (let [out (cat/run "build_recipe" (view data) input options)]
     [(if (seq (:changes out)) (cfc/process-changes data (:changes out)) data) out])))

(defn- objects
  [data]
  (get-in data [:pages-index page-id :objects]))

(defn- shape
  [data id]
  (get (objects data) (parse-uuid id)))

(defn- words
  [shape]
  (txt/content->text (:content shape)))

(defn- child
  "The shape called `nm` under the one with `id`."
  [data id nm]
  (let [objects (objects data)]
    (letfn [(walk [id]
              (some (fn [kid]
                      (let [kid (get objects kid)]
                        (if (= nm (:name kid)) kid (walk (:id kid)))))
                    (:shapes (get objects id))))]
      (walk (parse-uuid id)))))

(defn- main-of
  "The main shape of the member of a variant set with these values."
  [data member]
  (some (fn [component]
          (when (= member (into {} (map (juxt :name :value)) (:variant-properties component)))
            (get (objects data) (:main-instance-id component))))
        (ctkl/components-seq data)))

(defn- token-names
  [data]
  (some->> (:tokens-lib data) ctob/get-all-tokens-map keys set))

(defn- errors
  [data]
  (cfv/validate-file {:id file-id :data data :features #{"components/v2"}}
                     {file-id {:id file-id :data data}}))

(t/deftest refusals-name-what-is-wrong
  (let [problem (fn [input] (:problem (second (build data input))))]
    (t/is (re-find #"pass recipe — the name of one of: accordion, accordion-item" (problem {})))
    (t/is (re-find #"no recipe named \"buton\"" (problem {:recipe "buton"})))
    (t/is (re-find #"\"text\" is not a param of \"button\"" (problem {:recipe "button" :params {:text "x"}})))
    (t/is (re-find #"\"Kind\" is one of" (problem {:recipe "button" :variant {:Kind "Loud"}})))
    (t/is (re-find #"no shape on this page" (problem {:recipe "button" :parentId (str (uuid/next))})))
    (t/is (re-find #"roles names \"color.brand\", which is not a role"
                   (problem {:recipe "button" :roles {"color.brand" "x"}})))
    (t/is (re-find #"roles names the token \"brand.500\", and this file has no token"
                   (problem {:recipe "button" :roles {"color.primary" "brand.500"}})))))

(t/deftest the-index-names-every-recipe-by-category
  (let [index (carb/index cars/builtin)]
    (t/is (= 5 (count (re-seq #"(?m)^- " index))))
    (t/testing "whole screens first"
      (t/is (re-find #"^- screen: " index)))
    (t/is (re-find #"(?m)^- control: avatar \(avatar\); badge \(badge\); button \(button\)" index))
    (t/is (every? #(re-find (re-pattern (str "\\b" % " \\(")) index) (keys cars/builtin)))))

(t/deftest recipes-are-found-by-name-purpose-or-keyword
  (let [find  (fn [input] (cat/run "find_recipes" (view data) input options))
        names (fn [input] (mapv :name (-> (find input) :result :matches)))]
    (t/is (= "button" (first (names {:query "button"}))))
    (t/is (= "form" (first (names {:query "sign up form"}))))
    (t/is (= "choice-control" (first (names {:query "checkbox"}))))
    (t/is (contains? #{"pricing-table" "pricing-page"} (first (names {:query "subscription plans"}))))
    (t/is (= "landing-page" (first (names {:query "landing page for a startup"}))))
    (t/is (= "dashboard-screen" (first (names {:query "dashboard screen"}))))
    (t/is (= "top-bar" (first (names {:query "navbar"}))))
    (t/is (= "stat-cards" (first (names {:query "dashboard kpi"}))))
    (t/is (= (sort (map :name (filter #(= "navigation" (:category %)) cars/all)))
             (names {:category "navigation"})))
    (t/is (<= (count (names {:query "a"})) 5))
    (t/testing "what build_recipe needs is in the answer"
      (let [button (first (-> (find {:query "button"}) :result :matches))]
        (t/is (= ["label"] (mapv :name (:params button))))
        (t/is (= ["Kind" "Size"] (mapv :name (:variants button))))
        (t/is (= "an instance of its component" (:placedAs button))))
      (let [card (first (-> (find {:query "card"}) :result :matches))]
        (t/is (= ["content"] (:slots card)))
        (t/is (= ["button"] (:uses card)))))
    (t/testing "nothing fits: the agent is told to build by hand"
      (let [out (find {:query "isometric dragon"})]
        (t/is (= [] (-> out :result :matches)))
        (t/is (re-find #"build it by hand with build_tree" (-> out :result :note)))))
    (t/is (re-find #"pass query" (:problem (find {}))))
    (t/is (re-find #"category \"widget\" is not one" (:problem (find {:category "widget"}))))
    (t/testing "the host's own recipes are found too"
      (let [mine (assoc (get recipes "card") :name "ticket" :title "Ticket" :keywords ["boarding pass"])]
        (t/is (= "ticket"
                 (-> (cat/run "find_recipes" (view data) {:query "boarding pass"}
                              (assoc-in options [:recipes "ticket"] mine))
                     :result :matches first :name)))))))

(t/deftest every-built-in-recipe-builds
  (doseq [[nm _] cars/builtin]
    (t/testing nm
      (let [[after out] (build data {:recipe nm :parentId (str frame-id)})]
        (t/is (nil? (:problem out)))
        (t/is (nil? (-> out :result :error)))
        (t/is (some? (shape after (-> out :result :id))))
        (t/is (= frame-id (:parent-id (shape after (-> out :result :id)))))
        (t/is (empty? (errors after)))))))

(t/deftest a-recipe-with-variants-becomes-a-set-and-an-instance
  (let [[after out] (build data {:recipe "button" :params {:label "Save"}
                                 :variant {:Kind "Secondary"} :parentId (str frame-id)})
        result      (:result out)
        main        (first (:mains result))
        container   (shape after (:variantId main))
        members     (filter #(= (:id container) (:variant-id %)) (ctkl/components-seq after))
        instance    (shape after (:id result))
        primary     (main-of after {"Kind" "Primary" "Size" "Medium"})]
    (t/is (= {:recipe "button" :built true :members 6} (dissoc main :variantId)))
    (t/is (ctc/is-variant-container? container))
    (t/is (= :flex (:layout container)))
    (t/is (= 6 (count members)))
    (t/is (= #{"Button"} (set (map :name members))))
    (t/is (= #{{"Kind" "Primary" "Size" "Medium"} {"Kind" "Primary" "Size" "Small"}
               {"Kind" "Secondary" "Size" "Medium"} {"Kind" "Secondary" "Size" "Small"}
               {"Kind" "Ghost" "Size" "Medium"} {"Kind" "Ghost" "Size" "Small"}}
             (set (map (fn [member] (into {} (map (juxt :name :value)) (:variant-properties member)))
                       members))))
    (t/testing "the instance is a copy of the member asked for, with its words"
      (t/is (ctc/instance-head? instance))
      (t/is (not (ctc/main-instance? instance)))
      (t/is (= {"Kind" "Secondary" "Size" "Medium"}
               (into {} (map (juxt :name :value))
                     (:variant-properties (ctkl/get-component after (:component-id instance))))))
      (t/is (= "Save" (words (child after (:id result) "Label")))))
    (t/testing "the mains keep the default words"
      (t/is (some? primary))
      (t/is (= "Button" (words (child after (str (:id primary)) "Label")))))
    (t/is (empty? (errors after)))))

(t/deftest roles-become-tokens-and-everything-is-bound
  (let [[after out] (build data {:recipe "button"})
        primary     (main-of after {"Kind" "Primary" "Size" "Medium"})
        created     (set (-> out :result :tokensCreated))]
    (t/is (contains? created "color.primary"))
    (t/is (contains? created "spacing.lg"))
    (t/is (= created (token-names after)))
    (t/is (some? primary))
    (t/is (= "color.primary" (-> primary :applied-tokens :fill)))
    (t/is (= "radius.md" (-> primary :applied-tokens :r1)))
    (t/is (= "spacing.sm" (-> primary :applied-tokens :row-gap)))
    (t/is (= "spacing.md" (-> primary :applied-tokens :p1)))
    (t/is (= "spacing.lg" (-> primary :applied-tokens :p2)))
    (t/testing "a file's own token of that name is used, and a token named for a role"
      (let [[own _]      (let [out (cato/create-tokens (view data)
                                                       {:tokens [{:name "color.primary" :type "color" :value "#ff0000"}
                                                                 {:name "brand.ink" :type "color" :value "#00ff00"}]}
                                                       options)]
                           [(cfc/process-changes data (:changes out)) out])
            [after out]  (build own {:recipe "button" :roles {"color.on-primary" "brand.ink"}})
            label        (some #(when (and (= "Label" (:name %)) (= "brand.ink" (-> % :applied-tokens :fill))) %)
                               (vals (objects after)))]
        (t/is (not (contains? (set (-> out :result :tokensCreated)) "color.primary")))
        (t/is (nil? (get-in out [:result :roleTokens "color.primary"])))
        (t/is (= {:token "brand.ink" :from "the token you named"}
                 (get-in out [:result :roleTokens "color.on-primary"])))
        (t/is (not (contains? (token-names after) "color.on-primary")))
        (t/is (some? label))))))

(t/deftest a-linked-library-gives-a-role-its-value-and-its-components
  (let [lib-id    (uuid/next)
        lib-page  (uuid/next)
        empty-lib (ctf/make-file-data lib-id lib-page)
        lib-view  (fn [data] {:file-id lib-id :page-id lib-page :data data
                              :objects (get-in data [:pages-index lib-page :objects])
                              :libraries {lib-id {:id lib-id :data data}} :fonts {}})
        commit    (fn [data out] (cfc/process-changes data (:changes out)))
        with-lib  (fn [library input]
                    (let [out (cat/run "build_recipe"
                                       (assoc-in (view data) [:libraries lib-id] {:id lib-id :data library})
                                       input options)]
                      [(commit data out) out]))]
    (t/testing "a token of the role's name in the library: its value, in a token of this file"
      (let [library     (commit empty-lib
                                (cato/create-tokens (lib-view empty-lib)
                                                    {:tokens [{:name "color.primary" :type "color" :value "#aa00aa"}]}
                                                    options))
            [after out] (with-lib library {:recipe "button"})]
        (t/is (contains? (set (-> out :result :tokensCreated)) "color.primary"))
        (t/testing "and the result says where each role's token came from"
          (t/is (= {:token "color.primary" :from "a new token, with the value a linked library gives it now"}
                   (get-in out [:result :roleTokens "color.primary"])))
          (t/is (= ["color.primary"] (keys (get-in out [:result :roleTokens])))))
        (t/is (= "#aa00aa" (:value (get (ctob/get-all-tokens-map (:tokens-lib after)) "color.primary"))))
        (t/is (= "#aa00aa" (-> (main-of after {"Kind" "Primary" "Size" "Medium"}) :fills first :fill-color)))))
    (t/testing "a library that has the component: it is placed from there and not built again"
      (let [library     (commit empty-lib (cat/run "build_recipe" (lib-view empty-lib) {:recipe "button"} options))
            [after out] (with-lib library {:recipe "button" :variant {:Size "Small"}})
            placed      (shape after (-> out :result :id))]
        (t/is (false? (-> out :result :mains first :built)))
        (t/is (empty? (ctkl/components-seq after)))
        (t/is (= lib-id (:component-file placed)))))))

(t/deftest a-main-is-built-once
  (let [[once out1]  (build data {:recipe "button"})
        [twice out2] (build once {:recipe "button" :params {:label "Again"}})]
    (t/is (true? (-> out1 :result :mains first :built)))
    (t/is (false? (-> out2 :result :mains first :built)))
    (t/is (nil? (-> out2 :result :tokensCreated)))
    (t/is (= (count (ctkl/components-seq once)) (count (ctkl/components-seq twice))))
    (t/is (= "Again" (words (child twice (-> out2 :result :id) "Label"))))))

(t/deftest a-plain-recipe-is-built-as-its-tree-with-instances-inside
  (let [[after out] (build data {:recipe "pricing-table" :params {:plans 2 :features 3}})
        result      (:result out)
        root        (shape after (:id result))
        plan-1      (child after (:id result) "Plan 1")
        plan-2      (child after (:id result) "Plan 2")
        choose      (child after (str (:id plan-2)) "Choose")
        component   (fn [shape] (ctkl/get-component after (:component-id shape)))]
    (t/is (= "Pricing" (:name root)))
    (t/is (not (ctc/instance-head? root)))
    (t/testing "what repeats is one component, built for the structure asked, and each turn an instance"
      (t/is (= [{:recipe "button" :built true}
                {:recipe "plan-card" :built true :component "Plan card (features 3)"}]
               (mapv #(select-keys % [:recipe :built :component]) (:mains result))))
      (t/is (ctc/instance-head? plan-1))
      (t/is (ctc/instance-head? plan-2))
      (t/is (= #{"Plan card (features 3)"} (set (map (comp :name component) [plan-1 plan-2]))))
      (t/is (= 3 (count (:shapes (child after (str (:id plan-2)) "Features"))))))
    (t/testing "the turn that differs is a variant"
      (t/is (= [{"Emphasis" "Default"} {"Emphasis" "Featured"}]
               (mapv #(into {} (map (juxt :name :value)) (:variant-properties (component %))) [plan-1 plan-2]))))
    (t/testing "each takes its own words, down to the button inside it"
      (t/is (= "Plan 2" (words (child after (str (:id plan-2)) "Plan name"))))
      (t/is (ctc/instance-head? choose))
      (t/is (= "Choose plan 2" (words (child after (str (:id choose)) "Label")))))
    (t/is (nil? (child after (:id result) "Plan 3")))
    (t/is (= "color.background" (-> root :applied-tokens :fill)))
    (t/is (empty? (:fills (child after (:id result) "Plans"))))
    (t/is (empty? (errors after)))))

(def ^:private banner
  "A component that places another one, and takes a param that changes its
  structure."
  {:recipe 1
   :name "banner"
   :title "Banner"
   :purpose "A line of text with a button, to test with."
   :category "container"
   :params [{:name "message" :type "text" :default "Something happened"}
            {:name "actionLabel" :type "text" :default "Undo"}
            {:name "icon" :type "boolean" :default false}]
   :component true
   :tree {:type "board"
          :name "Banner"
          :layout {:type "flex" :dir "row"}
          :tokens {:fill "color.surface" :padding "spacing.md"}
          :children [{:type "rect" :name "Icon" :when {:param "icon"} :width 16 :height 16}
                     {:type "text" :name "Message" :text "{message}"}
                     {:type "recipe" :name "Action" :use "button"
                      :with {:label {:param "actionLabel"}}
                      :variant {:Kind "Ghost"}}]}})

(t/deftest a-component-inside-a-component-gets-its-words
  (let [options     (assoc-in options [:recipes "banner"] banner)
        [after out] (build data {:recipe "banner" :params {:message "Saved" :actionLabel "View"}} options)
        result      (:result out)
        placed      (shape after (:id result))
        action      (child after (:id result) "Action")]
    (t/is (= ["button" "banner"] (mapv :recipe (:mains result))))
    (t/is (ctc/instance-head? placed))
    (t/is (= "Saved" (words (child after (:id result) "Message"))))
    (t/is (= "View" (words (child after (str (:id action)) "Label"))))
    (t/is (nil? (:notes result)))
    (t/testing "the main keeps the default words"
      (let [main (shape after (-> result :mains second :shapeId))]
        (t/is (ctc/main-instance? main))
        (t/is (= "Something happened" (words (child after (str (:id main)) "Message"))))
        (t/is (= "Undo" (words (child after (str (:id main)) "Label"))))))
    (t/testing "a param that changes structure makes another component, named for it"
      (let [[after out] (build after {:recipe "banner" :params {:icon true :message "Hi"}} options)
            placed      (shape after (-> out :result :id))]
        (t/is (nil? (-> out :result :notes)))
        (t/is (= [{:recipe "button" :built false} {:recipe "banner" :built true :component "Banner (icon true)"}]
                 (mapv #(select-keys % [:recipe :built :component]) (-> out :result :mains))))
        (t/is (= "Banner (icon true)" (:name (ctkl/get-component after (:component-id placed)))))
        (t/is (some? (child after (-> out :result :id) "Icon")))
        (t/is (= "Hi" (words (child after (-> out :result :id) "Message"))))
        (t/testing "and is built once, like any other"
          (let [[again out2] (build after {:recipe "banner" :params {:icon true}} options)]
            (t/is (= [false] (mapv :built (filter #(= "banner" (:recipe %)) (-> out2 :result :mains)))))
            (t/is (= (count (ctkl/components-seq after)) (count (ctkl/components-seq again))))))))
    (t/is (empty? (errors after)))))

(t/deftest a-plain-recipe-names-its-slots-and-what-repeats
  (let [[after out] (build data {:recipe "card" :params {:title "Hello" :action true :actionLabel "Read"}})
        result      (:result out)
        card        (shape after (:id result))
        body        (child after (:id result) "Body")]
    (t/is (not (ctc/instance-head? card)))
    (t/is (= {"content" (str (:id body))} (:slots result)))
    (t/is (= "Hello" (words (child after (:id result) "Title"))))
    (t/is (= "Read" (words (child after (str (:id (child after (:id result) "Action"))) "Label")))))
  (let [[_ out] (build data {:recipe "feature-grid" :params {:features 2}})]
    (t/is (= #{"Features" "Heading" "Grid" "Feature 1" "Feature 2"} (set (keys (-> out :result :ids)))))))

(t/deftest the-design-rules-hold
  (let [[after out] (build data {:recipe "button"} (assoc options :guard {:enforced? true :allowed #{}}))]
    (t/is (nil? (:problem out)))
    (t/is (nil? (-> out :result :error)))
    (t/is (some? (shape after (-> out :result :id))))))

(t/deftest a-build-refused-part-way-says-what-exists
  (let [[own _]     (let [out (cato/create-tokens (view data)
                                                  {:tokens [{:name "gap.wide" :type "spacing" :value "40"}]}
                                                  options)]
                      [(cfc/process-changes data (:changes out)) out])
        [after out] (build own {:recipe "button" :roles {"color.on-primary" "gap.wide"}})
        result      (:result out)]
    (t/is (re-find #"build_recipe at button main.children\[0\]: token \"gap.wide\": .* cannot bind to fill"
                   (:error result)))
    (t/is (re-find #"PARTIAL build" (:note result)))
    (t/is (contains? (set (:tokensCreated result)) "color.primary"))
    (t/is (contains? (token-names after) "color.primary"))
    (t/testing "what it made is in the file"
      (t/is (some #(= "Button / Primary / Medium" (:name %)) (vals (objects after))))
      (t/is (= #{"Button / Primary / Medium" "Label"} (set (keys (:ids result)))))
      (t/is (every? #(some? (shape after %)) (vals (:ids result)))))))

(defn- settle
  "Runs build_recipe as a host that resolves tokens between runs does: it
  knows the tokens it resolved by what they are → `[runs answer]`."
  [data input]
  (let [resolved (volatile! {})]
    (loop [runs 1]
      (let [out (second (build data input (assoc options :resolve-tokens #(get @resolved %))))]
        (if (and (contains? out :pending) (< runs 5))
          (do (vswap! resolved assoc (-> out :pending :tokens) (resolve-tokens (-> out :pending :tokens)))
              (recur (inc runs)))
          [runs out])))))

(t/deftest a-host-that-resolves-tokens-between-runs-settles
  (t/testing "a file with no tokens asks the host for nothing"
    (let [[runs out] (settle data {:recipe "button"})]
      (t/is (= 1 runs))
      (t/is (some? (-> out :result :id)))))
  (t/testing "a file with tokens of its own asks once, and only about those"
    (let [own        (let [out (cato/create-tokens (view data)
                                                   {:tokens [{:name "brand.500" :type "color" :value "#ff0000"}
                                                             {:name "color.primary" :type "color" :value "#00ff00"}]}
                                                   options)]
                       (cfc/process-changes data (:changes out)))
          first-out  (second (build own {:recipe "button"} (assoc options :resolve-tokens (constantly nil))))
          [runs out] (settle own {:recipe "button"})]
      (t/is (= #{"brand.500" "color.primary"} (set (keys (-> first-out :pending :tokens)))))
      (t/is (= 2 runs))
      (t/is (some? (-> out :result :id))))))

(t/deftest what-the-engine-cannot-do-is-the-hosts-to-settle
  (t/is (some? (:fallback (second (build data {:recipe "button"} (dissoc options :measure-text)))))))

(t/deftest the-host-can-hand-in-its-own-recipes
  (let [mine        (assoc-in (get recipes "card") [:tree :name] "Team card")
        [after out] (build data {:recipe "card"} (assoc-in options [:recipes "card"] mine))]
    (t/is (= "Team card" (:name (shape after (-> out :result :id)))))
    (t/is (= (set (keys cars/builtin)) (set (keys (carb/catalog {})))))))

;; --- A board written down as a recipe

(defn- outline
  "What a build makes of a recipe, without its ids: each layer's name, kind,
  layout and sizing, in reading order."
  [data id]
  (let [objects (objects data)]
    (letfn [(walk [id]
              (let [shape (get objects id)
                    kids  (if (and (:layout shape) (not (#{:row-reverse :column-reverse} (:layout-flex-dir shape))))
                            (reverse (:shapes shape))
                            (:shapes shape))]
                (cond-> {:name (:name shape)
                         :type (:type shape)
                         :layout [(:layout shape) (:layout-flex-dir shape) (:layout-align-items shape)]
                         :sizing [(:layout-item-h-sizing shape) (:layout-item-v-sizing shape)]
                         :tokens (:applied-tokens shape)}
                  (cfh/text-shape? shape)  (assoc :words (words shape))
                  ;; what is inside a copy is its component's business
                  (:component-id shape)    (assoc :component (:component-id shape))
                  (and (seq kids) (nil? (:component-id shape))) (assoc :children (mapv walk kids)))))]
      (walk (parse-uuid id)))))

(defn- saved
  [data id about]
  (cars-save/from-board (objects data) {file-id {:id file-id :data data}} (parse-uuid id) about recipes))

(t/deftest a-built-recipe-saved-and-built-again-is-the-same
  (doseq [[nm params] [["card" {:media true :action true :title "Hello"}]
                       ["pricing-table" {:plans 2 :features 2}]
                       ["form" {:fields 2}]
                       ["sidebar" {:items 3}]]]
    (t/testing nm
      (let [[first-data out] (build data {:recipe nm :params params})
            {:keys [recipe notes problem]}
            (saved first-data (-> out :result :id)
                   {:name "mine" :purpose "Saved to test with." :category "container"})
            [again out2] (build first-data {:recipe "mine"} (assoc-in options [:recipes "mine"] recipe))]
        (t/is (nil? problem))
        (t/is (empty? notes))
        (t/is (nil? (-> out2 :result :error)))
        (t/is (= (outline first-data (-> out :result :id))
                 (outline again (-> out2 :result :id))))))))

(t/deftest the-one-who-saves-names-it-and-marks-its-params-and-slots
  (let [[built out] (build data {:recipe "card" :params {:media true :title "Hello"}})
        about       {:name "news-card" :title "News card" :purpose "A card for a news item."
                     :category "container" :keywords ["article"]
                     :params [{:name "headline" :layer "Title" :description "The headline"}
                              {:name "picture" :layer "Media" :type "boolean"}]
                     :slots [{:name "body" :layer "Body"}]}
        {:keys [recipe problem]} (saved built (-> out :result :id) about)
        mine        (assoc-in options [:recipes "news-card"] recipe)]
    (t/is (nil? problem))
    (t/is (= ["news-card" "News card" "container" ["article"]]
             ((juxt :name :title :category :keywords) recipe)))
    (t/is (= [{:name "headline" :description "The headline" :type "text" :default "Hello"}
              {:name "picture" :type "boolean" :default true}]
             (:params recipe)))
    (t/testing "built again, the params do what was marked"
      (let [[after out] (build built {:recipe "news-card" :params {:headline "Extra!" :picture false}} mine)]
        (t/is (= "Extra!" (words (child after (-> out :result :id) "Title"))))
        (t/is (nil? (child after (-> out :result :id) "Media")))
        (t/is (= ["body"] (keys (-> out :result :slots))))))
    (t/testing "what is wrong with a mark is named"
      (let [problem (fn [change] (:problem (saved built (-> out :result :id) (merge about change))))]
        (t/is (re-find #"param \"headline\" names the layer \"Headline\", and there is none"
                       (problem {:params [{:name "headline" :layer "Headline"}]})))
        (t/is (re-find #"layers here: Title, Description" (problem {:params [{:name "x" :layer "Nope"}]})))
        (t/is (re-find #"has type \"number\"" (problem {:params [{:name "x" :layer "Title" :type "number"}]})))
        (t/is (re-find #"slot \"body\" names the layer \"Title\"" (problem {:slots [{:name "body" :layer "Title"}]})))
        (t/is (re-find #"needs a `name` in kebab-case" (problem {:name "News card"})))
        (t/is (re-find #"needs a `purpose`" (problem {:purpose nil})))))))

(t/deftest what-a-recipe-cannot-hold-is-said
  (let [board-id (uuid/next)
        text-id  (uuid/next)
        group-id (uuid/next)
        loose    (assoc-in data [:pages-index page-id :objects]
                           (-> (objects data)
                               (assoc board-id (cts/setup-shape {:id board-id :type :frame :name "Loose"
                                                                 :x 0 :y 0 :width 100 :height 100
                                                                 :parent-id uuid/zero :frame-id uuid/zero
                                                                 :shapes [text-id]})
                                      text-id (cts/setup-shape {:id text-id :type :rect :name "Box"
                                                                :x 0 :y 0 :width 10 :height 10
                                                                :parent-id board-id :frame-id board-id})
                                      group-id (cts/setup-shape {:id group-id :type :group :name "Bunch"
                                                                 :x 0 :y 0 :width 10 :height 10
                                                                 :parent-id uuid/zero :frame-id uuid/zero}))))
        about    {:name "x" :purpose "x" :category "container"}]
    (t/is (re-find #"\"Loose\" has layers and no layout" (:problem (saved loose (str board-id) about))))
    (t/is (re-find #"\"Bunch\" is a group — a recipe is saved from a board" (:problem (saved loose (str group-id) about))))
    (t/is (re-find #"no shape on this page" (:problem (saved loose (str (uuid/next)) about)))))
  (t/testing "a token that is not a role is left out, and the result says so"
    (let [own         (cfc/process-changes
                       data
                       (:changes (cato/create-tokens (view data)
                                                     {:tokens [{:name "brand.500" :type "color" :value "#ff0000"}]}
                                                     options)))
          [built out] (build own {:recipe "empty-state" :roles {"color.surface-muted" "brand.500"}})
          {:keys [recipe notes]} (saved built (-> out :result :id) {:name "x" :purpose "x" :category "section"})]
      (t/is (re-find #"not roles were left out \(brand.500\)" (first notes)))
      (t/is (nil? (:tokens (some #(when (= "Illustration" (:name %)) %) (-> recipe :tree :children))))))))

;; --- Built for a platform

(t/deftest a-profile-is-applied-when-a-recipe-is-built
  (let [[plain out1] (build data {:recipe "top-bar"})
        [ios out2]   (build data {:recipe "top-bar" :profile "ios"})
        action       (fn [data out] (child data (-> out :result :id) "Action"))
        placed       (fn [data shape]
                       (into {} (map (juxt :name :value))
                             (:variant-properties (ctkl/get-component data (:component-id shape)))))]
    (t/is (= 1200 (:width (shape plain (-> out1 :result :id)))))
    (t/is (= 393 (:width (shape ios (-> out2 :result :id)))))
    (t/is (= 4 (count (:shapes (child plain (-> out1 :result :id) "Links")))))
    (t/is (empty? (:shapes (child ios (-> out2 :result :id) "Links"))))
    (t/testing "a board with nothing in it takes no room"
      (t/is (= [1 1] ((juxt :width :height) (child ios (-> out2 :result :id) "Links")))))
    (t/is (= {"Kind" "Primary" "Size" "Small"} (placed plain (action plain out1))))
    (t/is (= {"Kind" "Ghost" "Size" "Small"} (placed ios (action ios out2))))
    (t/testing "what the call says wins over the profile"
      (let [[after out] (build data {:recipe "top-bar" :profile "ios" :params {:links 2}})]
        (t/is (= 2 (count (:shapes (child after (-> out :result :id) "Links")))))))
    (t/testing "a component is placed in the variant the platform asks for"
      (let [[web out]   (build data {:recipe "button" :profile "web"})
            [web2 out2] (build data {:recipe "button" :profile "web" :variant {:Size "Medium"}})]
        (t/is (= {"Kind" "Primary" "Size" "Small"} (placed web (shape web (-> out :result :id)))))
        (t/is (= {"Kind" "Primary" "Size" "Medium"} (placed web2 (shape web2 (-> out2 :result :id)))))))
    (t/testing "a recipe the profile does not name is built as ever"
      (let [[a out-a] (build data {:recipe "empty-state"})
            ;; on the same file, so both place the same button
            [b out-b] (build a {:recipe "empty-state" :profile "ios"})]
        (t/is (= (outline a (-> out-a :result :id)) (outline b (-> out-b :result :id))))))
    (t/is (re-find #"no platform profile named \"windows\" — there are: android, ios, web"
                   (:problem (second (build data {:recipe "top-bar" :profile "windows"})))))))

;; --- A linked library

(t/deftest a-copy-of-a-library-component-takes-the-values-of-the-file-it-is-in
  (let [[library _] (build data {:recipe "button"})
        ;; another file, which links the one the button was built in
        other-id    (uuid/next)
        other       (ctf/make-file-data other-id (uuid/next))
        other-page  (first (:pages other))
        view-of     (fn [data]
                      {:file-id other-id :page-id other-page :data data
                       :objects (get-in data [:pages-index other-page :objects])
                       :libraries {other-id {:id other-id :name "File" :data data}
                                   file-id {:id file-id :name "Library" :data library}}
                       :fonts {}})
        run         (fn [data tool input]
                      (let [out (cat/run tool (view-of data) input options)]
                        [(cfc/process-changes data (:changes out)) out]))
        seen        (car/read-design (view-of other) {})
        button      (some #(when (= "Button" (:name %)) %) (:variants seen))
        [placed out] (run other "create_instance"
                          {:componentId (-> button :members first :componentId) :fileId (str file-id) :x 0 :y 0})
        copy        (fn [data] (get-in data [:pages-index other-page :objects (parse-uuid (-> out :result :shapeId))]))
        fill        (fn [data] (-> (copy data) :fills first :fill-color))]
    (t/testing "the library's variant sets are there to place, with its file"
      (t/is (= {:fileId (str file-id) :library "Library"} (select-keys button [:fileId :library])))
      (t/is (< 1 (count (:members button)))))
    (t/testing "a copy carries the names of the tokens, and the library's values"
      (t/is (= file-id (:component-file (copy placed))))
      (t/is (= "color.primary" (-> (copy placed) :applied-tokens :fill)))
      (t/is (= "#2563eb" (fill placed))))
    (t/testing "the library's tokens can be taken as the file's own, themes and all"
      (let [themed-library (cfc/process-changes library (:changes (cat/run "apply_token_pack" (view library) {:pack "soft" :onExisting "replace"} options)))
            view-with      (fn [data] (assoc-in (view-of data) [:libraries file-id :data] themed-library))
            out            (cat/run "apply_token_pack" (view-with placed) {:library (str file-id)} options)
            taken          (cfc/process-changes placed (:changes out))
            lib            (:tokens-lib taken)]
        (t/is (= {:applied true :fromLibrary true :theme "Light" :pack "Library"}
                 (select-keys (:result out) [:applied :fromLibrary :theme :pack])))
        (t/is (= ["core" "modes/light" "modes/dark"] (mapv ctob/get-name (ctob/get-sets lib))))
        (t/is (= "#6d4aff" (:value (get (ctob/get-tokens-in-active-sets lib) "color.primary"))))
        ;; the copy placed before now has the values the library's tokens have here
        (t/is (= "#6d4aff" (fill taken)))
        (t/is (re-find #"is the fileId of a library LINKED to this file"
                       (:problem (cat/run "apply_token_pack" (view-with placed) {:library (str (uuid/next))} options))))))
    (t/testing "tokens of those names in the file it is in give it their values"
      (let [[themed applied] (run placed "apply_token_pack" {:pack "bold"})
            [dark _]         (run themed "activate_theme" {:name "Dark"})]
        (t/is (pos? (-> applied :result :restyled)))
        (t/is (= "#4f2bff" (fill themed)))
        (t/is (= "#c6ff3d" (fill dark)))
        ;; it is still a copy of the library's component, and not an edited one
        (t/is (= file-id (:component-file (copy dark))))
        (t/is (empty? (:touched (copy dark))))
        (t/is (= 1.0 (-> (cau/capability-usage (get-in dark [:pages-index other-page :objects]) [] other-id)
                         :shares :libraryReuse)))))))

;; --- The measure recipes are held against

(t/deftest what-a-recipe-builds-uses-what-penpot-offers
  (let [[built out] (build data {:recipe "pricing-table"})
        usage       (cau/capability-usage (objects built) [(parse-uuid (-> out :result :id))])]
    (t/is (= 1.0 (-> usage :shares :autoLayout)))
    (t/is (= 1.0 (-> usage :shares :colorTokenBound)))
    (t/is (< 0.1 (-> usage :shares :components) 1))
    (t/is (< 0.9 (-> usage :shares :tokenBound)))
    (t/testing "its copies are of the file's own components, not of a library's"
      (let [root  [(parse-uuid (-> out :result :id))]
            own   (cau/capability-usage (objects built) root (:id built))
            ;; the same page, read as a file that only links the one the components are in
            other (cau/capability-usage (objects built) root (uuid/next))]
        (t/is (nil? (-> usage :shares :libraryReuse)))
        (t/is (pos? (-> own :counts :copies)))
        (t/is (= 0.0 (-> own :shares :libraryReuse)))
        (t/is (= 1.0 (-> other :shares :libraryReuse))))))
  (t/testing "and all of what a design system owns comes from a role"
    (let [[built out] (build data {:recipe "pricing-table"})
          system      (cau/system-usage (objects built) [(parse-uuid (-> out :result :id))])]
      (doseq [kind [:color :typeSize :typeface :spacing :radius]]
        (t/is (pos? (get-in system [:counts kind :with])) kind)
        (t/is (= 1.0 (get-in system [:shares kind])) kind))
      (t/is (= 1.0 (-> system :shares :restyle)))
      ;; a token that is not a role is a token, and not one a pack reaches
      (let [id     (parse-uuid (-> out :result :id))
            custom (update-in (objects built) [id :applied-tokens] assoc :fill "brand.bg")
            usage  (cau/system-usage custom [id])]
        (t/is (= 1.0 (-> usage :shares :color)))
        (t/is (> 1.0 (-> usage :shares :restyle))))))
  (t/testing "boxes drawn by hand use none of it"
    (let [board-id (uuid/next)
          rect-id  (uuid/next)
          drawn    (-> (objects data)
                       (assoc board-id (cts/setup-shape {:id board-id :type :frame :name "Drawn"
                                                         :x 0 :y 0 :width 100 :height 100
                                                         :parent-id uuid/zero :frame-id uuid/zero
                                                         :shapes [rect-id]})
                              rect-id (cts/setup-shape {:id rect-id :type :rect :name "Box"
                                                        :x 0 :y 0 :width 10 :height 10
                                                        :parent-id board-id :frame-id board-id})))
          usage    (cau/capability-usage drawn [board-id])]
      (t/is (= {:layers 2 :insideABoard 1 :inAutoLayout 0 :tokenBound 0
                :withAColor 2 :colorTokenBound 0 :inComponents 0 :copies 0 :libraryCopies 0}
               (:counts usage)))
      (t/is (= {:autoLayout 0.0 :tokenBound 0.0 :colorTokenBound 0.0 :components 0.0 :libraryReuse nil}
               (:shares usage)))
      (t/is (= {:color {:with 2 :bound 0 :roleBound 0} :typeSize {:with 0 :bound 0 :roleBound 0}
                :typeface {:with 0 :bound 0 :roleBound 0}
                :spacing {:with 0 :bound 0 :roleBound 0} :radius {:with 0 :bound 0 :roleBound 0}}
               (:counts (cau/system-usage drawn [board-id]))))
      (t/is (= 0.0 (-> (cau/system-usage drawn [board-id]) :shares :restyle)))))
  (t/testing "nothing to count gives no share"
    (t/is (= {:autoLayout nil :tokenBound nil :colorTokenBound nil :components nil :libraryReuse nil}
             (:shares (cau/capability-usage {} []))))))

;; --- Content in the same call, and what is left to replace

(t/deftest the-words-are-set-in-the-call-that-builds
  (let [[after out] (build data {:recipe "pricing-table"
                                 :params {:plans 2 :features 2}
                                 :texts {"Heading" "Plans for every team"
                                         "Plan 2 / Plan name" "Team"
                                         "Plan 2 / Price" "$12"
                                         "Plan 2 / Feature 1" "Unlimited projects"
                                         "Plan 1 / Choose" "Start free"
                                         "Plan 2 / Choose / Label" "Choose Team"
                                         "Plan 9 / Price" "nowhere"}})
        ids         (-> out :result :ids)
        said        (fn [key & path]
                      (words (reduce (fn [shape nm] (child after (str (:id shape)) nm))
                                     (shape after (get ids key))
                                     path)))]
    (t/is (= "Plans for every team" (said "Heading")))
    (t/testing "inside an instance, by the path of layer names from it"
      (t/is (= "Team" (said "Plan 2" "Plan name")))
      (t/is (= "$12" (said "Plan 2" "Price")))
      (t/is (= "$0" (said "Plan 1" "Price")))
      (t/is (= "Unlimited projects" (said "Plan 2" "Feature 1"))))
    (t/testing "a layer that places a recipe takes the words of its one text"
      (t/is (= "Start free" (said "Plan 1" "Choose" "Label")))
      (t/is (= "Choose Team" (said "Plan 2" "Choose" "Label"))))
    (t/testing "a key that names no text is said, and the rest is built"
      (t/is (= ["Plan 9 / Price"] (-> out :result :textsNotSet :keys)))
      (t/is (some? (-> out :result :id)))))
  (t/testing "keys as they arrive from JSON, as keywords"
    (let [[after out] (build data {:recipe "pricing-table" :params {:plans 2 :features 1}
                                   :texts {(keyword "Plan 2 / Price") "$12"}})
          plan-2      (shape after (get (-> out :result :ids) "Plan 2"))]
      (t/is (nil? (-> out :result :textsNotSet)))
      (t/is (= "$12" (words (child after (str (:id plan-2)) "Price"))))))
  (t/testing "on an instance, by the layer's name"
    (let [[after out] (build data {:recipe "button" :texts {:Label "Go"}})]
      (t/is (= "Go" (words (child after (-> out :result :id) "Label"))))))
  (t/is (re-find #"texts is not a map" (:problem (second (build data {:recipe "button" :texts ["Go"]}))))))

(t/deftest what-stands-for-something-else-is-named
  (let [[_ hero]    (build data {:recipe "hero"})
        [_ sidebar] (build data {:recipe "sidebar" :params {:items 2}})
        [_ item]    (build data {:recipe "list-item"})]
    (t/is (= {"image" ["Media"]} (-> hero :result :placeholders)))
    (t/is (= {"icon" ["Item 1 / Icon" "Item 2 / Icon"]} (-> sidebar :result :placeholders)))
    (t/testing "inside a component, by layer name on its main"
      (t/is (= {"icon" ["Chevron"]} (-> item :result :placeholders)))
      (t/is (= ["Chevron"] (:placeholders (some #(when (= "list-item" (:recipe %)) %) (-> item :result :mains))))))
    (t/is (re-find #"this is the start, not the result" (-> hero :result :note))))
  (t/testing "find_recipes says what each text goes by, and what is a placeholder"
    (let [found (fn [nm] (first (-> (cat/run "find_recipes" (view data) {:query nm} options) :result :matches)))]
      (t/is (= ["Heading" "Plan {index} / Plan name" "Plan {index} / Price"
                "Plan {index} / Feature {index}" "Plan {index} / Choose"]
               (:texts (found "pricing-table"))))
      (t/is (= ["Brand" "Item {index}" "Footer text"] (:texts (found "sidebar"))))
      (t/is (= ["Label"] (:texts (found "button"))))
      (t/is (= ["Media"] (:placeholders (found "hero")))))))

(t/deftest many-texts-are-set-in-one-call
  (let [[built out] (build data {:recipe "hero"})
        ids         (-> out :result :ids)
        run         (fn [input] (cat/run "set_texts" (view built) input options))
        answer      (run {:texts [{:shapeId (get ids "Heading") :text "Ship faster"}
                                  {:shapeId (get ids "Subheading") :text "With less noise."}]})
        after       (cfc/process-changes built (:changes answer))]
    (t/is (= 2 (-> answer :result :set)))
    (t/is (= "Ship faster" (words (get (objects after) (parse-uuid (get ids "Heading"))))))
    (t/is (= "With less noise." (words (get (objects after) (parse-uuid (get ids "Subheading"))))))
    (t/testing "all or nothing"
      (let [bad (run {:texts [{:shapeId (get ids "Heading") :text "x"}
                              {:shapeId (get ids "Actions") :text "y"}]})]
        (t/is (re-find #"set_texts: texts\[1\]: .*is a frame, not a text shape" (:problem bad)))
        (t/is (nil? (:changes bad)))))
    (t/is (re-find #"pass texts" (:problem (run {}))))))

;; --- Real icons where placeholders are

(def ^:private icon-options
  (assoc options
         :parse-svg (fn [body]
                      (when (= "<svg/>" body)
                        {:tag :svg :attrs {:width "24" :height "24" :viewBox "0 0 24 24"}
                         :content [{:tag :path :attrs {:d "M0 0L10 10" :stroke "#000000"} :content []}]}))
         :resources (into {}
                          (map (fn [icon] [(cam/icon-svg-url icon (cam/icon-size 20)) {:status 200 :body "<svg/>"}]))
                          ["lucide:house" "lucide:folder"])))

(defn- set-icons
  [data input]
  (let [out (cat/run "set_icons" (view data) input icon-options)]
    [(if (seq (:changes out)) (cfc/process-changes data (:changes out)) data) out]))

(t/deftest an-instance-that-stands-for-an-icon-is-swapped-for-a-component-of-it
  (let [[built out]  (build data {:recipe "sidebar" :params {:items 2}})
        ids          (-> out :result :ids)
        [after done] (set-icons built {:icons [{:shapeId (get ids "Item 1") :layer "Icon" :icon "lucide:house"}
                                               {:shapeId (get ids "Item 2") :layer "Icon" :icon "lucide:folder"}]})
        icons        (filter #(= "Icon" (:path %)) (ctkl/components-seq after))
        in-item      (fn [key] (some #(when (contains? (set (map :id icons)) (:component-id %)) %)
                                     (map #(get (objects after) %)
                                          (:shapes (shape after (get ids key))))))]
    (t/is (= {"icon" ["Item 1 / Icon" "Item 2 / Icon"]} (-> out :result :placeholders)))
    (t/is (nil? (:problem done)))
    (t/is (nil? (-> done :result :error)))
    (t/is (= ["Icon / house" "Icon / folder"] (-> done :result :componentsCreated)))
    (t/is (= #{"house" "folder"} (set (map :name icons))))
    (t/testing "each item now holds an instance of its own icon"
      (t/is (= "house" (:name (ctkl/get-component after (:component-id (in-item "Item 1"))))))
      (t/is (= "folder" (:name (ctkl/get-component after (:component-id (in-item "Item 2")))))))
    (t/testing "and its strokes take the color token the placeholder had: the current item's is another"
      (let [drawn (fn [key] (->> (cfh/get-children-ids (objects after) (:id (in-item key)))
                                 (map #(get (objects after) %))
                                 (filter #(= :path (:type %)))
                                 (first)))]
        (t/is (= "color.primary" (-> (drawn "Item 1") :applied-tokens :stroke-color)))
        (t/is (= "color.text-muted" (-> (drawn "Item 2") :applied-tokens :stroke-color)))))
    (t/testing "the item is still an instance of the same component"
      (t/is (= (:component-id (shape built (get ids "Item 1")))
               (:component-id (shape after (get ids "Item 1"))))))
    (t/testing "an icon the file has is used again, not made again"
      (let [[built2 out2]  (build after {:recipe "sidebar" :params {:items 1}})
            [again done2]  (set-icons built2 {:icons [{:shapeId (get (-> out2 :result :ids) "Item 1")
                                                       :layer "Icon" :icon "lucide:house"}]})]
        (t/is (nil? (-> done2 :result :componentsCreated)))
        (t/is (= 2 (count (filter #(= "Icon" (:path %)) (ctkl/components-seq again)))))))
    (t/is (empty? (errors after)))))

(t/deftest a-text-that-fills-its-parent-wraps-to-it
  (let [[after out] (build data {:recipe "table-row"})
        cell        (child after (-> out :result :id) "Cell 1")
        [after2 o2] (build data {:recipe "button"})]
    (t/is (= :auto-height (:grow-type cell)))
    (t/testing "one that hugs keeps growing with its words"
      (t/is (= :auto-width (:grow-type (child after2 (-> o2 :result :id) "Label")))))))

(t/deftest a-plain-box-that-stands-for-an-icon-is-replaced-in-its-place
  (let [box-id      (uuid/next)
        with-box    (assoc-in data [:pages-index page-id :objects]
                              (-> (objects data)
                                  (assoc box-id (cts/setup-shape {:id box-id :type :rect :name "Logo mark"
                                                                  :x 0 :y 0 :width 20 :height 20
                                                                  :parent-id frame-id :frame-id frame-id}))
                                  (update-in [frame-id :shapes] conj box-id)))
        [after out] (set-icons with-box {:icons [{:shapeId (str box-id) :icon "lucide:house"}]})
        placed      (shape after (-> out :result :set first :id))]
    (t/is (nil? (:problem out)))
    (t/is (nil? (get (objects after) box-id)))
    (t/is (= "Logo mark" (:name placed)))
    (t/is (= frame-id (:parent-id placed)))
    (t/is (nil? (-> out :result :componentsCreated))))
  (t/testing "what is wrong is named before anything changes"
    (let [problem (fn [input] (:problem (second (set-icons data input))))]
      (t/is (re-find #"pass icons" (problem {})))
      (t/is (re-find #"icons\[0\]: .*prefix:name" (problem {:icons [{:shapeId (str frame-id) :icon "house"}]})))
      (t/is (re-find #"icons\[0\]: no shape with that id" (problem {:icons [{:shapeId (str (uuid/next)) :icon "lucide:house"}]})))))
  (t/is (some? (:fallback (second (set-icons data {:icons [{:shapeId (str frame-id) :icon "lucide:star"}]}))))))
