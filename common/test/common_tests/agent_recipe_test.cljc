;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-recipe-test
  "The recipe format: the built-in recipes are valid, and a recipe that is not
  is told what is wrong with it and where."
  (:require
   [app.common.agent.recipe :as car]
   [app.common.agent.recipes :as cars]
   [app.common.agent.recipes.profiles :as profiles]
   [clojure.test :as t]))

(def ^:private minimal
  {:recipe 1
   :name "thing"
   :title "Thing"
   :purpose "A thing to test with."
   :category "control"
   :tree {:type "board" :name "Thing"}})

(defn- problem
  "The problem of `minimal` once `f` has changed it."
  [f & args]
  (car/problem (apply f minimal args) cars/builtin))

(defn- with-child
  [recipe child]
  (assoc-in recipe [:tree :children] [child]))

(t/deftest the-built-in-recipes-are-valid
  (doseq [[nm recipe] cars/builtin]
    (t/is (= nm (:name recipe)))
    (t/is (nil? (car/problem recipe cars/builtin)))))

(t/deftest the-catalog-is-the-one-that-was-planned
  (let [planned {"control"    ["button" "text-field" "select" "choice-control" "badge" "avatar"]
                 "container"  ["card" "list-item" "tabs" "alert" "modal"]
                 "navigation" ["top-bar" "sidebar" "bottom-tab-bar"]
                 "section"    ["hero" "feature-grid" "pricing-table" "form" "data-table"
                               "stat-cards" "footer" "empty-state"]}
        by-kind (update-vals (group-by :category cars/all) #(mapv :name %))]
    (t/testing "the 22 that were planned, first in each category"
      (doseq [[category names] planned]
        (t/is (= names (vec (take (count names) (get by-kind category)))))))
    (t/testing "and the parts they are made of, each a component of its own"
      (doseq [nm ["icon" "tab" "plan-card" "feature-item" "stat-card" "table-row"
                  "nav-link" "nav-item" "tab-bar-item" "footer-group"]]
        (let [part (get cars/builtin nm)]
          (t/is (or (seq (:variants part)) (true? (:component part))) nm))))
    (t/testing "over a hundred in all, each with a name of its own"
      (t/is (<= 100 (count cars/all)))
      (t/is (= (count cars/all) (count cars/builtin))))))

(t/deftest what-repeats-is-a-component-and-the-current-one-a-variant
  (doseq [[nm part] {"sidebar" "nav-item" "top-bar" "nav-link" "bottom-tab-bar" "tab-bar-item"
                     "tabs" "tab" "pricing-table" "plan-card" "feature-grid" "feature-item"
                     "stat-cards" "stat-card" "data-table" "table-row" "footer" "footer-group"}]
    (t/testing nm
      (let [repeated (filter :repeat (tree-seq map? :children (:tree (get cars/builtin nm))))]
        (t/is (seq repeated))
        (t/is (every? #(= "recipe" (:type %)) repeated))
        (t/is (contains? (set (map :use repeated)) part)))))
  (doseq [nm ["sidebar" "top-bar" "bottom-tab-bar" "tabs"]]
    (t/is (= [{:at 1 :set {:variant {:State "Current"}}}]
             (:vary (first (filter :repeat (tree-seq map? :children (:tree (get cars/builtin nm)))))))
          nm)))

(defn- tree-nodes
  [recipe]
  (tree-seq map? :children (:tree recipe)))

(t/deftest every-recipe-is-exemplary
  (doseq [recipe cars/all]
    (t/testing (:name recipe)
      (t/testing "auto-layout: every board with children lays them out"
        (t/is (every? #(some? (:layout %))
                      (filter #(and (= "board" (:type %)) (seq (:children %))) (tree-nodes recipe)))))
      (t/testing "token roles: every shape and text takes its color from a role, or has none"
        (t/is (every? #(contains? (:tokens %) :fill)
                      (filter #(contains? #{"rect" "ellipse" "text"} (:type %)) (tree-nodes recipe)))))
      (t/testing "no raw color anywhere"
        (t/is (nil? (re-find #"#[0-9a-fA-F]{3,8}\b|rgba?\(" (pr-str recipe))))))))

(t/deftest a-control-is-a-component-or-is-made-of-them
  (doseq [recipe (filter #(= "control" (:category %)) cars/all)]
    (t/is (or (seq (:variants recipe))
              (true? (:component recipe))
              (seq (car/uses recipe)))
          (:name recipe))))

(t/deftest sections-use-the-controls-they-show
  (let [uses (into {} (map (juxt :name car/uses)) cars/all)]
    (t/is (= ["button"] (get uses "hero")))
    (t/is (= ["feature-item"] (get uses "feature-grid")))
    (t/is (= ["plan-card"] (get uses "pricing-table")))
    (t/is (= ["text-field" "button"] (get uses "form")))
    (t/is (= ["table-row"] (get uses "data-table")))
    (t/is (= ["stat-card"] (get uses "stat-cards")))
    (t/is (= ["footer-group"] (get uses "footer")))))

(t/deftest a-valid-recipe-has-no-problem
  (t/is (nil? (car/problem minimal))))

(t/deftest metadata-problems-are-named
  (t/is (= "recipe: not a recipe object" (car/problem "button")))
  (t/is (re-find #"format version read here is 1" (problem assoc :recipe 2)))
  (t/is (re-find #"\"script\", which is not part of the format" (problem assoc :script "alert(1)")))
  (t/is (re-find #"`name` in kebab-case" (problem assoc :name "My Thing")))
  (t/is (re-find #"recipe \"thing\": needs a `title`" (problem dissoc :title)))
  (t/is (re-find #"needs a `purpose`" (problem assoc :purpose "")))
  (t/is (re-find #"`category`: one of container, control, navigation, screen, section"
                 (problem assoc :category "widget")))
  (t/is (re-find #"keywords is not a list of words" (problem assoc :keywords "a b"))))

(t/deftest param-problems-are-named
  (t/is (re-find #"params is not a list" (problem assoc :params {})))
  (t/is (re-find #"params\[0\] has type \"color\"" (problem assoc :params [{:name "a" :type "color" :default "#fff"}])))
  (t/is (re-find #"params\[0\] needs a `default`" (problem assoc :params [{:name "a" :type "text"}])))
  (t/is (re-find #"default that does not fit: \"a\" takes one of: x, y"
                 (problem assoc :params [{:name "a" :type "choice" :options ["x" "y"] :default "z"}])))
  (t/is (re-find #"default that does not fit: \"n\" is at most 4"
                 (problem assoc :params [{:name "n" :type "number" :min 1 :max 4 :default 5}])))
  (t/is (re-find #"`options`, which only a choice takes"
                 (problem assoc :params [{:name "a" :type "text" :default "" :options ["x"]}])))
  (t/is (re-find #"named \"index\"" (problem assoc :params [{:name "index" :type "number" :default 1}])))
  (t/is (re-find #"two called \"a\""
                 (problem assoc :params [{:name "a" :type "text" :default ""}
                                         {:name "a" :type "boolean" :default true}]))))

(t/deftest variant-problems-are-named
  (t/is (re-find #"variants\[0\] needs `values`: two or more"
                 (problem assoc :variants [{:name "Kind" :values ["Only"]}])))
  (t/is (re-find #"`name` with no \"/\"" (problem assoc :variants [{:name "A/B" :values ["x" "y"]}])))
  (t/is (re-find #"two axes with the same name"
                 (problem assoc :variants [{:name "A" :values ["x" "y"]} {:name "A" :values ["x" "y"]}])))
  (t/is (re-find #"more than 24 members"
                 (problem assoc :variants [{:name "A" :values ["1" "2" "3"]}
                                           {:name "B" :values ["1" "2" "3"]}
                                           {:name "C" :values ["1" "2" "3"]}])))
  (t/is (re-find #"cannot say component: false"
                 (problem assoc :component false :variants [{:name "A" :values ["x" "y"]}])))
  (t/is (re-find #"tree.vary\[0\] has `on`, but the recipe has no variants"
                 (problem assoc-in [:tree :vary] [{:on {:A "x"} :set {:width 10}}])))
  (t/is (re-find #"vary\[0\] has `at` outside a repeated node"
                 (problem with-child {:type "rect" :vary [{:at 1 :set {:width 10}}]})))
  (t/is (re-find #"vary\[0\] needs one of `on`"
                 (problem with-child {:type "rect" :repeat 2 :vary [{:set {:width 10}}]})))
  (t/is (nil? (problem with-child {:type "rect" :repeat 3 :vary [{:at 2 :set {:width 10}}]})))
  (t/is (= [nil 10 nil]
           (mapv :width (:children (car/expand (with-child minimal {:type "rect" :repeat 3
                                                                    :vary [{:at 2 :set {:width 10}}]})
                                               nil nil)))))
  (let [varied (assoc minimal :variants [{:name "Kind" :values ["Primary" "Ghost"]}])]
    (t/is (nil? (car/problem (assoc-in varied [:tree :vary]
                                       [{:on {:Kind "Ghost"} :set {:tokens {:fill "color.surface"}}}]))))
    (t/is (re-find #"vary\[0\].on names \"Size\", which is not a variant axis"
                   (car/problem (assoc-in varied [:tree :vary] [{:on {:Size "S"} :set {:width 1}}]))))
    (t/is (re-find #"vary\[0\].on.Kind is not one of: Primary, Ghost"
                   (car/problem (assoc-in varied [:tree :vary] [{:on {:Kind "Big"} :set {:width 1}}]))))
    (t/is (re-find #"vary\[0\].set has \"children\""
                   (car/problem (assoc-in varied [:tree :vary] [{:on {:Kind "Ghost"} :set {:children []}}]))))
    (t/is (re-find #"when needs `is`: one of Primary, Ghost"
                   (car/problem (with-child varied {:type "rect" :when {:variant "Kind"}}))))))

(t/deftest tree-problems-are-named
  (t/is (re-find #"tree is missing" (problem dissoc :tree)))
  (t/is (re-find #"tree is not a board" (problem assoc :tree {:type "text" :text "x"})))
  (t/is (re-find #"`when` or `repeat`, which the root cannot take" (problem assoc-in [:tree :repeat] 2)))
  (t/is (re-find #"tree.children\[0\] has type \"star\"" (problem with-child {:type "star"})))
  (t/is (re-find #"tree.children\[0\] has \"x\", which is not part of the format"
                 (problem with-child {:type "rect" :x 10})))
  (t/is (re-find #"tree.children\[0\] has \"children\"" (problem with-child {:type "rect" :children []})))
  (t/is (re-find #"is a text and needs `text`" (problem with-child {:type "text"})))
  (t/is (re-find #"url is neither an absolute" (problem with-child {:type "image" :url "cat.png"})))
  (t/is (re-find #"placeholder is not one of: icon, image" (problem with-child {:type "rect" :placeholder "logo"})))
  (t/is (re-find #"has \"placeholder\"" (problem with-child {:type "text" :text "x" :placeholder "icon"})))
  (t/is (re-find #"tree.layout needs `type`: flex or grid" (problem assoc-in [:tree :layout] {:dir "row"})))
  (t/is (re-find #"tree.layout has \"gap\"" (problem assoc-in [:tree :layout] {:type "flex" :gap 4})))
  (t/is (re-find #"tree.children\[0\].width is not a number above zero"
                 (problem with-child {:type "rect" :width "wide"})))
  (t/is (re-find #"has a slot and is a component"
                 (problem assoc :component true :tree {:type "board" :slot "body"})))
  (t/is (re-find #"two slots called \"body\""
                 (problem assoc-in [:tree :children] [{:type "board" :slot "body"} {:type "board" :slot "body"}])))
  (t/is (re-find #"deeper than 6 levels"
                 (problem assoc :tree (reduce (fn [child _] {:type "board" :children [child]})
                                              {:type "rect"}
                                              (range 6)))))
  (t/is (re-find #"more than 80 nodes"
                 (problem assoc-in [:tree :children] (vec (repeat 80 {:type "rect"}))))))

(t/deftest a-recipe-names-roles-and-never-values
  (t/is (re-find #"tree has \"fill\", which is not part of the format" (problem assoc-in [:tree :fill] "#ff0000")))
  (t/is (re-find #"tree.tokens.fill names \"#ff0000\", which is not a role"
                 (problem assoc-in [:tree :tokens] {:fill "#ff0000"})))
  (t/is (re-find #"tree.tokens.fill cannot take \"spacing.md\", a spacing role"
                 (problem assoc-in [:tree :tokens] {:fill "spacing.md"})))
  (t/is (re-find #"tree.tokens.colour is not a property a token binds to"
                 (problem assoc-in [:tree :tokens] {:colour "color.text"})))
  (t/is (nil? (problem assoc-in [:tree :tokens]
                       {:fill "color.surface" :strokeColor "color.border" :strokeWidth "border-width.sm"
                        :borderRadius "radius.md" :padding "spacing.md" :gap "spacing.sm"
                        :paddingX "spacing.lg" :paddingY "spacing.xs" :rowGap "spacing.sm"}))))

(t/deftest the-roles-are-one-vocabulary-of-colour-type-spacing-radius-and-shadow
  ;; every role is of a known kind, and of that kind's token type
  (doseq [[role {:keys [type]}] car/roles]
    (t/is (= type (get car/role-kinds (car/role-kind role))) role))
  ;; colour, typography (family, size, weight), spacing, radius and shadow
  (t/is (= #{"color" "spacing" "radius" "border-width" "font-size" "font-weight" "font-family" "shadow"}
           (into #{} (map car/role-kind) (keys car/roles))))
  (let [named (fn [recipe]
                (->> (tree-seq map? #(filter map? (:children %)) (:tree recipe))
                     (mapcat (fn [node] (concat (vals (:tokens node))
                                                (mapcat #(vals (get-in % [:set :tokens])) (:vary node)))))
                     (set)))]
    ;; the three example recipes name roles and nothing else
    (doseq [recipe [cars/button cars/card cars/pricing-table]]
      (t/is (seq (named recipe)))
      (t/is (every? #(contains? car/roles %) (named recipe)) (:name recipe)))
    ;; a text names its family, and what floats casts a shadow
    (t/is (contains? (named cars/button) "font-family.body"))
    (t/is (contains? (named cars/pricing-table) "font-family.heading"))
    (t/is (contains? (named cars/card) "shadow.sm"))
    ;; across the catalog, every kind of role is used
    (t/is (= (set (keys car/role-kinds))
             (into #{} (comp (mapcat named) (map car/role-kind)) cars/all))))
  (t/is (re-find #"tree.tokens.shadow cannot take \"color.text\", a color role"
                 (problem assoc-in [:tree :tokens] {:shadow "color.text"})))
  (t/is (nil? (problem assoc-in [:tree :tokens] {:shadow "shadow.md"}))))

(t/deftest param-uses-are-checked
  (let [recipe (assoc minimal :params [{:name "label" :type "text" :default "Hi"}
                                       {:name "wide" :type "boolean" :default false}
                                       {:name "count" :type "number" :default 2 :min 1 :max 6}
                                       {:name "size" :type "number" :default 2}
                                       {:name "tone" :type "choice" :options ["calm" "loud"] :default "calm"}])
        check  (fn [child] (car/problem (with-child recipe child) cars/builtin))]
    (t/is (nil? (check {:type "text" :text "{label} {index}" :repeat {:param "count"} :when {:param "wide"}})))
    (t/is (nil? (check {:type "rect" :width {:param "size"} :when {:param "tone" :is "loud"}})))
    (t/is (re-find #"text uses \{title\}, which is not a param" (check {:type "text" :text "{title}"})))
    (t/is (re-find #"uses \{index\} outside a repeated node" (check {:type "text" :text "{index}"})))
    (t/is (re-find #"width needs a number, and \"label\" is a text" (check {:type "rect" :width {:param "label"}})))
    (t/is (re-find #"when needs `is`, as \"tone\" is not a boolean" (check {:type "rect" :when {:param "tone"}})))
    (t/is (re-find #"can never hold: \"tone\" takes one of: calm, loud"
                   (check {:type "rect" :when {:param "tone" :is "soft"}})))
    (t/is (re-find #"repeat needs a number param with a whole `min`" (check {:type "rect" :repeat {:param "size"}})))
    (t/is (re-find #"repeat is not a whole number from 1 to 12" (check {:type "rect" :repeat 40})))))

(t/deftest uses-of-other-recipes-are-checked
  (let [use (fn [node] (problem with-child (merge {:type "recipe" :use "button"} node)))]
    (t/is (nil? (use {:with {:label "Go"} :variant {:Kind "Ghost" :Size "Small"}})))
    (t/is (re-find #"needs `use`: the name of a recipe" (use {:use nil})))
    (t/is (re-find #"uses \"buton\", and there is no recipe of that name" (use {:use "buton"})))
    (t/is (re-find #"with.text is not a param of \"button\" — it takes: label" (use {:with {:text "Go"}})))
    (t/is (re-find #"with.label: \"label\" takes text" (use {:with {:label 3}})))
    (t/is (re-find #"variant.Tone is not a variant axis of \"button\"" (use {:variant {:Tone "Calm"}})))
    (t/is (re-find #"variant.Kind is not one of: Primary, Secondary, Ghost" (use {:variant {:Kind "Loud"}})))
    (t/testing "a placed recipe may differ at a turn: its variant, what it is handed"
      (t/is (nil? (problem with-child {:type "recipe" :use "button" :repeat 3
                                       :vary [{:at 1 :set {:variant {:Kind "Ghost"}}}]})))
      (t/is (re-find #"vary\[0\].set has \"tokens\""
                     (problem with-child {:type "recipe" :use "button" :repeat 3
                                          :vary [{:at 1 :set {:tokens {:fill "color.text"}}}]})))
      (t/is (= [{"Kind" "Ghost"} nil nil]
               (mapv :variant (:children (car/expand (with-child minimal {:type "recipe" :use "button" :repeat 3
                                                                          :vary [{:at 1 :set {:variant {:Kind "Ghost"}}}]})
                                                     nil nil))))))
    (t/is (re-find #"has \"width\"" (use {:width 10}))))
  (t/testing "without a lookup only the shape of the use is checked"
    (t/is (nil? (car/problem (with-child minimal {:type "recipe" :use "anything" :with {:a 1}})))))
  (t/testing "a recipe cannot come back to itself"
    (let [a      (-> minimal (assoc :name "a") (with-child {:type "recipe" :use "b"}))
          b      (-> minimal (assoc :name "b") (with-child {:type "recipe" :use "a"}))
          lookup {"a" a "b" b}]
      (t/is (re-find #"recipe \"a\": uses itself: a → b → a" (car/problem a lookup)))
      (t/is (re-find #"uses the recipe it is part of"
                     (car/problem (with-child a {:type "recipe" :use "a"}) lookup))))))

;; --- What a recipe comes to

(defn- named
  [tree nm]
  (some #(when (= nm (:name %)) %) (tree-seq map? :children tree)))

(t/deftest what-a-recipe-is-handed-is-checked
  (t/is (nil? (car/arguments-problem cars/button {:label "Save"})))
  (t/is (nil? (car/arguments-problem cars/button nil)))
  (t/is (re-find #"\"text\" is not a param of \"button\" — it takes: label"
                 (car/arguments-problem cars/button {:text "Save"})))
  (t/is (re-find #"\"label\" takes text" (car/arguments-problem cars/button {"label" 3})))
  (t/is (re-find #"\"plans\" is at most 4" (car/arguments-problem cars/pricing-table {:plans 9})))
  (t/is (re-find #"\"plans\" takes a whole number" (car/arguments-problem cars/pricing-table {:plans 2.5})))
  (t/is (nil? (car/member-problem cars/button {:Kind "Ghost"})))
  (t/is (re-find #"\"Tone\" is not a variant axis of \"button\" — it has: Kind, Size"
                 (car/member-problem cars/button {:Tone "Calm"})))
  (t/is (re-find #"\"Kind\" is one of: Primary, Secondary, Ghost"
                 (car/member-problem cars/button {"Kind" "Loud"})))
  (t/is (re-find #"has no variants" (car/member-problem cars/card {:Kind "Primary"}))))

(t/deftest a-recipe-has-a-member-per-combination
  (t/is (= [{}] (car/members cars/card)))
  (t/is (= [{"Kind" "Primary" "Size" "Medium"} {"Kind" "Primary" "Size" "Small"}
            {"Kind" "Secondary" "Size" "Medium"} {"Kind" "Secondary" "Size" "Small"}
            {"Kind" "Ghost" "Size" "Medium"} {"Kind" "Ghost" "Size" "Small"}]
           (car/members cars/button)))
  (t/is (= {"Kind" "Ghost" "Size" "Medium"} (car/member cars/button {:Kind "Ghost"}))))

(t/deftest a-member-is-the-tree-with-what-varies
  (let [primary (car/expand cars/button {:label "Save"} nil)
        small   (car/expand cars/button nil {:Kind "Secondary" :Size "Small"})]
    (t/is (= "Save" (:text (named primary "Label"))))
    (t/is (= "color.primary" (-> primary :tokens :fill)))
    (t/is (= "spacing.lg" (-> primary :tokens :paddingX)))
    (t/is (nil? (-> primary :tokens :strokeColor)))
    (t/is (= "Button" (:text (named small "Label"))))
    (t/is (= {:fill "color.surface" :strokeColor "color.border" :strokeWidth "border-width.sm"
              :borderRadius "radius.md" :gap "spacing.sm"
              :paddingX "spacing.md" :paddingY "spacing.sm"}
             (:tokens small)))
    (t/is (= {:fill "color.text" :fontSize "font-size.xs" :fontWeight "font-weight.medium"
              :fontFamily "font-family.body"}
             (:tokens (named small "Label"))))
    (t/is (not-any? #(or (contains? % :vary) (contains? % :when) (contains? % :repeat))
                    (tree-seq map? :children small)))))

(t/deftest conditions-leave-nodes-out
  (let [plain (car/expand cars/card nil nil)
        full  (car/expand cars/card {:media true :action true :actionLabel "Read"} nil)]
    (t/is (nil? (named plain "Media")))
    (t/is (nil? (named plain "Action")))
    (t/is (= "content" (:slot (named plain "Body"))))
    (t/is (some? (named full "Media")))
    (t/is (= {:type "recipe" :name "Action" :use "button"
              :with {"label" "Read"} :variant {"Kind" "Secondary"}}
             (named full "Action")))))

(t/deftest repeats-count-from-one-each-on-its-own
  (let [tree  (car/expand cars/pricing-table {:plans 2 :features 3.0 :heading "Plans"} nil)
        plans (:children (named tree "Plans"))]
    (t/is (= "Plans" (:text (named tree "Heading"))))
    (t/is (= ["Plan 1" "Plan 2"] (mapv :name plans)))
    (t/testing "what a placed recipe is handed is worked out at each turn"
      (t/is (= {"name" "Plan 2" "cta" "Choose plan 2" "features" 3.0} (:with (second plans))))
      (t/is (= [nil {"Emphasis" "Featured"}] (mapv :variant plans)))))
  (t/testing "a repeat inside a repeat counts for itself"
    (let [tree (car/expand (get cars/builtin "data-table") {:columns 3 :rows 2} nil)
          card (car/expand (get cars/builtin "plan-card") {:features 2 :name "Team"} {:Emphasis "Featured"})]
      (t/is (= ["Header" "Row 1" "Row 2"] (mapv :name (:children tree))))
      (t/is (= ["Feature 1" "Feature 2"] (mapv :text (:children (named card "Features")))))
      (t/is (= {"Kind" "Primary"} (:variant (named card "Choose")))))))

;; --- Platform profiles

(t/deftest the-built-in-profiles-are-valid
  (t/is (= ["ios" "android" "web"] (mapv :name profiles/all)))
  (doseq [profile profiles/all]
    (t/is (nil? (car/profile-problem profile cars/builtin)))
    (t/testing "each is over the navigation recipes"
      (t/is (every? #(contains? (:recipes profile) %) ["top-bar" "sidebar" "bottom-tab-bar"])))))

(t/deftest a-profile-adjusts-and-does-not-copy
  (let [top (car/with-profile (get cars/builtin "top-bar") profiles/ios)]
    (t/testing "sizes and spacing"
      (t/is (= 393 (-> top :tree :width)))
      (t/is (= "spacing.lg" (-> top :tree :tokens :paddingX)))
      (t/is (= "color.surface" (-> top :tree :tokens :fill))))
    (t/testing "a default of a param"
      (t/is (= 0 (:default (some #(when (= "links" (:name %)) %) (:params top))))))
    (t/testing "the variant of a control it places"
      (t/is (= {:Kind "Ghost" :Size "Small"} (:variant (named (:tree top) "Action")))))
    (t/testing "the adjusted recipe is still a valid one"
      (t/is (nil? (car/problem top cars/builtin))))
    (t/testing "a profile holds no tree of its own"
      (t/is (not (re-find #":children|:type \"board\"" (pr-str profiles/ios))))))
  (t/testing "a default of a param reaches what repeats"
    (let [tree (car/expand (car/with-profile (get cars/builtin "bottom-tab-bar") profiles/ios) nil nil)]
      (t/is (= 5 (count (:children tree))))
      (t/is (= 393 (:width tree)))))
  (t/testing "a profile picks the variant of a component"
    (t/is (= {"Size" "Small"} (car/profile-variant profiles/web cars/button)))
    (t/is (nil? (car/profile-variant profiles/web cars/card)))))

(t/deftest a-recipe-with-no-profile-is-unaffected
  (doseq [recipe cars/all
          profile profiles/all
          :when (not (contains? (:recipes profile) (:name recipe)))]
    (t/is (identical? recipe (car/with-profile recipe profile)))))

(t/deftest profile-problems-are-named
  (let [problem (fn [f & args] (car/profile-problem (apply f profiles/ios args) cars/builtin))]
    (t/is (= "profile: not a profile object" (car/profile-problem [] cars/builtin)))
    (t/is (re-find #"profile \"ios\": the profile has \"script\"" (problem assoc :script "x")))
    (t/is (re-find #"recipes names \"nav-bar\", and there is no recipe of that name"
                   (problem assoc-in [:recipes "nav-bar"] {})))
    (t/is (re-find #"recipes.top-bar.params: \"links\" is at most 6"
                   (problem assoc-in [:recipes "top-bar" :params] {:links 9})))
    (t/is (re-find #"recipes.button.variant: \"Size\" is one of: Medium, Small"
                   (problem assoc-in [:recipes "button" :variant] {:Size "Huge"})))
    (t/is (re-find #"recipes.button adjusts a component"
                   (problem assoc-in [:recipes "button" :adjust] [{:layer "Label" :set {:width 10}}])))
    (t/is (re-find #"adjust\[0\] names the layer \"Logo\", and \"top-bar\" has none of that name — it has: Top bar, Brand"
                   (problem assoc-in [:recipes "top-bar" :adjust] [{:layer "Logo" :set {:width 10}}])))
    (t/is (re-find #"adjust\[0\].set has \"children\""
                   (problem assoc-in [:recipes "top-bar" :adjust] [{:layer "Brand" :set {:children []}}])))
    (t/is (re-find #"adjust\[0\].set.tokens.fill names \"#fff\", which is not a role"
                   (problem assoc-in [:recipes "top-bar" :adjust] [{:layer "Brand" :set {:tokens {:fill "#fff"}}}])))))

;; --- Whole screens

(t/deftest a-screen-is-made-of-other-recipes
  (let [screens (filter #(= "screen" (:category %)) cars/all)]
    (t/is (<= 12 (count screens)))
    (doseq [screen screens]
      (t/testing (:name screen)
        (let [nodes (tree-seq map? :children (:tree screen))]
          (t/is (<= 1 (count (filter #(= "recipe" (:type %)) nodes))))
          (t/testing "it draws next to nothing itself"
            (t/is (<= (count (remove #(contains? #{"recipe" "board"} (:type %)) nodes)) 1)))
          (t/testing "and is not a component: it is to be filled and changed"
            (t/is (not (or (seq (:variants screen)) (:component screen))))))))
    (t/testing "the kinds of screen asked for are there"
      (t/is (every? (set (map :name screens))
                    ["landing-page" "dashboard-screen" "sign-in-screen" "settings-screen"])))))

(t/deftest a-platform-sets-its-screens
  (let [screen (fn [profile nm] (:tree (car/with-profile (get cars/builtin nm) profile)))]
    (t/is (= [393 852] ((juxt :width :height) (screen profiles/ios "mobile-list-screen"))))
    (t/is (= [360 800] ((juxt :width :height) (screen profiles/android "mobile-list-screen"))))
    (t/is (= "spacing.3xl" (-> (screen profiles/ios "mobile-list-screen") :tokens :paddingTop)))
    (t/is (= 3 (:default (some #(when (= "tabs" (:name %)) %)
                               (:params (car/with-profile (get cars/builtin "mobile-list-screen") profiles/android))))))
    (t/is (= "start" (-> (screen profiles/android "mobile-header") :layout :justifyContent)))
    (t/testing "a screen a platform does not name is as it was"
      (t/is (identical? (get cars/builtin "dashboard-screen")
                        (car/with-profile (get cars/builtin "dashboard-screen") profiles/ios))))))
