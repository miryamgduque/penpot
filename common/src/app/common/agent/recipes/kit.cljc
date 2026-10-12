;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipes.kit
  "Short ways to write the nodes of a built-in recipe. What they return is
  the plain data of the recipe format (`app.common.agent.recipe`), nothing
  more: a recipe written with them is the same recipe written out by hand.")

(defn- sized
  [node size]
  (cond-> node
    (some? size) (assoc :layoutChild (cond-> {}
                                       (first size)  (assoc :horizontalSizing (first size))
                                       (second size) (assoc :verticalSizing (second size))))))

(defn- with-rest
  [node {:keys [vary repeat when slot placeholder]}]
  (cond-> node
    (some? vary)        (assoc :vary vary)
    (some? repeat)      (assoc :repeat repeat)
    (some? when)        (assoc :when when)
    (some? slot)        (assoc :slot slot)
    (some? placeholder) (assoc :placeholder placeholder)))

(defn board
  "A board that lays out its children. `opts`: `:dir` (column), `:align`,
  `:justify`, `:wrap`, `:w`, `:h`, `:size` ([horizontal vertical] sizing in
  its parent), `:tokens`, `:slot`, `:vary`, `:repeat`, `:when`."
  [nm {:keys [dir align justify wrap w h size tokens] :or {dir "column"} :as opts} & children]
  (let [children (vec (remove nil? children))]
    (-> (cond-> {:type "board"
                 :name nm
                 :layout (cond-> {:type "flex" :dir dir}
                           align   (assoc :alignItems align)
                           justify (assoc :justifyContent justify)
                           wrap    (assoc :wrap true))}
          (some? w)      (assoc :width w)
          (some? h)      (assoc :height h)
          (some? tokens) (assoc :tokens tokens)
          (seq children) (assoc :children children))
        (sized size)
        (with-rest opts))))

(defn text
  "A text in the type size `size` (xs, sm, md, lg, xl, 2xl). `opts`: `:fill`
  (a color role, color.text), `:weight` (medium, bold), `:align`, `:grow`
  (fill its parent's width), `:vary`, `:repeat`, `:when`."
  [nm words size & {:keys [fill weight align grow] :as opts}]
  (-> (cond-> {:type "text"
               :name nm
               :text words
               :tokens (cond-> {:fill (or fill "color.text")
                                :fontSize (str "font-size." size)
                                ;; the big sizes are headings
                                :fontFamily (if (contains? #{"xl" "2xl"} size)
                                              "font-family.heading"
                                              "font-family.body")}
                         weight (assoc :fontWeight (str "font-weight." weight)))}
        align (assoc :align align))
      (sized (when grow ["fill" nil]))
      (with-rest opts)))

(defn rect
  "A box of a color role. `opts`: `:radius`, `:grow`, `:placeholder`, `:vary`,
  `:repeat`, `:when`."
  [nm w h fill & {:keys [radius grow] :as opts}]
  (-> (cond-> {:type "rect" :name nm :tokens (cond-> {:fill fill} radius (assoc :borderRadius radius))}
        (some? w) (assoc :width w)
        (some? h) (assoc :height h))
      (sized (when grow ["fill" nil]))
      (with-rest opts)))

(defn ellipse
  [nm w h fill & {:as opts}]
  (-> {:type "ellipse" :name nm :width w :height h :tokens {:fill fill}}
      (with-rest opts)))

(defn media
  "The place of a picture: a muted box as wide as its parent."
  [nm h & {:as opts}]
  (-> {:type "rect" :name nm :height h :placeholder "image"
       :tokens {:fill "color.surface-muted" :borderRadius "radius.md"}}
      (sized ["fill" nil])
      (with-rest opts)))

(defn place
  "Another recipe, placed here. `opts`: `:with`, `:variant`, `:size`,
  `:vary`, `:repeat`, `:when`, `:placeholder`."
  [nm recipe & {:keys [with variant size] :as opts}]
  (-> (cond-> {:type "recipe" :name nm :use recipe}
        (some? with)    (assoc :with with)
        (some? variant) (assoc :variant variant))
      (sized size)
      (with-rest opts)))

(defn icon
  "The place of an icon: Small, Medium or Large; Muted, Strong or Primary."
  ([] (icon "Icon" "Small" "Muted"))
  ([nm size tone & {:as opts}]
   (apply place nm "icon" :variant {:Size size :Tone tone} :placeholder "icon" (mapcat identity opts))))

(defn on
  "What a node has instead in the variants that match."
  [variant set]
  {:on variant :set set})

(defn at
  "What a repeated node has instead at one turn."
  [turn set]
  {:at turn :set set})

(defn axis
  [nm & values]
  {:name nm :values (vec values)})

(defn ptext
  [nm default description]
  {:name nm :type "text" :default default :description description})

(defn pnum
  [nm default lowest highest description]
  {:name nm :type "number" :default default :min lowest :max highest :description description})

(defn pbool
  [nm default description]
  {:name nm :type "boolean" :default default :description description})

(defn recipe
  "A recipe: what it is (`:name :title :category :purpose :keywords`, and
  `:params :variants :component` when it has them) and its tree."
  [about tree]
  (assoc about :recipe 1 :tree tree))

(def surface
  "The tokens of a bordered surface."
  {:fill "color.surface"
   :strokeColor "color.border"
   :strokeWidth "border-width.sm"
   :borderRadius "radius.lg"})
