;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.layout
  "Flex and grid layout tools: set_layout and set_layout_child."
  (:require
   [app.common.agent.layout :as cal]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-tools.common :as atc]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [beicon.v2.core :as rx]))

;; --- Shared with the server
;;
;; The pure halves of the layout tools live in `app.common.agent.layout`,
;; shared with the server-side tools. They keep their names here.

(def layout-changes cal/layout-changes)

(def grid-tracks cal/grid-tracks)

(def rebuild-grid cal/rebuild-grid)

(def reflow-grid-cells cal/reflow-grid-cells)

(def layout-problem cal/layout-problem)

(defn set-layout
  [{:keys [shapeId type columns remove] :as input}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)]
    (if-let [problem (layout-problem objects id input)]
      (rx/throw (ex-info problem {}))
      (let [shape    (get objects id)
            changes  (layout-changes input)
            existing (:layout shape)
            ;; default to flex, as before; honour an explicit type; a board that
            ;; already has a layout keeps its kind unless type says otherwise
            kind     (cond type (keyword type)
                           existing existing
                           :else :flex)
            grid?    (= :grid kind)
            tracks   (grid-tracks columns)]
        (atc/interrupt!)
        (cond
          remove
          (do (st/emit! (dwsl/remove-layout #{id}))
              (rx/of {:note "layout removed — children keep their positions"}))

          :else
          (do
            ;; (re)create when there is no layout, or when switching kinds —
            ;; create-layout-from-id seeds the right initializer for each
            (when (or (nil? existing) (not= existing kind))
              (st/emit! (dwsl/create-layout-from-id id kind)))
            (when (seq changes)
              (st/emit! (dwsl/update-layout [id] changes)))
            ;; explicit columns: rebuild the grid so children actually flow
            ;; across N columns, not just widen the track vector under cells the
            ;; auto-grid already stacked into column 1. {:with-objects? true}
            ;; gives the update fn the fresh objects assign-cells needs.
            (when (and grid? (seq tracks))
              (st/emit! (dwsh/update-shapes [id]
                                            (fn [shape objs] (rebuild-grid shape objs columns))
                                            {:with-objects? true})))
            (rx/of {:note (if grid?
                            (str "grid layout applied — children flow into cells and "
                                 "reflow as you add more, so stop setting their x/y. "
                                 "Gaps and padding accept spacing tokens via "
                                 "apply_tokens. Verify with read_design.")
                            (str "flex layout applied — children now reflow, so stop "
                                 "setting their x/y. Gaps and padding accept spacing "
                                 "tokens via apply_tokens. Verify with read_design."))})))))))

(def layout-child-attrs cal/layout-child-attrs)

(def layout-child-problem cal/layout-child-problem)

(defn set-layout-child
  [{:keys [shapeId] :as input}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)]
    (if-let [problem (layout-child-problem objects id input)]
      (rx/throw (ex-info problem {}))
      (do
        (atc/interrupt!)
        (st/emit! (dwsl/update-layout-child [id] (layout-child-attrs input)))
        (rx/of {:note (str "layout applied to the child — its parent reflows. "
                           "Margins accept spacing tokens via apply_tokens. "
                           "Verify with read_design.")})))))
