;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.layout
  "Flex and grid layout tools: set_layout and set_layout_child."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-tools.common :as atc]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [beicon.v2.core :as rx]))

;; --- Layout (flex)
;;
;; The single widest gap in the tool surface: the skills' central method is
;; "create a board, give it a flex layout, apply spacing tokens to the gaps", and
;; only the first step was reachable — which is why the agent positioned
;; everything by absolute x/y.
;;
;; Names and values are mapped from `common/types/shape/layout.cljc`, NOT from
;; the plugin's `flex.cljs`: the plugin proxy does its own aliasing, and copying
;; its public names without its translation layer would silently miss.

(def ^:private layout-dirs #{"row" "row-reverse" "column" "column-reverse"})

(def ^:private layout-align-items #{"start" "end" "center" "stretch"})

(def ^:private layout-justify-content
  #{"start" "center" "end" "space-between" "space-around" "space-evenly" "stretch"})

(defn layout-changes
  "The `:layout-*` patch for `update-layout`, from the tool's public params.

  Only keys actually given are emitted — `update-layout` patches whatever it
  receives, so a stray nil would clobber a value the caller never mentioned."
  [{:keys [dir alignItems justifyContent rowGap columnGap padding wrap]}]
  (let [gap (cond-> {}
              (some? rowGap)    (assoc :row-gap rowGap)
              (some? columnGap) (assoc :column-gap columnGap))
        pad (cond-> {}
              (some? (:top padding))    (assoc :p1 (:top padding))
              (some? (:right padding))  (assoc :p2 (:right padding))
              (some? (:bottom padding)) (assoc :p3 (:bottom padding))
              (some? (:left padding))   (assoc :p4 (:left padding)))]
    (cond-> {}
      (some? dir)            (assoc :layout-flex-dir (keyword dir))
      (some? alignItems)     (assoc :layout-align-items (keyword alignItems))
      (some? justifyContent) (assoc :layout-justify-content (keyword justifyContent))
      (some? wrap)           (assoc :layout-wrap-type (if wrap :wrap :nowrap))
      (seq gap)              (assoc :layout-gap gap)
      (seq pad)              (assoc :layout-padding pad))))

(def ^:private layout-kinds #{"flex" "grid"})

(defn grid-tracks
  "N equal free-space columns, each Penpot's `default-track-value` (`{:type :flex
  :value 1}` — a `1fr` track). Nil/0 columns means \"let calculate-params infer
  the tracks from the children\", which is the auto-grid's whole point, so it
  adds none."
  [columns]
  (if (and (number? columns) (pos? columns))
    (vec (repeat columns {:type :flex :value 1}))
    []))

(defn rebuild-grid
  "Force a grid to N columns and flow its children across them in append order.

  `create-layout-from-id`'s auto-grid infers tracks from child *positions*, so
  near-aligned children collapse into a single column — `columns: N` must mean
  N columns regardless of where the children happen to sit. Resets the tracks and
  cells (like `calculate-params`' empty-children branch, but for a populated
  board) and re-assigns."
  [shape objects n]
  (let [n     (max 1 (int n))
        ;; Canonicalize :shapes from the CURRENT cells before touching them.
        ;; The flip below only yields reading order when :shapes is in the
        ;; reversed-reading canonical order reorder-grid-children produces — but
        ;; Penpot's z-order commands (Send to back / Bring to front) reorder a
        ;; grid's children WITHOUT re-canonicalizing (cells pin the visuals, so
        ;; nothing moves on screen), leaving :shapes out of sync. Rebuilding it
        ;; from the existing cells restores the invariant, so re-asserting
        ;; columns no longer seats children reversed.
        shape (cond-> shape
                (seq (:layout-grid-cells shape))
                (ctl/reorder-grid-children))
        kids  (count (:shapes shape))
        rows  (max 1 (js/Math.ceil (/ kids n)))]
    (-> shape
        (assoc :layout-grid-columns (vec (repeat n ctl/default-track-value))
               :layout-grid-rows    (vec (repeat rows ctl/default-track-value))
               :layout-grid-cells   {})
        (ctl/create-cells [1 1 n rows])
        ;; assign-cells consumes :shapes in VECTOR order, but the canonical
        ;; vector is REVERSED reading order — flip so the first flow item lands
        ;; in the first cell; the reorder below restores the canonical vector
        ;; from the cells. Without this, re-asserting columns seats the LAST
        ;; child in cell 1 (the NYT session's scrambled 2×2).
        (update :shapes (comp vec rseq))
        (ctl/assign-cells objects)
        (ctl/reorder-grid-children))))

(defn reflow-grid-cells
  "Reseat ALL of a grid's children into cells following the :shapes vector's
  flow order — the deterministic answer to \"I re-nested but the grid kept its
  cell assignments\". Keeps the column count, resets manual placements/spans
  (agent-built grids are auto-flowed anyway). Public for tests."
  [shape objects]
  (rebuild-grid shape objects (max 1 (count (:layout-grid-columns shape)))))

(defn layout-problem
  "Why `set_layout` cannot be applied to `id`, as a message the agent can act on,
  or nil. Pure."
  [objects id {:keys [type dir alignItems justifyContent remove] :as input}]
  (let [shape (get objects id)]
    (cond
      (nil? id)
      "set_layout: shapeId is required"

      (nil? shape)
      (dm/str "set_layout: no shape on this page with id " (str id)
              " — check read_design")

      (not (cfh/frame-shape? shape))
      (dm/str "set_layout: " (atc/shape-label shape) " is a "
              (some-> (:type shape) name)
              ", and only boards can have a layout — create one with"
              " create_shape type=board and nest these shapes into it")

      (and remove (nil? (:layout shape)))
      (dm/str "set_layout: " (atc/shape-label shape) " has no layout to remove")

      :else
      (or (atc/enum-problem "set_layout" "type" type layout-kinds)
          (atc/enum-problem "set_layout" "dir" dir layout-dirs)
          (atc/enum-problem "set_layout" "alignItems" alignItems layout-align-items)
          (atc/enum-problem "set_layout" "justifyContent" justifyContent layout-justify-content)
          ;; `type` is itself a change ("make this a grid"), so it satisfies the
          ;; nothing-to-change guard on its own
          (when (and (not remove) (nil? type) (empty? (layout-changes input)))
            (dm/str "set_layout: nothing to change — pass type (flex/grid), dir,"
                    " alignItems, justifyContent, rowGap, columnGap, padding or"
                    " wrap (or remove: true)"))))))

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

(def ^:private layout-sizing #{"fill" "fix" "auto"})

(def ^:private layout-align-self #{"start" "end" "center" "stretch"})

(defn layout-child-attrs
  "The `:layout-item-*` patch for `update-layout-child`, from the tool's public
  params. Only keys actually given are emitted — but note `absolute false` is a
  real instruction (\"rejoin the flow\"), so presence is tested with `some?`
  rather than truthiness."
  [{:keys [horizontalSizing verticalSizing alignSelf margin absolute zIndex
           minWidth maxWidth minHeight maxHeight]}]
  (let [m (cond-> {}
            (some? (:top margin))    (assoc :m1 (:top margin))
            (some? (:right margin))  (assoc :m2 (:right margin))
            (some? (:bottom margin)) (assoc :m3 (:bottom margin))
            (some? (:left margin))   (assoc :m4 (:left margin)))]
    (cond-> {}
      (some? horizontalSizing) (assoc :layout-item-h-sizing (keyword horizontalSizing))
      (some? verticalSizing)   (assoc :layout-item-v-sizing (keyword verticalSizing))
      (some? alignSelf)        (assoc :layout-item-align-self (keyword alignSelf))
      (some? absolute)         (assoc :layout-item-absolute absolute)
      (some? zIndex)           (assoc :layout-item-z-index zIndex)
      (some? minWidth)         (assoc :layout-item-min-w minWidth)
      (some? maxWidth)         (assoc :layout-item-max-w maxWidth)
      (some? minHeight)        (assoc :layout-item-min-h minHeight)
      (some? maxHeight)        (assoc :layout-item-max-h maxHeight)
      (seq m)                  (assoc :layout-item-margin m))))

(defn layout-child-problem
  "Why `set_layout_child` cannot be applied to `id`, or nil. Pure.

  The parent-has-no-layout branch is why this is a separate tool rather than part
  of `modify_shape`: `update-layout-child` writes `:layout-item-*` onto any shape
  quite happily. The attrs persist, do nothing, and spring to life the moment
  someone adds a layout later — a silent no-op wearing a success message.

  One exception mirrors Penpot's own sidebar: a board that ITSELF has a layout
  (the 'Flex board' menu) accepts fix/auto sizing with no laid-out parent —
  that is how a top-level board or component hugs its content. `fill` and the
  flow attrs (alignSelf, margins, zIndex, absolute) still need a laid-out
  parent to mean anything."
  [objects id {:keys [horizontalSizing verticalSizing alignSelf absolute zIndex
                      margin minWidth maxWidth minHeight maxHeight] :as input}]
  (let [shape      (get objects id)
        parent     (some->> (:parent-id shape) (get objects))
        in-layout? (boolean (and parent
                                 (not= uuid/zero (:parent-id shape))
                                 (ctl/any-layout? parent)))
        ;; sizing-only fix/auto on a shape that owns a layout = hug-content
        owner-hug? (boolean (and (ctl/any-layout? shape)
                                 (or (some? horizontalSizing) (some? verticalSizing))
                                 (not= "fill" horizontalSizing)
                                 (not= "fill" verticalSizing)
                                 (nil? alignSelf) (nil? absolute) (nil? zIndex)
                                 (nil? margin) (nil? minWidth) (nil? maxWidth)
                                 (nil? minHeight) (nil? maxHeight)))]
    (cond
      (nil? id)
      "set_layout_child: shapeId is required"

      (nil? shape)
      (dm/str "set_layout_child: no shape on this page with id " (str id)
              " — check read_design")

      (atc/enum-problem "set_layout_child" "horizontalSizing" horizontalSizing layout-sizing)
      (atc/enum-problem "set_layout_child" "horizontalSizing" horizontalSizing layout-sizing)

      (atc/enum-problem "set_layout_child" "verticalSizing" verticalSizing layout-sizing)
      (atc/enum-problem "set_layout_child" "verticalSizing" verticalSizing layout-sizing)

      (atc/enum-problem "set_layout_child" "alignSelf" alignSelf layout-align-self)
      (atc/enum-problem "set_layout_child" "alignSelf" alignSelf layout-align-self)

      (or in-layout? owner-hug?)
      (when (empty? (layout-child-attrs input))
        (dm/str "set_layout_child: nothing to change — pass horizontalSizing,"
                " verticalSizing, alignSelf, margin, absolute, zIndex or"
                " min/max width/height"))

      (ctl/any-layout? shape)
      (dm/str "set_layout_child: " (atc/shape-label shape) " is a layout board with"
              " no laid-out parent — only horizontalSizing/verticalSizing"
              " fix|auto (hug-content) apply here; fill, alignSelf, margins,"
              " zIndex and absolute need it nested inside a laid-out board"
              " (nest_shape)")

      (or (nil? parent) (= uuid/zero (:parent-id shape)))
      (dm/str "set_layout_child: " (atc/shape-label shape) " is not inside a board"
              " — nest it with nest_shape first; or, to make a board hug its"
              " own content, give IT a layout (set_layout) and pass fix/auto"
              " sizing")

      :else
      (dm/str "set_layout_child: the parent of " (atc/shape-label shape)
              " has no layout, so these settings would be stored and do nothing"
              " — call set_layout on board " (dm/str (:parent-id shape)) " first"))))

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
