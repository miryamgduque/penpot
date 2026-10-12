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
    of one, main or copy.

  A share is a number from 0 to 1, or nil when there is nothing to count."
  (:require
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

(defn capability-usage
  "The measure for the layers under `roots` (ids of boards), or for the whole
  page when none are given → the counts and the shares."
  [objects roots]
  (let [ids     (if (seq roots)
                  (into [] (comp (filter #(contains? objects %))
                                 (mapcat #(cfh/get-children-ids-with-self objects %))
                                 (distinct))
                        roots)
                  (remove #(= uuid/zero %) (keys objects)))
        layers  (mapv #(get objects %) ids)
        inside  (filterv #(not= uuid/zero (:parent-id %)) layers)
        colored (filterv colored? layers)
        counts  {:layers (count layers)
                 :insideABoard (count inside)
                 :inAutoLayout (count (filter #(laid-out? objects %) inside))
                 :tokenBound (count (filter #(seq (:applied-tokens %)) layers))
                 :withAColor (count colored)
                 :colorTokenBound (count (filter color-bound? colored))
                 :inComponents (count (filter #(in-component? objects %) layers))}]
    {:counts counts
     :shares {:autoLayout (share (:inAutoLayout counts) (:insideABoard counts))
              :tokenBound (share (:tokenBound counts) (:layers counts))
              :colorTokenBound (share (:colorTokenBound counts) (:withAColor counts))
              :components (share (:inComponents counts) (:layers counts))}}))
