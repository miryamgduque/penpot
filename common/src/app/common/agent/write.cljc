;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.write
  "The design agent's tools that change a page, as pure functions.

  A tool is given the page's objects and answers with the changes to commit
  and the result to hand the model. Where things land is not decided here:
  layout belongs to the render engine, so every function that moves or
  resizes something takes a `propagate` function — `(fn [objects entries])` →
  `{shape-id transform}` — and applies what it returns. The workspace and the
  server each bring their own engine instance; the answer is the same.

  A tool answers one of:
    {:changes [...] :result {...}}   what to commit and what to tell the model
    {:problem \"...\" :rule \"...\"}   a refusal the model can act on
    {:fallback \"...\"}              not handled here yet; run it elsewhere"
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;; --- shape types
;;
;; The agent says "board"; the file model says `:frame`. Two mappings, and the
;; difference matters: `create_shape` offers three types and defaults anything
;; else to `:rect` (a lenient default for a tool that only takes three), while
;; `find_shapes` searches the whole vocabulary — there, an unknown type must find
;; NOTHING rather than quietly become `:rect` and return every rectangle on the
;; page as if they were what was asked for.

(defn shape-type
  "`create_shape`'s type → Penpot's. Defaults to `:rect`; safe only because that
  tool offers exactly board / rect / ellipse."
  [type]
  (case type
    "board"   :frame
    "ellipse" :circle
    :rect))

;; --- Shared message helpers
;;
;; Defined once, here, above every user: these were scattered through the file
;; and each new tool that reached for one hit a forward reference. They exist
;; because of the plan's standing rule — a rejection has to name the shape and
;; the fix, so almost every tool needs them.

(defn shape-label
  "How a shape is named back to the agent: \"Name\" (id). Both halves matter —
  the name so the message is readable, the id so it is actionable."
  [shape]
  (dm/str "\"" (:name shape) "\" (" (dm/str (:id shape)) ")"))

(defn labels
  "Several shapes named back to the agent, so one message can list every
  offender and cost one retry instead of one per shape."
  [shapes]
  (str/join ", " (map shape-label shapes)))

(defn enum-problem
  [tool param value allowed]
  (when (and (some? value) (not (contains? allowed value)))
    (dm/str tool ": " param " \"" value "\" is not valid — use one of: "
            (str/join ", " (sort allowed)))))

;; --- token-only-colors
;;
;; When a file enforces `token-only-colors`, the color-setting tools accept
;; only colors that are a design token value or a library color; a raw hex is
;; refused with a message naming the rule, which the agent recovers from by
;; using create_token + apply_tokens.

(def color-rule "token-only-colors")

(defn- parse-css-color
  "Normalizes a CSS color string to #rrggbb. Handles #rrggbb, #rgb, rgb()."
  [raw]
  (let [s (some-> raw str/trim)]
    (when (string? s)
      (if (cc/valid-hex-color? s)
        (-> (subs s 1) cc/expand-hex cc/prepend-hash)
        (some-> (cc/parse-rgb s) cc/rgb->hex)))))

(defn normalize-hex
  "Canonical `#rrggbb` form of a CSS color, or nil.

  Delegates to `uc/parse-css-color` (the codebase's #hex/#rgb/rgb()
  normalizer) rather than only matching bare hex, so a color token stored as
  `rgb(99,102,241)` and the equivalent `#6366f1` compare equal — both sides of
  the token-only-colors check pass through here. A bare hex without `#` (which
  a shape or an agent may hand us) is prefixed first, preserving the previous
  behavior. Named/hsl() forms `parse-css-color` cannot resolve still yield nil."
  [s]
  (when (string? s)
    (let [s (str/trim s)
          s (cond->> s
              (re-matches #"(?i)[0-9a-f]{3}|[0-9a-f]{6}" s) (dm/str "#"))]
      ;; lowercase the result: parse-css-color keeps a 6-digit hex's original
      ;; case (expand-hex only folds shorthand) but emits lowercase for rgb(),
      ;; so #6366F1 and rgb(99,102,241) would otherwise not compare equal.
      (some-> (parse-css-color s) str/lower))))

(defn allowed-colors
  "Set of normalized hexes allowed under token-only-colors: color-token values
  in active sets + the file's library colors."
  [data]
  (let [token-values   (some->> (:tokens-lib data)
                                (ctob/get-tokens-in-active-sets)
                                (vals)
                                (filter #(= :color (:type %)))
                                (keep #(or (:resolved-value %) (:value %))))
        library-colors (->> (vals (:colors data)) (keep :color))]
    (into #{} (keep normalize-hex) (concat token-values library-colors))))

(defn color-problem
  "Why `hex` cannot be written under `guard` — `{:enforced? :allowed}` — or nil
  when it is allowed or the rule is not enforced."
  [{:keys [enforced? allowed]} hex]
  (when (and enforced? (string? hex) (seq hex)
             (not (contains? allowed (normalize-hex hex))))
    (dm/str "token-only-colors: raw color " hex " is not a design token. "
            "Create it with create_token (type: color) and bind it with "
            "apply_tokens, or use an existing token"
            (when (seq allowed)
              (dm/str " (allowed: " (str/join ", " (take 20 allowed)) ")"))
            ".")))

;; --- Fills, written
;;
;; The write shape mirrors `fill-summary`'s read shape, so a descriptor read out
;; of `read_design` can be handed straight back: read → copy → write round-trips,
;; which is exactly what "replicate this element" needs. A bare hex string still
;; works — it was the shipped param.

(def ^:private fill-types #{"solid" "gradient" "image"})

(defn fill-colors
  "Every raw color a fill param carries. A gradient is several colors in a trench
  coat: if the guard only ever saw `fill` as a string, every stop would walk past
  `token-only-colors` untouched."
  [fill]
  (cond
    (string? fill) [fill]
    (map? fill) (case (:type fill)
                  "gradient" (into [] (keep identity) (:stops fill))
                  "image" []
                  (into [] (keep identity) [(:color fill)]))
    :else []))

(defn fill-problem
  "Why this fill cannot be written, or nil. Pure."
  [fill]
  (when (map? fill)
    (let [{:keys [type color stops imageId]} fill]
      (cond
        (not (contains? fill-types type))
        (dm/str "fill: \"" type "\" is not a fill type — use one of: "
                (str/join ", " (sort fill-types)))

        (and (= "solid" type) (not (string? color)))
        "fill: a solid fill needs a color, e.g. {type: \"solid\", color: \"#6366f1\"}"

        (and (= "gradient" type) (< (count stops) 2))
        "fill: a gradient needs at least 2 stops, e.g. {type: \"gradient\", stops: [\"#000000\", \"#ffffff\"]}"

        (and (= "image" type) (not (string? imageId)))
        (str "fill: an image fill needs an imageId — read one off an existing "
             "image fill with read_design or find_shapes; this reuses that raster "
             "rather than approximating it")

        (and (= "image" type) (nil? (parse-uuid imageId)))
        (dm/str "fill: \"" imageId "\" is not a valid imageId")))))

(defn fill->shape
  "Penpot's `:fills` vector for a fill param — a hex string or a descriptor."
  [fill]
  (cond
    (string? fill)
    [{:fill-color fill :fill-opacity 1}]

    (map? fill)
    (case (:type fill)
      "gradient"
      (let [stops (vec (:stops fill))
            n     (max 1 (dec (count stops)))]
        [{:fill-color-gradient
          {:type (keyword (or (:gradient fill) "linear"))
           :start-x 0 :start-y 0 :end-x 0 :end-y 1 :width 1
           ;; spread across the axis — every stop at offset 0 is not a gradient
           :stops (into [] (map-indexed (fn [i c] {:color c :offset (/ i n)})) stops)}}])

      "image"
      [{:fill-image (cond-> {:id (parse-uuid (:imageId fill))
                             :width (or (:width fill) 0)
                             :height (or (:height fill) 0)
                             :mtype (or (:mtype fill) "image/png")}
                      (:name fill) (assoc :name (:name fill)))}]

      [{:fill-color (:color fill) :fill-opacity (or (:opacity fill) 1)}])))

(defn input-colors
  "Every raw color a `create_shape` / `modify_shape` call would put on a shape.

  The shadow is why this exists. `token-only-colors` watched only `fill` and
  `stroke`, so widening `modify_shape` to shadows would have opened a second,
  unwatched path for raw hex — and the most-used one, since a shadow is exactly
  where a designer reaches for a one-off color. One collector, so both tools are
  guarded by construction and a param added later cannot slip past — which is
  what let gradient stops join without touching either tool's guard."
  [{:keys [fill stroke shadow]}]
  (into (fill-colors fill)
        (keep identity)
        [stroke (:color shadow)]))

;; --- Flow order
;;
;; Penpot lays flex children out in REVERSE `:shapes` order for `row`/`column`
;; (flex_layout/layout_data.cljc reverses the children before positioning) and
;; in vector order for the -reverse directions. The tools speak READING order
;; and translate here: "create A, then B" reads A-then-B on canvas, and
;; nest_shape's `index` is the position in the flow (0 = first). Without the
;; translation the model discovers the reversal the hard way and starts
;; authoring row-reverse layouts to compensate — semantically backwards files
;; (the Kahoot session, 2026-07-16).

(defn flow-append-index
  "Vector index at which to insert a NEW child of `parent` so it lands LAST in
  the visual flow (reading order), or nil when the parent has no layout — the
  caller then keeps the default append-on-top z behavior. Public for tests."
  [parent]
  (when (ctl/any-layout? parent)
    (if (ctl/reverse? parent) (count (:shapes parent)) 0)))

(defn shadow->shape
  "A shadow as Penpot stores it. `:color` is a *map* (`schema:color`), not a hex
  string — a bare string fails the schema."
  [{:keys [style offsetX offsetY blur spread color opacity]}]
  {:id (uuid/next)
   :style (if (= style "inner-shadow") :inner-shadow :drop-shadow)
   :offset-x (or offsetX 0)
   :offset-y (or offsetY 0)
   :blur (or blur 4)
   :spread (or spread 0)
   :hidden false
   :color (cond-> {:color (or color "#000000")}
            (some? opacity) (assoc :opacity opacity))})

(defn style-attrs
  "The plain shape attrs for the styling params. Only keys given are emitted, and
  presence is `some?` — `opacity 0` is a real instruction."
  [{:keys [radius opacity]}]
  (cond-> {}
    (some? radius) (assoc :r1 radius :r2 radius :r3 radius :r4 radius)
    (some? opacity) (assoc :opacity opacity)))

(def stroke-styles #{"solid" "dotted" "dashed" "mixed"})

;; Line/path end caps (`:stroke-cap-start` / `:stroke-cap-end` on the stroke).
;; The exact Penpot tokens the model should use, plus "none" to clear a cap and
;; a couple of forgiving aliases ("circle" → the circle-marker dot).
(def ^:private stroke-cap-values
  #{"round" "square" "line-arrow" "triangle-arrow" "square-marker"
    "circle-marker" "diamond-marker" "none"})

(def ^:private stroke-cap-aliases
  {"circle" "circle-marker" "diamond" "diamond-marker" "arrow" "triangle-arrow"})

(def stroke-cap-inputs (into stroke-cap-values (keys stroke-cap-aliases)))

(defn ->stroke-cap
  "A validated cap string → the stored keyword, or nil for \"none\" (clears it)."
  [v]
  (let [v (get stroke-cap-aliases v v)]
    (when-not (= v "none") (keyword v))))

(defn locked-problem
  "Refuses mutating a locked (`:blocked`) shape, unless the call is unlocking it.
  A user locks a layer to mean hands-off; the agent should respect that and say
  how to override. Pure."
  [shape {:keys [locked] :as _input}]
  (when (and (:blocked shape) (not (false? locked)))
    (dm/str "modify_shape: " (shape-label shape) " is locked. A user locks a layer"
            " to keep it as-is — ask before changing it, or unlock it deliberately"
            " with modify_shape locked:false.")))

;; --- modify_shape: validation and attribute writes

(defn modify-problem
  "Why ONE modify_shape-style update cannot be applied, as `{:problem
  message}` (with `:rule` for a guard hit), or nil. `tool` names whichever
  tool surfaced it."
  [tool objects {:keys [shapeId fill strokeStyle strokeCapStart strokeCapEnd] :as input} guard]
  (let [id    (some-> shapeId parse-uuid)
        shape (when id (get objects id))
        plain (fn [message] (when message {:problem message}))]
    (cond
      (nil? id)
      (plain (dm/str tool ": missing or invalid shapeId"))

      (nil? shape)
      (plain (dm/str tool ": no shape with id " shapeId " on this page"))

      :else
      (or (plain (locked-problem shape input))
          (plain (some->> (fill-problem fill) (dm/str tool ": ")))
          (plain (enum-problem tool "strokeStyle" strokeStyle stroke-styles))
          (plain (enum-problem tool "strokeCapStart" strokeCapStart stroke-cap-inputs))
          (plain (enum-problem tool "strokeCapEnd" strokeCapEnd stroke-cap-inputs))
          ;; the guard runs last: a rejection naming the rule is the most
          ;; useful message, so it should not mask a plain input error
          (when-let [message (some #(color-problem guard %) (input-colors input))]
            {:problem message :rule color-rule})))))

(defn attr-update-fn
  "The plain attribute writes of a modify_shape-style update — name, styles,
  shadow, fill, stroke — composed into one function of the shape, or nil when
  the update carries none. Each is an assoc/merge on a distinct key, so one
  pass over the shape applies them all."
  [{:keys [fill stroke shadow strokeWidth strokeStyle strokeCapStart strokeCapEnd
           strokeDash strokeGap]
    :as input}]
  (let [nm     (:name input)
        styles (style-attrs input)
        fns
        (cond-> []
          nm           (conj #(assoc % :name nm))
          (seq styles) (conj #(merge % styles))
          shadow       (conj #(assoc % :shadow [(shadow->shape shadow)]))
          fill         (conj #(assoc % :fills (fill->shape fill)))
          stroke       (conj #(assoc % :strokes [(cond-> {:stroke-color stroke
                                                          :stroke-opacity 1
                                                          :stroke-width (or strokeWidth 1)
                                                          :stroke-style (keyword (or strokeStyle "solid"))
                                                          :stroke-alignment :center}
                                                   (some? strokeCapStart) (assoc :stroke-cap-start (->stroke-cap strokeCapStart))
                                                   (some? strokeCapEnd)   (assoc :stroke-cap-end (->stroke-cap strokeCapEnd))
                                                   (some? strokeDash)     (assoc :stroke-dash strokeDash)
                                                   (some? strokeGap)      (assoc :stroke-gap strokeGap))]))
          ;; stroke width/style/caps/dash change with no new color: patch the
          ;; existing stroke
          (and (nil? stroke) (or (some? strokeWidth) (some? strokeStyle)
                                 (some? strokeCapStart) (some? strokeCapEnd)
                                 (some? strokeDash) (some? strokeGap)))
          (conj (fn [s]
                  (update s :strokes
                          (fn [strokes]
                            (let [st0 (or (first strokes)
                                          {:stroke-color "#000000" :stroke-opacity 1
                                           :stroke-alignment :center})]
                              [(cond-> st0
                                 (some? strokeWidth)    (assoc :stroke-width strokeWidth)
                                 (some? strokeStyle)    (assoc :stroke-style (keyword strokeStyle))
                                 (some? strokeCapStart) (assoc :stroke-cap-start (->stroke-cap strokeCapStart))
                                 (some? strokeCapEnd)   (assoc :stroke-cap-end (->stroke-cap strokeCapEnd))
                                 (some? strokeDash)     (assoc :stroke-dash strokeDash)
                                 (some? strokeGap)      (assoc :stroke-gap strokeGap))]))))))]
    (when (seq fns)
      ;; in the order listed: `comp` applies right to left
      (apply comp (reverse fns)))))

;; --- create_shape: the shape and where it goes

(defn new-shape
  "The shape a create_shape call describes, placed under the right parent →
  `{:shape :parent :index}`. `:index` is where it goes among its siblings, or
  nil for the default.

  It hops out of any component copy: a copy's structure is owned by its main
  component, so adding a child to it directly would corrupt the copy."
  [objects {:keys [type x y width height fill parentId] :as input}]
  (let [nm       (:name input)
        req-pid  (some-> parentId parse-uuid)
        pid      (when (and req-pid (contains? objects req-pid))
                   (:id (ctn/get-first-valid-parent objects req-pid)))
        parent   (get objects pid)
        ;; :frame-id must reference a FRAME. When the parent is a board it is
        ;; itself the frame; when it is a group (or anything else) the shape
        ;; inherits the parent's own frame-id.
        frame-id (when parent
                   (if (cfh/frame-shape? parent) pid (:frame-id parent)))
        shape    (cond-> (cts/setup-shape
                          (cond-> {:type (shape-type type)
                                   :x (or x 0) :y (or y 0)
                                   :width (or width 100) :height (or height 100)}
                            nm (assoc :name nm)))
                   fill   (assoc :fills (fill->shape fill))
                   parent (assoc :parent-id pid :frame-id frame-id))]
    {:shape shape
     :parent parent
     ;; into a laid-out board, land at the END of the flow so creation order
     ;; is reading order
     :index (some-> parent flow-append-index)}))

;; --- Geometry through the engine

(defn- entry
  "One `propagate` entry for `modifiers` on `id`, tagged the way the
  workspace tags it: `:parent` when the modifiers carry parent-level geometry,
  `:child` otherwise."
  [id modifiers]
  [id {:transform (ctm/modifiers->transform modifiers)
       :kind (if (d/not-empty? (:geometry-parent modifiers)) :parent :child)}])

(def reflow-entry
  "Asks the engine to lay a board out again: a unit matrix on the parent."
  (fn [id] [id {:transform (gmt/matrix) :kind :parent}]))

(defn- apply-transforms
  "Appends the changes that move every shape in `transforms` to where the
  engine put it."
  [changes transforms]
  (let [transforms (dissoc transforms uuid/zero)]
    (cond-> changes
      (seq transforms)
      (cb/update-shapes (vec (keys transforms))
                        (fn [shape]
                          (gsh/apply-transform shape (get transforms (:id shape))))
                        {:ignore-touched true}))))

(defn- propagate-into
  "Runs `entries` through the engine on the builder's current objects and
  appends the resulting moves."
  [changes propagate entries]
  (apply-transforms changes (propagate (cb/lookup-objects changes) entries)))

(defn- uses-grid?
  "Whether laying `shape` out involves a grid — its own, its parent's, or one
  somewhere below it."
  [objects shape]
  (or (ctl/grid-layout? shape)
      (ctl/grid-layout? (get objects (:parent-id shape)))
      (some #(ctl/grid-layout? (get objects %))
            (cfh/get-children-ids objects (:id shape)))))

(def ^:private grid-fallback
  "grid layouts are not laid out on the server yet")

;; --- create_shape

(defn create-shape
  "→ `{:changes :result}`, `{:problem}` or `{:fallback}`. `view` is `{:page-id
  :objects}`; `guard` is the token-only-colors state; `propagate` is the
  engine."
  [{:keys [page-id objects]} {:keys [type fill] :as input} {:keys [guard propagate]}]
  (if-let [refusal (or (some->> (fill-problem fill) (dm/str "create_shape: ") (hash-map :problem))
                       (when-let [message (some #(color-problem guard %) (input-colors input))]
                         {:problem message :rule color-rule}))]
    refusal
    (let [{:keys [shape parent index]} (new-shape objects input)]
      (if (ctl/grid-layout? parent)
        {:fallback grid-fallback}
        (let [changes (-> (cb/empty-changes)
                          (cb/with-page-id page-id)
                          (cb/with-objects objects)
                          (cb/add-object shape (when (some? index) {:index index}))
                          ;; a child added to a laid-out board must flow into
                          ;; it, or it lands at its raw x/y outside the layout
                          (cond-> (ctl/flex-layout? parent)
                            (propagate-into propagate [(reflow-entry (:id parent))])))]
          {:changes (:redo-changes changes)
           :result {:id (dm/str (:id shape))
                    :type type
                    :parentId (when parent (dm/str (:id parent)))
                    :note "created — verify geometry with read_design"}})))))

;; --- modify_shape

(defn- position-delta
  "How far to move `shape` so it sits at the requested x/y, which are relative
  to its parent frame, as in the sidebar."
  [objects shape {:keys [x y]}]
  (let [bbox  (-> shape :points grc/points->rect)
        frame (cfh/get-parent-frame objects shape)]
    (gpt/subtract (gpt/point (or (some-> x (+ (:x frame))) (:x bbox))
                             (or (some-> y (+ (:y frame))) (:y bbox)))
                  (gpt/point (:x bbox) (:y bbox)))))

(defn- unsupported
  "Why this update is not handled on the server yet, or nil."
  [objects shape {:keys [x y width height rotation flipH flipV]}]
  (let [geometry? (or (some? x) (some? y) (some? width) (some? height))]
    (cond
      (or (some? rotation) flipH flipV)
      "rotation and flips are not applied on the server yet"

      (and geometry? (uses-grid? objects shape))
      grid-fallback

      ;; resizing a text re-measures it, which needs its fonts in the engine
      (and (or (some? width) (some? height)) (cfh/text-shape? shape))
      "text is not resized on the server yet")))

(defn modify-shape
  "→ `{:changes :result}`, `{:problem}` or `{:fallback}`; arguments as in
  `create-shape`."
  [{:keys [page-id objects]} {:keys [shapeId x y width height hidden locked] :as input}
   {:keys [guard propagate]}]
  (or (modify-problem "modify_shape" objects input guard)
      (let [id    (parse-uuid shapeId)
            shape (get objects id)]
        (if-let [why (unsupported objects shape input)]
          {:fallback why}
          (let [attr-fn (attr-update-fn input)
                step    (fn [changes modifiers-of]
                          ;; each geometry write sees the result of the last
                          (let [shape (get (cb/lookup-objects changes) id)]
                            (propagate-into changes propagate [(entry id (modifiers-of (cb/lookup-objects changes) shape))])))
                changes
                (-> (cb/empty-changes)
                    (cb/with-page-id page-id)
                    (cb/with-objects objects)
                    (cond-> (some? attr-fn)
                      (cb/update-shapes [id] attr-fn))
                    ;; hiding touches the shape alone; locking also locks what
                    ;; is inside it
                    (cond-> (some? hidden)
                      (cb/update-shapes [id] #(assoc % :hidden hidden)))
                    (cond-> (some? locked)
                      (cb/update-shapes (into [id] (cfh/get-children-ids objects id))
                                        #(assoc % :blocked locked)))
                    (cond-> (or (some? x) (some? y))
                      (step (fn [objects shape]
                              (ctm/move-modifiers (position-delta objects shape input)))))
                    (cond-> (some? width)
                      (step (fn [_ shape] (ctm/change-dimensions-modifiers shape :width width))))
                    (cond-> (some? height)
                      (step (fn [_ shape] (ctm/change-dimensions-modifiers shape :height height)))))]
            {:changes (:redo-changes changes)
             :result
             (if-not (or (some? x) (some? y) (some? width) (some? height))
               {:id shapeId :note "modified — verify with read_design"}
               ;; geometry writes can be constrained — a parent layout owns
               ;; the position — so report where the shape actually SETTLED
               ;; instead of claiming the numbers took
               (let [s      (get (cb/lookup-objects changes) id)
                     off?   (fn [want got]
                              (and (some? want) (some? got)
                                   (> (mth/abs (- want got)) 0.5)))
                     ;; compared as the workspace tool compares them
                     drift? (or (off? x (:x s))
                                (off? y (:y s))
                                (off? width (:width s))
                                (off? height (:height s)))]
                 {:id shapeId
                  :settled {:x (:x s) :y (:y s) :width (:width s) :height (:height s)}
                  :note (if drift?
                          (str "modified — but the shape SETTLED at a different "
                               "geometry than requested (see settled). Something "
                               "owns it: a parent layout positions its children "
                               "(use set_layout/set_layout_child or move the "
                               "parent), auto-width text re-measures itself. Do "
                               "not re-send the same x/y.")
                          "modified — settled at the requested geometry")}))})))))
