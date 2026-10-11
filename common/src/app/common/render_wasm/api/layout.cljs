;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.render-wasm.api.layout
  "Browser-free grid layout setters, shared by the workspace render
  orchestrator (`app.render-wasm.api`) and the headless layout driver
  (`app.wasm.layout`). Flex and layout-item properties travel in the shape
  upload (`app.common.render-wasm.api.upload`); a grid's tracks and cells are
  written here, to the shape the engine currently has in use."
  (:require
   [app.common.data :as d]
   [app.common.render-wasm.helpers :as h]
   [app.common.render-wasm.mem :as mem]
   [app.common.render-wasm.serializers :as sr]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.uuid :as uuid]))

(def ^:const GRID-LAYOUT-ROW-U8-SIZE 8)
(def ^:const GRID-LAYOUT-COLUMN-U8-SIZE 8)
(def ^:const GRID-LAYOUT-CELL-U8-SIZE 36)

(defn set-grid-layout-data
  [shape]
  (let [dir        (-> (get shape :layout-grid-dir :row)
                       (sr/translate-layout-grid-dir))
        gap        (get shape :layout-gap)
        row-gap    (get gap :row-gap 0)
        column-gap (get gap :column-gap 0)

        align-items     (-> (get shape :layout-align-items) sr/translate-layout-align-items)
        align-content   (-> (get shape :layout-align-content) sr/translate-layout-align-content)
        justify-items   (-> (get shape :layout-justify-items) sr/translate-layout-justify-items)
        justify-content (-> (get shape :layout-justify-content) sr/translate-layout-justify-content)

        padding         (get shape :layout-padding)
        padding-top     (get padding :p1 0)
        padding-right   (get padding :p2 0)
        padding-bottom  (get padding :p3 0)
        padding-left    (get padding :p4 0)]

    (h/call wasm/internal-module
            "_set_grid_layout_data"
            dir
            row-gap
            column-gap
            align-items
            align-content
            justify-items
            justify-content
            padding-top
            padding-right
            padding-bottom
            padding-left)))

(defn set-grid-layout-rows
  [entries]
  ;; Only allocate when there are entries; an empty list would alloc 0 bytes.
  ;; The wasm side reads an empty buffer as zero rows.
  (when (seq entries)
    (let [size    (mem/get-alloc-size entries GRID-LAYOUT-ROW-U8-SIZE)
          offset  (mem/alloc size)
          dview   (mem/get-data-view)]

      (reduce (fn [offset {:keys [type value]}]
                (-> offset
                    (mem/write-u8 dview (sr/translate-grid-track-type type))
                    (+ 3) ;; padding
                    (mem/write-f32 dview value)
                    (mem/assert-written offset GRID-LAYOUT-ROW-U8-SIZE)))

              offset
              entries)))

  (h/call wasm/internal-module "_set_grid_rows"))

(defn set-grid-layout-columns
  [entries]
  ;; Only allocate when there are entries; an empty list would alloc 0 bytes.
  ;; The wasm side reads an empty buffer as zero columns.
  (when (seq entries)
    (let [size   (mem/get-alloc-size entries GRID-LAYOUT-COLUMN-U8-SIZE)
          offset (mem/alloc size)
          dview  (mem/get-data-view)]

      (reduce (fn [offset {:keys [type value]}]
                (-> offset
                    (mem/write-u8 dview (sr/translate-grid-track-type type))
                    (+ 3) ;; padding
                    (mem/write-f32 dview value)
                    (mem/assert-written offset GRID-LAYOUT-COLUMN-U8-SIZE)))
              offset
              entries)))

  (h/call wasm/internal-module "_set_grid_columns"))

(defn set-grid-layout-cells
  [cells]
  ;; Only allocate when there are cells; an empty collection would alloc 0
  ;; bytes. The wasm side reads an empty buffer as zero cells.
  (when (seq cells)
    (let [size    (mem/get-alloc-size cells GRID-LAYOUT-CELL-U8-SIZE)
          offset  (mem/alloc size)
          dview   (mem/get-data-view)]

      (reduce-kv (fn [offset _ cell]
                   (let [shape-id  (-> (get cell :shapes) first)]
                     (-> offset
                         (mem/write-i32 dview (get cell :row))
                         (mem/write-i32 dview (get cell :row-span))
                         (mem/write-i32 dview (get cell :column))
                         (mem/write-i32 dview (get cell :column-span))

                         (mem/write-u8 dview (sr/translate-align-self (get cell :align-self)))
                         (mem/write-u8 dview (sr/translate-justify-self (get cell :justify-self)))

                         ;; padding
                         (+ 2)

                         (mem/write-uuid dview (d/nilv shape-id uuid/zero))
                         (mem/assert-written offset GRID-LAYOUT-CELL-U8-SIZE))))

                 offset
                 cells)))

  (h/call wasm/internal-module "_set_grid_cells"))

(defn set-grid-layout
  [shape]
  (set-grid-layout-data shape)
  (set-grid-layout-rows (get shape :layout-grid-rows))
  (set-grid-layout-columns (get shape :layout-grid-columns))
  (set-grid-layout-cells (get shape :layout-grid-cells)))
