;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.layout
  "Layout under Node: loads a page into the headless render-wasm module WITH
  its layout properties and asks the engine where things go — the same
  `propagate_modifiers` the workspace calls, so a change computed here lands
  where it would land in the editor.

  The rendering path (`app.wasm.serialize`) leaves layout out on purpose: a
  stored page is already laid out, and drawing it needs no layout pass.
  Changing a page does."
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.render-wasm.api.layout :as wasm.layout]
   [app.common.render-wasm.api.props :as props]
   [app.common.render-wasm.api.upload :as upload]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.mem.heap32 :as mem.h32]
   [app.common.render-wasm.serializers :as sr]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.wasm.text :as text]))

;; Byte layouts of `propagate_modifiers`, as in `app.render-wasm.api`:
;; input entry  = [uuid 16][matrix 24][kind u32]
;; result entry = [uuid 16][matrix 24]
(def ^:private INPUT-MODIFIER-U8-SIZE 44)
(def ^:private MODIFIER-U32-SIZE 10)

;; `propagate_modifiers` pixel precision: the tools place shapes where they
;; are asked to, like the sidebar's numeric inputs do.
(def ^:private PIXEL-PRECISION-DISABLED 0)

(defn set-shape!
  "Serializes one shape into the WASM design state, layout included."
  [shape]
  (let [type (get shape :type)]
    (upload/set-shape-upload! shape {:include-layout? true})
    (when (some? (get shape :svg-attrs))
      (props/set-shape-svg-attrs (get shape :svg-attrs)))
    (when (and (contains? #{:path :bool} type) (some? (get shape :content)))
      (props/set-shape-path-content (get shape :content)))
    (props/write-shape-fills! (get shape :fills))
    (when-not (= type :group)
      (props/write-shape-strokes! (get shape :strokes)))
    (when (= type :text)
      (text/set-shape-text! (get shape :content)))
    ;; flex travels in the upload above; a grid's tracks and cells do not
    (when (ctl/grid-layout? shape)
      (wasm.layout/set-grid-layout shape))))

(defn load-page!
  "Loads every shape of an `objects` map, replacing whatever was loaded."
  [objects]
  (h/call wasm/internal-module "_init_shapes_pool" (count objects))
  (run! set-shape! (vals objects)))

(defn- read-entry
  [heapu32 heapf32 offset]
  (d/vec2 (uuid/from-unsigned-parts (aget heapu32 (+ offset 0))
                                    (aget heapu32 (+ offset 1))
                                    (aget heapu32 (+ offset 2))
                                    (aget heapu32 (+ offset 3)))
          (gmt/matrix (aget heapf32 (+ offset 4))
                      (aget heapf32 (+ offset 5))
                      (aget heapf32 (+ offset 6))
                      (aget heapf32 (+ offset 7))
                      (aget heapf32 (+ offset 8))
                      (aget heapf32 (+ offset 9)))))

(defn propagate
  "Propagates geometry modifiers through the loaded tree → `{shape-id
  transform}` for every shape that has to move or resize as a result.
  `entries` is a seq of `[id {:transform matrix :kind :parent|:child}]`."
  [entries]
  (if (empty? entries)
    {}
    (let [entries (vec entries)
          heapf32 (mem/get-heap-f32)
          heapu32 (mem/get-heap-u32)
          size    (mem/get-alloc-size entries INPUT-MODIFIER-U8-SIZE)
          offset  (mem/alloc->offset-32 size)]
      (reduce (fn [offset [id {:keys [transform kind]}]]
                (-> offset
                    (mem.h32/write-uuid heapu32 id)
                    (mem.h32/write-matrix heapf32 transform)
                    (mem.h32/write-u32 heapu32 (sr/translate-transform-entry-kind kind))))
              offset
              entries)
      (let [;; the heap can grow during the call: read through fresh views
            offset     (-> (h/call wasm/internal-module "_propagate_modifiers" PIXEL-PRECISION-DISABLED)
                           (mem/->offset-32))
            heapf32    (mem/get-heap-f32)
            heapu32    (mem/get-heap-u32)
            length     (aget heapu32 offset)
            max-offset (+ offset 1 (* length MODIFIER-U32-SIZE))
            result     (loop [result (transient {})
                              offset (inc offset)]
                         (if (< offset max-offset)
                           (let [[id transform] (read-entry heapu32 heapf32 offset)]
                             (recur (assoc! result id transform)
                                    (+ offset MODIFIER-U32-SIZE)))
                           (persistent! result)))]
        (mem/free)
        result))))

(defn reflow
  "Asks the engine to lay `ids` out again → `{shape-id transform}`. A unit
  matrix on a parent is how the workspace asks for a reflow."
  [ids]
  (propagate (map (fn [id] [id {:transform (gmt/matrix) :kind :parent}]) ids)))
