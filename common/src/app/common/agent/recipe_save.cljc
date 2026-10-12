;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipe-save
  "A board of a file written down as a recipe: the reverse of building one.
  See `app.common.agent.recipe` for the format.

  What is kept is structure: the layers and their names, each board's layout,
  how each layer sits in its parent's, the words, and the tokens that are
  named as roles are. What is not: colors and other values written on the
  shapes, and tokens that go by another name. A copy of a component that is
  itself a recipe is kept as a use of that recipe.

  Whoever saves says what the recipe is called and for, and which layers are
  its params (a text whose words can be set, a layer that can be left out)
  and its slots."
  (:require
   [app.common.agent.recipe :as car]
   [app.common.agent.tokens :as cato]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.math :as mth]
   [app.common.types.component :as ctc]
   [app.common.types.file :as ctf]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [cuerdas.core :as str]))

(defn- flow
  "A shape's children in reading order."
  [shape]
  (if (and (ctl/any-layout? shape) (not (ctl/reverse? shape)))
    (reverse (:shapes shape))
    (:shapes shape)))

(defn- size
  [v]
  (let [v (mth/round v)]
    (if (pos? v) v 1)))

;; --- Tokens that are roles

(def ^:private folded
  "The shorthand that stands for all of these properties, when they share a
  role."
  [["padding"      ["paddingTop" "paddingRight" "paddingBottom" "paddingLeft"]]
   ["paddingX"     ["paddingLeft" "paddingRight"]]
   ["paddingY"     ["paddingTop" "paddingBottom"]]
   ["gap"          ["rowGap" "columnGap"]]
   ["borderRadius" ["borderRadiusTopLeft" "borderRadiusTopRight"
                    "borderRadiusBottomRight" "borderRadiusBottomLeft"]]])

