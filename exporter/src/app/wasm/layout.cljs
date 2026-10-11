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
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.render-wasm.api.layout :as wasm.layout]
   [app.common.render-wasm.api.props :as props]
   [app.common.render-wasm.api.upload :as upload]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.mem.heap32 :as mem.h32]
   [app.common.render-wasm.serializers :as sr]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.types.path :as path]
   [app.common.types.path.impl :as path.impl]
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

(defn measure-text
  "How big the words of text shape `id` are in the loaded page, as the engine
  lays them out with the fonts it has → `{:width :height}`, or nil when the
  shape is not a text."
  [id]
  (let [buffer (uuid/get-u32 id)]
    (h/call wasm/internal-module "_use_shape"
            (aget buffer 0) (aget buffer 1) (aget buffer 2) (aget buffer 3))
    (let [ptr (h/call wasm/internal-module "_get_text_dimensions")]
      (when-not (zero? ptr)
        (let [offset  (mem/->offset-32 ptr)
              heapf32 (mem/get-heap-f32)
              width   (aget heapf32 (+ offset 0))
              height  (aget heapf32 (+ offset 1))]
          (mem/free)
          {:width width :height height})))))

;; --- Booleans
;;
;; The outline of a boolean shape is computed by the engine, in the workspace
;; and here: the same `_calculate_bool`, so a boolean made on the server has
;; the path the editor would give it.

(def ^:private UUID-U8-SIZE 16)

(defn- calculate-bool*
  [bool-type ids]
  (let [ids    (vec ids)
        size   (mem/get-alloc-size ids UUID-U8-SIZE)
        heap   (mem/get-heap-u32)
        offset (mem/alloc->offset-32 size)]
    (reduce (fn [offset id] (mem.h32/write-uuid offset heap id))
            offset
            (rseq ids))
    (try
      (let [offset  (-> (h/call wasm/internal-module "_calculate_bool" (sr/translate-bool-type bool-type))
                        (mem/->offset-32))
            heap    (mem/get-heap-u32)
            length  (aget heap offset)
            data    (mem/slice heap (+ offset 1) (* length path.impl/SEGMENT-U32-SIZE))
            content (path/from-bytes data)]
        (mem/free)
        content)
      (catch :default cause
        (mem/free)
        (throw cause)))))

(defn calculate-bool
  "The engine's implementation of `path/calc-bool-content`. The operands are
  loaded into a temporary shape pool, so whatever page is loaded stays as it
  is."
  [shape objects]
  (h/call wasm/internal-module "_start_temp_objects")
  (try
    (let [ids      (get shape :shapes)
          children (mapcat #(cfh/get-children-with-self objects %) ids)]
      (h/call wasm/internal-module "_init_shapes_pool" (count children))
      (run! set-shape! children)
      (-> (calculate-bool* (get shape :bool-type) ids)
          (path.impl/path-data)))
    (finally
      (h/call wasm/internal-module "_end_temp_objects"))))

(defn install-bool-engine!
  "Makes the shared path code compute booleans with the engine."
  []
  (set! path/wasm:calc-bool-content calculate-bool))
