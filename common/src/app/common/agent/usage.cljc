;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.usage
  "How much of a design uses what Penpot offers: the measure the recipes are
  held against.

  Three shares, each of the layers of a page or of some of its boards:

  - in auto-layout: of the layers that sit inside a board, the ones a layout
    places — their board has a layout and they are not taken out of its flow;
  - token-bound: of all the layers, the ones with a token on any property;
    and, of the layers that have a color of their own, the ones whose color
    is a token;
  - in components: of all the layers, the ones that are a component or part
    of one, main or copy;
  - library reuse: of the copies of a component placed, the ones whose
    component lives in a linked library and not in this file.

  A share is a number from 0 to 1, or nil when there is nothing to count."
  (:require
   [app.common.agent.recipe :as car]
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctc]
   [app.common.types.container :as ctn]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]))

(defn- share
  [part whole]
  (when (pos? whole)
    (/ (double part) whole)))

(defn- colored?
  "Whether the layer has a fill or a stroke of its own."
  [shape]
  (or (seq (:fills shape))
      (seq (:strokes shape))
      (and (cfh/text-shape? shape)
           (some #(seq (:fills %)) (txt/node-seq txt/is-text-node? (:content shape))))))

(defn- color-bound?
  [shape]
  (some #(contains? (:applied-tokens shape) %) [:fill :stroke-color]))

(defn- laid-out?
  [objects shape]
  (let [parent (get objects (:parent-id shape))]
    (and (ctl/any-layout? parent)
         (not (true? (:layout-item-absolute shape))))))

(defn- in-component?
  [objects shape]
  (or (ctc/in-component-copy? shape)
      (ctc/main-instance? shape)
      (some? (ctn/get-component-shape objects shape {:allow-main? true}))))

(defn- copy-head?
  "Whether the layer is a copy of a component, taken as a whole."
  [shape]
  (and (some? (:component-id shape))
       (true? (:component-root shape))
       (not (ctc/main-instance? shape))))

(defn- capability-usage*
  [objects roots file-id]
  (let [ids     (if (seq roots)
                  (into [] (comp (filter #(contains? objects %))
                                 (mapcat #(cfh/get-children-ids-with-self objects %))
                                 (distinct))
                        roots)
                  (remove #(= uuid/zero %) (keys objects)))
        layers  (mapv #(get objects %) ids)
        inside  (filterv #(not= uuid/zero (:parent-id %)) layers)
        colored (filterv colored? layers)
        copies  (filterv copy-head? layers)
        counts  {:layers (count layers)
                 :insideABoard (count inside)
                 :inAutoLayout (count (filter #(laid-out? objects %) inside))
                 :tokenBound (count (filter #(seq (:applied-tokens %)) layers))
                 :withAColor (count colored)
                 :colorTokenBound (count (filter color-bound? colored))
                 :inComponents (count (filter #(in-component? objects %) layers))
                 :copies (count copies)
                 :libraryCopies (if (some? file-id)
                                  (count (filter #(not= file-id (:component-file %)) copies))
                                  0)}]
    {:counts counts
     :shares {:autoLayout (share (:inAutoLayout counts) (:insideABoard counts))
              :tokenBound (share (:tokenBound counts) (:layers counts))
              :colorTokenBound (share (:colorTokenBound counts) (:withAColor counts))
              :components (share (:inComponents counts) (:layers counts))
              :libraryReuse (when (some? file-id)
                              (share (:libraryCopies counts) (:copies counts)))}}))

(defn capability-usage
  "The measure for the layers under `roots` (ids of boards), or for the whole
  page when none are given → the counts and the shares. With `file-id`, the
  file the page is of, it also tells the copies of a linked library's
  components from the copies of the file's own."
  ([objects roots] (capability-usage objects roots nil))
  ([objects roots file-id]
   (capability-usage* objects roots file-id)))

;; --- The design-system measure
;;
;; What a design system is held against: of the values a system owns —
;; colors, type, spacing, corners — how many come from a token, and how many
;; from a token named as a role, which is what any token pack or theme can
;; restyle in one step.

(def ^:private kinds
  "Kind → the token properties that carry it."
  {:color    #{:fill :stroke-color}
   :typeSize #{:font-size :typography}
   :typeface #{:font-family :typography}
   :spacing  #{:p1 :p2 :p3 :p4 :row-gap :column-gap}
   :radius   #{:r1 :r2 :r3 :r4}})

(defn- has-kind?
  "Whether the layer has a value of that kind to take from a system."
  [kind shape]
  (case kind
    :color   (colored? shape)
    (:typeSize :typeface) (cfh/text-shape? shape)
    :spacing (and (ctl/any-layout? shape)
                  (or (some pos? (vals (:layout-padding shape)))
                      (some pos? (vals (:layout-gap shape)))))
    :radius  (some #(pos? (or (get shape %) 0)) [:r1 :r2 :r3 :r4])))

(defn- tokens-on
  [kind shape]
  (keep (fn [[attr token-name]] (when (contains? (get kinds kind) attr) token-name))
        (:applied-tokens shape)))

(defn system-usage
  "The design-system measure for the layers under `roots`, or for the whole
  page → per kind (color, type size, typeface, spacing, radius): how many layers have a
  value of that kind, how many take it from a token, and how many from a
  token named as a role. `restyle` is the share of all those values that a
  pack or a theme reaches: the role-bound ones."
  [objects roots]
  (let [ids    (if (seq roots)
                 (into [] (comp (filter #(contains? objects %))
                                (mapcat #(cfh/get-children-ids-with-self objects %))
                                (distinct))
                       roots)
                 (remove #(= uuid/zero %) (keys objects)))
        layers (mapv #(get objects %) ids)
        counts (into {}
                     (map (fn [kind]
                            (let [with  (filterv #(has-kind? kind %) layers)
                                  named (map #(tokens-on kind %) with)]
                              [kind {:with (count with)
                                     :bound (count (filter seq named))
                                     :roleBound (count (filter #(some (fn [n] (contains? car/roles n)) %) named))}])))
                     (keys kinds))
        total  (fn [k] (transduce (map k) + (vals counts)))]
    {:counts counts
     :shares (-> (into {} (map (fn [[kind {:keys [with bound]}]] [kind (share bound with)])) counts)
                 (assoc :all (share (total :bound) (total :with))
                        :restyle (share (total :roleBound) (total :with))))}))