(defn- fold
  [tokens]
  (reduce (fn [tokens [short properties]]
            (let [roles (map #(get tokens %) properties)]
              (if (and (every? some? roles) (apply = roles))
                (-> (apply dissoc tokens properties) (assoc short (first roles)))
                tokens)))
          tokens
          folded))

(defn- roles-of
  "→ `[tokens others]`: the shape's tokens that are roles, as `:tokens` has
  them, and the names of the ones that are not."
  [shape]
  (let [applied (:applied-tokens shape)
        roles   (into {}
                      (comp (filter (fn [[_ token]] (contains? car/roles token)))
                            (map (fn [[attr token]] [(cato/public-attr-name attr) token])))
                      applied)]
    [(into {} (map (fn [[k v]] [(keyword k) v])) (fold roles))
     (into #{} (comp (map val) (remove #(contains? car/roles %))) applied)]))

;; --- A shape as a node

(defn- layout-of
  [shape tokens]
  (let [bound? #(contains? tokens %)
        gap    (:layout-gap shape)
        pad    (:layout-padding shape)
        sides  (cond-> {}
                 (and (not (some bound? [:padding :paddingY :paddingTop])) (pos? (or (:p1 pad) 0)))
                 (assoc :top (:p1 pad))
                 (and (not (some bound? [:padding :paddingX :paddingRight])) (pos? (or (:p2 pad) 0)))
                 (assoc :right (:p2 pad))
                 (and (not (some bound? [:padding :paddingY :paddingBottom])) (pos? (or (:p3 pad) 0)))
                 (assoc :bottom (:p3 pad))
                 (and (not (some bound? [:padding :paddingX :paddingLeft])) (pos? (or (:p4 pad) 0)))
                 (assoc :left (:p4 pad)))]
    (if (ctl/grid-layout? shape)
      (cond-> {:type "grid"}
        (seq (:layout-grid-columns shape)) (assoc :columns (count (:layout-grid-columns shape))))
      (cond-> {:type "flex"
               :dir (name (or (:layout-flex-dir shape) :row))}
        (some? (:layout-align-items shape))     (assoc :alignItems (name (:layout-align-items shape)))
        (some? (:layout-justify-content shape)) (assoc :justifyContent (name (:layout-justify-content shape)))
        (= :wrap (:layout-wrap-type shape))     (assoc :wrap true)
        (and (not (some bound? [:gap :rowGap])) (pos? (or (:row-gap gap) 0)))
        (assoc :rowGap (:row-gap gap))
        (and (not (some bound? [:gap :columnGap])) (pos? (or (:column-gap gap) 0)))
        (assoc :columnGap (:column-gap gap))
        (seq sides)                             (assoc :padding sides)))))

(defn- sizing
  "How the shape sits in its parent's layout, and the sizes that are its own.
  The root has no parent to fill."
  [shape root?]
  (let [in-flow (fn [k] (let [v (get shape k)] (if (and root? (= :fill v)) :fix v)))
        h       (in-flow :layout-item-h-sizing)
        v       (in-flow :layout-item-v-sizing)
        text?   (cfh/text-shape? shape)
        child   (cond-> {}
                  (some? h) (assoc :horizontalSizing (name h))
                  (some? v) (assoc :verticalSizing (name v))
                  (and (not root?) (some? (:layout-item-align-self shape)))
                  (assoc :alignSelf (name (:layout-item-align-self shape))))]
    (cond-> {}
      (seq child) (assoc :layoutChild child)
      (and (not text?) (contains? #{nil :fix} h)) (assoc :width (size (:width shape)))
      (and (not text?) (contains? #{nil :fix} v)) (assoc :height (size (:height shape))))))

(defn- param-layers
  "Param → the layer whose words are that param and nothing else."
  [recipe]
  (into {}
        (keep (fn [node]
                (when-let [[_ param] (and (string? (:text node)) (string? (:name node))
                                          (re-matches #"\{([A-Za-z][A-Za-z0-9]*)\}" (:text node)))]
                  [param (:name node)])))
        (tree-seq map? :children (:tree recipe))))

(defn- words
  [shape]
  (txt/content->text (:content shape)))

(defn- use-of
  "The copy as a use of the recipe its component is, or nil."
  [objects libraries recipes shape]
  (when (and (ctc/instance-head? shape) (not (ctc/main-instance? shape)))
    (let [component (ctf/get-component libraries (:component-file shape) (:component-id shape))
          ;; a component built with params that change its structure is
          ;; named for them: "Table row (columns 5)"
          [_ title built] (re-matches #"^(.*?)(?: \((.+)\))?$" (or (:name component) ""))
          recipe    (some (fn [recipe]
                            (when (and (= (:title recipe) title)
                                       (or (seq (:variants recipe)) (true? (:component recipe))))
                              recipe))
                          (vals recipes))
          kinds     (into {} (map (juxt :name :type)) (:params recipe))
          shaped    (into {}
                          (keep (fn [part]
                                  (when-let [[_ k v] (re-matches #"^(\S+) (.+)$" part)]
                                    (case (get kinds k)
                                      "number"  [(keyword k) (parse-double v)]
                                      "boolean" [(keyword k) (= "true" v)]
                                      "choice"  [(keyword k) v]
                                      nil))))
                          (when built (str/split built #", ")))]
      (when (some? recipe)
        (let [axes    (into {} (map (juxt :name :values)) (:variants recipe))
              variant (into {}
                            (filter (fn [[axis value]] (some #(= value %) (get axes axis))))
                            (map (juxt :name :value) (:variant-properties component)))
              texts   (into {}
                            (comp (map #(get objects %))
                                  (filter cfh/text-shape?)
                                  (map (juxt :name words)))
                            (cfh/get-children-ids objects (:id shape)))
              params  (into {} (map (juxt :name identity)) (:params recipe))
              with    (into {}
                            (keep (fn [[param layer]]
                                    (let [said (get texts layer)]
                                      (when (and (string? said)
                                                 (not= said (:default (get params param)))
                                                 (not (re-find #"\{[A-Za-z][A-Za-z0-9]*\}" said)))
                                        [(keyword param) said]))))
                            (param-layers recipe))]
          (cond-> {:type "recipe" :name (:name shape) :use (:name recipe)}
            (seq variant) (assoc :variant (into {} (map (fn [[k v]] [(keyword k) v])) variant))
            (seq (merge shaped with)) (assoc :with (merge shaped with))))))))

(defn- node-of
  "→ the run, with the node of shape `id` under `:node`. A run that has a
  `:problem` does nothing more."
  [{:keys [objects libraries recipes] :as run} id root?]
  (let [shape       (get objects id)
        label       (dm/str "\"" (:name shape) "\"")
        [tokens others] (roles-of shape)
        run         (update run :others into others)
        used        (when-not root? (use-of objects libraries recipes shape))
        placed      (sizing shape root?)
        escape      #(str/replace % #"\{([A-Za-z][A-Za-z0-9]*)\}" "($1)")]
    (cond
      (some? (:problem run))
      run

      (some? used)
      (assoc run :node (merge used (select-keys placed [:layoutChild])))

      (cfh/group-shape? shape)
      (assoc run :problem (dm/str label " is a group — a recipe lays its layers out: turn it"
                                  " into a board with a layout (set_layout) and save again"))

      (and (cfh/frame-shape? shape) (seq (:shapes shape)) (not (ctl/any-layout? shape)))
      (assoc run :problem (dm/str label " has layers and no layout — a recipe places nothing by"
                                  " x and y: give the board a layout (set_layout) and save again"))

      (cfh/frame-shape? shape)
      (let [[run children]
            (reduce (fn [[run children] child]
                      (let [run (node-of run child false)]
                        [run (cond-> children (nil? (:problem run)) (conj (:node run)))]))
                    [run []]
                    (flow shape))]
        (assoc run :node (cond-> (merge {:type "board" :name (:name shape)} placed)
                           (ctl/any-layout? shape) (assoc :layout (layout-of shape tokens))
                           (seq tokens)            (assoc :tokens tokens)
                           (seq children)          (assoc :children children))))

      (cfh/text-shape? shape)
      (let [align (some-> (txt/node-seq txt/is-paragraph-node? (:content shape)) first :text-align)]
        (assoc run :node (cond-> (merge {:type "text" :name (:name shape) :text (escape (words shape))}
                                        placed)
                           ;; left is what a text has when nothing is said
                           (contains? #{"center" "right"} align) (assoc :align align)
                           (seq tokens) (assoc :tokens tokens))))

      :else
      (let [kind (if (= :circle (:type shape)) "ellipse" "rect")]
        (cond-> (assoc run :node (cond-> (merge {:type kind :name (:name shape)} placed)
                                   (seq tokens) (assoc :tokens tokens)))
          (not (contains? #{:rect :circle} (:type shape)))
          (update :notes conj (dm/str label " is a " (name (:type shape))
                                      ", which a recipe cannot hold: a box of its size stands in")))))))

;; --- Params and slots

(defn- layers
  [tree]
  (tree-seq map? :children tree))

(defn- mark
  "The tree with `f` applied to the one node called `layer`, or a problem."
  [tree layer what pred f]
  (let [found (filter #(and (= layer (:name %)) (pred %)) (layers tree))]
    (if (not= 1 (count found))
      {:problem (dm/str what " names the layer \"" layer "\", and "
                        (if (empty? found) "there is none of that name that fits" "more than one has that name")
                        " — layers here: "
                        (str/join ", " (distinct (keep :name (filter pred (layers tree))))))}
      (letfn [(walk [node]
                (cond-> (if (identical? node (first found)) (f node) node)
                  (contains? node :children) (update :children #(mapv walk %))))]
        {:tree (walk tree)}))))

(defn- with-params
  [tree params]
  (reduce (fn [{:keys [tree declared] :as run} {:keys [layer type description] :as param}]
            (if (some? (:problem run))
              run
              (let [what   (dm/str "param \"" (:name param) "\"")
                    base   (cond-> {:name (:name param)}
                             (string? description) (assoc :description description))
                    marked (cond
                             (not (string? layer))
                             {:problem (dm/str what " needs `layer`: the name of the layer it sets")}

                             (= "boolean" type)
                             (mark tree layer what #(not (identical? % tree))
                                   #(assoc % :when {:param (:name param)}))

                             (contains? #{nil "text"} type)
                             (mark tree layer what #(= "text" (:type %))
                                   #(assoc % :text (dm/str "{" (:name param) "}")))

                             :else
                             {:problem (dm/str what " has type \"" type "\" — a saved recipe takes"
                                               " text (the words of a text layer) or boolean"
                                               " (a layer that can be left out)")})]
                (if (some? (:problem marked))
                  marked
                  {:tree (:tree marked)
                   :declared (conj declared
                                   (if (= "boolean" type)
                                     (assoc base :type "boolean" :default true)
                                     (assoc base :type "text"
                                            :default (:text (some #(when (and (= layer (:name %))
                                                                              (= "text" (:type %)))
                                                                     %)
                                                                  (layers tree))))))}))))
          {:tree tree :declared []}
          params))

(defn- with-slots
  [tree slots]
  (reduce (fn [run {:keys [layer] :as slot}]
            (if (some? (:problem run))
              run
              (mark (:tree run) layer (dm/str "slot \"" (:name slot) "\"")
                    #(= "board" (:type %))
                    #(assoc % :slot (:name slot)))))
          {:tree tree}
          slots))

;; --- The recipe

(defn from-board
  "Board `id` of `objects` as a recipe → `{:recipe :notes}` or `{:problem}`.
  `about` says what it is — `:name :title :purpose :category :keywords` — and
  which layers are `:params` (`{:name :layer :type :description}`) and
  `:slots` (`{:name :layer}`). `recipes` are the ones there are: a copy of a
  component one of them builds is kept as a use of it."
  [objects libraries id {:keys [params slots] :as about} recipes]
  (let [shape (get objects id)]
    (cond
      (nil? shape)
      {:problem "no shape on this page with that id — check read_design"}

      (not (cfh/frame-shape? shape))
      {:problem (dm/str "\"" (:name shape) "\" is a " (name (:type shape))
                        " — a recipe is saved from a board")}

      (not (or (nil? params) (and (sequential? params) (every? map? params))))
      {:problem "params is not a list of {name, layer, type?, description?}"}

      (not (or (nil? slots) (and (sequential? slots) (every? map? slots))))
      {:problem "slots is not a list of {name, layer}"}

      :else
      (let [run (node-of {:objects objects :libraries libraries :recipes recipes
                          :notes [] :others #{}}
                         id true)]
        (if (some? (:problem run))
          (select-keys run [:problem])
          (let [slotted (with-slots (:node run) slots)
                marked  (if (some? (:problem slotted))
                          slotted
                          (with-params (:tree slotted) params))]
            (if (some? (:problem marked))
              (select-keys marked [:problem])
              (let [recipe  (cond-> {:recipe car/version
                                     :name (:name about)
                                     :title (or (:title about) (:name shape))
                                     :purpose (:purpose about)
                                     :category (:category about)
                                     :tree (:tree marked)}
                              (seq (:keywords about)) (assoc :keywords (vec (:keywords about)))
                              (seq (:declared marked)) (assoc :params (:declared marked)))
                    problem (car/problem recipe (assoc recipes (:name recipe) recipe))]
                (if (some? problem)
                  {:problem problem}
                  {:recipe recipe
                   :notes (cond-> (:notes run)
                            (seq (:others run))
                            (conj (dm/str "values bound to tokens that are not roles were left out ("
                                          (str/join ", " (sort (:others run)))
                                          "): a recipe names roles, such as color.primary")))})))))))))
