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

(t/deftest the-examples-prove-the-format
  (t/testing "variants"
    (t/is (= ["Kind" "Size"] (mapv :name (:variants cars/button))))
    (t/is (some? (-> cars/button :tree :vary))))
  (t/testing "references to other recipes"
    (t/is (= ["button"] (car/uses cars/card)))
    (t/is (= ["button"] (car/uses cars/pricing-table))))
  (t/testing "repetition, also nested"
    (let [plan (-> cars/pricing-table :tree :children second :children first)]
      (t/is (= {:param "plans"} (:repeat plan)))
      (t/is (= {:param "features"} (-> plan :children (nth 2) :children first :repeat))))))

(t/deftest a-valid-recipe-has-no-problem
  (t/is (nil? (car/problem minimal))))

(t/deftest metadata-problems-are-named
  (t/is (= "recipe: not a recipe object" (car/problem "button")))
  (t/is (re-find #"format version read here is 1" (problem assoc :recipe 2)))
  (t/is (re-find #"\"script\", which is not part of the format" (problem assoc :script "alert(1)")))
  (t/is (re-find #"`name` in kebab-case" (problem assoc :name "My Thing")))
  (t/is (re-find #"recipe \"thing\": needs a `title`" (problem dissoc :title)))
  (t/is (re-find #"needs a `purpose`" (problem assoc :purpose "")))
  (t/is (re-find #"`category`: one of container, control, navigation, section"
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
  (t/is (re-find #"tree.vary is there, but the recipe has no variants"
                 (problem assoc-in [:tree :vary] [{:on {:A "x"} :set {:width 10}}])))
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
  (t/is (re-find #"tree.layout needs `type`: flex or grid" (problem assoc-in [:tree :layout] {:dir "row"})))
  (t/is (re-find #"tree.layout has \"gap\"" (problem assoc-in [:tree :layout] {:type "flex" :gap 4})))
  (t/is (re-find #"tree.children\[0\].width is not a number above zero"
                 (problem with-child {:type "rect" :width "wide"})))
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
    (t/is (re-find #"has \"vary\"" (use {:vary []})))
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
