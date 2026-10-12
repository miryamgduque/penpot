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
   [app.common.agent.recipe-build :as carb]
   [app.common.agent.recipes :as cars]
   [app.common.agent.tokens :as cato]
   [app.common.agent.tools :as cat]
   [app.common.files.changes :as cfc]
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
               [nm (if (= :color (:type token))
                     {:resolved-value (:value token) :color {:color (:value token) :opacity 1}}
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
    (t/is (re-find #"pass recipe — the name of one of: button, card, pricing-table" (problem {})))
    (t/is (re-find #"no recipe named \"buton\"" (problem {:recipe "buton"})))
    (t/is (re-find #"\"text\" is not a param of \"button\"" (problem {:recipe "button" :params {:text "x"}})))
    (t/is (re-find #"\"Kind\" is one of" (problem {:recipe "button" :variant {:Kind "Loud"}})))
    (t/is (re-find #"no shape on this page" (problem {:recipe "button" :parentId (str (uuid/next))})))
    (t/is (re-find #"roles names \"color.brand\", which is not a role"
                   (problem {:recipe "button" :roles {"color.brand" "x"}})))
    (t/is (re-find #"roles names the token \"brand.500\", and this file has no token"
                   (problem {:recipe "button" :roles {"color.primary" "brand.500"}})))))

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
        (t/is (not (contains? (token-names after) "color.on-primary")))
        (t/is (some? label))))))

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
        plan-2      (child after (:id result) "Plan 2")
        choose      (child after (str (:id plan-2)) "Choose")]
    (t/is (= "Pricing" (:name root)))
    (t/is (not (ctc/instance-head? root)))
    (t/is (= [{:recipe "button" :built true :members 6}]
             (mapv #(dissoc % :variantId) (:mains result))))
    (t/is (= 3 (count (:shapes (child after (str (:id plan-2)) "Features")))))
    (t/is (nil? (child after (:id result) "Plan 3")))
    (t/is (ctc/instance-head? choose))
    (t/is (= "Choose plan 2" (words (child after (str (:id choose)) "Label"))))
    (t/is (= "color.background" (-> root :applied-tokens :fill)))
    (t/is (empty? (:fills (child after (:id result) "Plans"))))
    (t/is (empty? (errors after)))))

(t/deftest a-component-inside-a-component-gets-its-words
  (let [[after out] (build data {:recipe "card" :params {:title "Hello" :action true :actionLabel "Read"}})
        result      (:result out)
        card        (shape after (:id result))]
    (t/is (= ["button" "card"] (mapv :recipe (:mains result))))
    (t/is (ctc/instance-head? card))
    (t/testing "a param that changes structure is not applied to an instance, and the result says so"
      (t/is (re-find #"an instance keeps its main's structure" (first (:notes result)))))
    (let [[after out] (build after {:recipe "card" :params {:title "Hello"}})]
      (t/is (= "Hello" (words (child after (-> out :result :id) "Title"))))
      (t/is (some? (-> out :result :ids))))))

(t/deftest slots-are-named-in-the-result
  (let [[after out] (build data {:recipe "card"})]
    ;; the slot is inside the main: an instance has no slot of its own
    (t/is (nil? (-> out :result :slots)))
    (t/is (some? (child after (-> out :result :id) "Content")))))

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

(t/deftest what-the-engine-cannot-do-is-the-hosts-to-settle
  (t/is (some? (:fallback (second (build data {:recipe "button"} (dissoc options :measure-text)))))))

(t/deftest the-host-can-hand-in-its-own-recipes
  (let [mine        (assoc-in (get recipes "card") [:tree :name] "Team card")
        [after out] (build data {:recipe "card"} (assoc-in options [:recipes "card"] (assoc mine :component false)))]
    (t/is (= "Team card" (:name (shape after (-> out :result :id)))))
    (t/is (= {"content" (str (:id (child after (-> out :result :id) "Content")))}
             (-> out :result :slots)))
    (t/is (= ["button" "card" "pricing-table"] (sort (keys (carb/catalog {})))))))
