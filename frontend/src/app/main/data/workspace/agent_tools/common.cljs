;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.common
  "Shared vocabulary of the agent tool families: shape summaries and
  labels, the token-only-colors enforcement boundary, fill parsing, flex
  flow-order translation and the create-shape builder they all lean on."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctc]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.util.color :as uc]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; --- insert_image

(def http-url-re
  #"(?i)^https?://\S+$")

(defn tokens-lib
  [state]
  (some-> (dsh/lookup-file-data state) :tokens-lib))

;; --- shape types
;;
;; The agent says "board"; the file model says `:frame`. Two mappings, and the
;; difference matters: `create_shape` offers three types and defaults anything
;; else to `:rect` (a lenient default for a tool that only takes three), while
;; `find_shapes` searches the whole vocabulary — there, an unknown type must find
;; NOTHING rather than quietly become `:rect` and return every rectangle on the
;; page as if they were what was asked for.

(defn- shape-type
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

(def text-aligns #{"left" "center" "right" "justify"})

(def searchable-types
  "`find_shapes`' vocabulary → Penpot's. No default: an unmapped name resolves to
  nil and matches nothing."
  {"board" :frame "frame" :frame
   "rect" :rect "rectangle" :rect
   "ellipse" :circle "circle" :circle
   "text" :text
   "group" :group
   "path" :path
   "image" :image
   "bool" :bool})

(defn bounded
  "`[items omission]` — at most `limit` of `coll`, plus a note when it held back.
  The note names `find_shapes` because a cut the agent cannot act on is just a
  cut."
  [coll limit what]
  (let [total (count coll)]
    (if (<= total limit)
      [(vec coll) nil]
      [(vec (take limit coll))
       (dm/str "showing " limit " of " total " " what
               " — use find_shapes to query the rest by name or type")])))

(defn fill-summary
  "A shape's paint, as compact descriptors the agent can recognise and copy —
  not the raw attrs. Exactly one of `:fill-color` / `:fill-color-gradient` /
  `:fill-image` is set per fill (`cfl/valid-fill-attrs`).

  The image case is the point: a raster cannot be reproduced from JSON, so the
  useful answer is \"this is an image, here is its id, name and size\" rather
  than silence — which is what the agent hit when it said it could see the fill
  but could not inspect it."
  [fills]
  (into []
        (keep (fn [{:keys [fill-color fill-opacity fill-color-ref-id
                           fill-color-gradient fill-image]}]
                (cond
                  (some? fill-image)
                  (cond-> {:type "image"
                           :imageId (dm/str (:id fill-image))
                           :width (:width fill-image)
                           :height (:height fill-image)}
                    (:name fill-image)  (assoc :name (:name fill-image))
                    (:mtype fill-image) (assoc :mtype (:mtype fill-image)))

                  (some? fill-color-gradient)
                  {:type "gradient"
                   :gradient (some-> (:type fill-color-gradient) name)
                   :stops (mapv :color (:stops fill-color-gradient))}

                  (some? fill-color)
                  (cond-> {:type "solid" :color fill-color}
                    ;; only when it is not the default — every fill would carry
                    ;; `opacity: 1` otherwise, for nothing
                    (and (some? fill-opacity) (not= 1 fill-opacity))
                    (assoc :opacity fill-opacity)
                    ;; a token-bound fill is a reference to reuse, not a raw
                    ;; colour to copy — the distinction audit-tokens looks for
                    (some? fill-color-ref-id)
                    (assoc :fromToken true)))))
        fills))

(defn effect-summary
  "A shape's effects, in **Phase 14's own write-param names** — `radius`,
  `opacity`, `shadow {style offsetX offsetY blur spread color opacity}` — so a
  read can be handed straight back to `modify_shape` with no translation.

  Only present, non-default attrs are emitted: every shape carries `r1..r4 = 0`
  and `opacity 1`, and reporting those would spend the budget saying nothing.

  `blur` and `blendMode` are reported although `modify_shape` cannot write them.
  The transcript's complaint was a *glow* it could see but not inspect — a blur
  IS that glow. Naming what cannot be reproduced is the same honesty Phase 17
  gives an image fill: better a replica that says \"the original also has an 8px
  blur\" than one that silently drops it."
  [shape]
  (let [{:keys [r1 r2 r3 r4 opacity blend-mode blur shadow strokes]} shape
        live   (remove :hidden shadow)
        ;; stroke depth (Phase 29): width + style beyond the plain color, so a
        ;; 4px dashed border no longer reads identically to a 1px solid one.
        stroke (when-let [s (first strokes)]
                 (cond-> {:color (:stroke-color s)}
                   (some? (:stroke-width s)) (assoc :width (:stroke-width s))
                   (some? (:stroke-style s)) (assoc :style (name (:stroke-style s)))))
        one    (fn [s] (cond-> {:style (some-> (:style s) name)
                                :offsetX (:offset-x s)
                                :offsetY (:offset-y s)
                                :blur (:blur s)
                                :spread (:spread s)
                                :color (:color (:color s))}
                         (some? (:opacity (:color s)))
                         (assoc :opacity (:opacity (:color s)))))
        radius (when (some pos? [r1 r2 r3 r4])
                 (if (apply = [r1 r2 r3 r4])
                   r1
                   ;; Phase 14 writes ONE radius for all four, so a mixed radius
                   ;; is not copyable in a single call — reporting a single
                   ;; number here would be a plausible lie
                   {:topLeft r1 :topRight r2 :bottomRight r3 :bottomLeft r4}))]
    (cond-> {}
      (some? radius)                     (assoc :radius radius)
      (and (some? opacity) (not= 1 opacity)) (assoc :opacity opacity)
      (and (some? blend-mode) (not= :normal blend-mode))
      (assoc :blendMode (name blend-mode))

      (and (some? blur) (not (:hidden blur)))
      (assoc :blur {:type (some-> (:type blur) name) :value (:value blur)})

      (some? stroke) (assoc :stroke stroke)

      (= 1 (count live)) (assoc :shadow (one (first live)))
      (< 1 (count live)) (assoc :shadows (mapv one live)))))

(defn summarize-shape
  "The shape as the agent sees it. Variant keys are added only when truthy —
  `read_design` is called constantly, so a file without variants should not pay
  for the feature in every payload.

  `:look?` — a shape's paint and effects — is off by default and on for the two
  places the look is actually wanted: the **selection** (\"replicate this\") and
  **find_shapes** hits (drill-in). On every shape of the broad list it cost ~11%
  of the 20k budget for a 59-shape file; the constant-cost orientation call stays
  about *what is here*, not *what it looks like*."
  ([objects id] (summarize-shape objects id nil))
  ([objects id {:keys [look?]}]
   (let [shape   (get objects id)
         kids    (count (:shapes shape))
         fills   (when look? (fill-summary (:fills shape)))
         effects (when look? (effect-summary shape))]
     (cond-> {:id (dm/str id)
              :name (:name shape)
              :type (some-> (:type shape) name)
              :x (:x shape)
              :y (:y shape)
              :width (:width shape)
              :height (:height shape)}
       ;; so the agent can tell an empty board from one it cannot see into
       (pos? kids)
       (assoc :childCount kids)

       (seq fills)
       (assoc :fills fills)

       (seq effects)
       (merge effects)

       (ctc/is-variant-container? shape)
       (assoc :isVariantContainer true)

       (ctc/is-variant? shape)
       (assoc :variantId (dm/str (:variant-id shape))
              :variantName (:variant-name shape))

       (:variant-error shape)
       (assoc :variantError (:variant-error shape))

       (:masked-group shape)
       (assoc :isMask true)

       ;; hide/lock flags (Phase 26) — truthy-only, same discipline
       (:hidden shape)
       (assoc :hidden true)

       (:blocked shape)
       (assoc :locked true)

       (and (:rotation shape) (not (zero? (:rotation shape))))
       (assoc :rotation (:rotation shape))))))

(defn- token-summary
  [t]
  (let [value    (str (:value t))
        resolved (some-> (:resolved-value t) str)]
    (cond-> {:name (:name t) :value value}
      ;; only when it differs: an alias's reference is the interesting half, and
      ;; showing only the resolved value hides that this is a reference at all
      (and (some? resolved) (not= resolved value))
      (assoc :resolvedValue resolved))))

(defn tokens-by-type
  "The file's active tokens, grouped under the same public DTCG type names
  `create_token` accepts — so a type read here can be passed straight back.
  Types with no tokens are absent rather than empty."
  [tokens]
  (reduce-kv (fn [acc type toks]
               (assoc acc (get cto/token-type->dtcg-token-type type (name type))
                      (mapv token-summary toks)))
             {}
             (group-by :type tokens)))

(defn shape-matches?
  "Does this shape match the query? `name` is a case-insensitive substring;
  `type` accepts the agent's own vocabulary (\"board\" for `:frame`, as
  `create_shape` takes). An empty query matches nothing — a query that matched
  everything would be a dump, which is what bounding exists to prevent."
  [shape {:keys [name type]}]
  (let [want (some->> type (str/lower) (get searchable-types))]
    (and (or (some? name) (some? type))
         (or (nil? name)
             (and (:name shape)
                  (str/includes? (str/lower (:name shape)) (str/lower name))))
         ;; `type` given but unmapped leaves `want` nil, which matches nothing —
         ;; never everything
         (or (nil? type) (= want (:type shape))))))

;; --- Structural tools (create / modify / nest)
;;
;; Each mutation is emitted through the internal changes pipeline, so it is a
;; normal, undoable Penpot edit. Writes apply to the store synchronously (a
;; following read_design sees them); geometry via the transform modifiers and
;; WASM rendering settle on a later tick — hence the "verify with read_design"
;; note in each result.

(defn interrupt!
  "Clear any transient drawing/edition state before a structural mutation —
  operating mid-drawing leaves the path/draw overlay mounted with stale data
  and crashes its renderer."
  []
  (st/emit! :interrupt))

;; --- token-only-colors enforcement (tool boundary)
;;
;; When the file enforces `token-only-colors`,
;; the color-setting tools accept only colors that are a design token value or a
;; library color; a raw hex is rejected with a rule-tagged error the agent
;; recovers from by using create_token + apply_tokens. Which rules are
;; enforced is read from `[:ai-panel <file-id> :enforced-rules]` (populated by
;; the backend skills resolution — phase 07).

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
      (some-> (uc/parse-css-color s) str/lower))))

(defn allowed-colors
  "Set of normalized hexes allowed under token-only-colors: color-token values
  in active sets + the file's library colors."
  [state]
  (let [fdata (dsh/lookup-file-data state)
        token-values (some->> (:tokens-lib fdata)
                              (ctob/get-tokens-in-active-sets)
                              (vals)
                              (filter #(= :color (:type %)))
                              (keep #(or (:resolved-value %) (:value %))))
        library-colors (->> (vals (:colors fdata)) (keep :color))]
    (into #{} (keep normalize-hex) (concat token-values library-colors))))

(defn rule-enforced?
  [state rule]
  (when-let [file-id (:current-file-id state)]
    (contains? (dm/get-in state [:ai-panel file-id :enforced-rules]) rule)))

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

(defn color-violation
  "Returns a token-only-colors ex-info when `hex` is a raw color that the rule
  forbids, or nil when it is allowed / the rule is not enforced."
  [state hex]
  (when (and (string? hex)
             (seq hex)
             (rule-enforced? state "token-only-colors"))
    (let [allowed (allowed-colors state)]
      (when-not (contains? allowed (normalize-hex hex))
        (ex-info (dm/str "token-only-colors: raw color " hex " is not a design token. "
                         "Create it with create_token (type: color) and bind it with "
                         "apply_tokens, or use an existing token"
                         (when (seq allowed)
                           (dm/str " (allowed: " (str/join ", " (take 20 allowed)) ")"))
                         ".")
                 {:rule "token-only-colors"})))))

(defn reflow-parent!
  "A child added to a laid-out board must flow into it, or it lands at its raw
  x/y outside the layout — a grid gallery stops being a gallery on the next
  card. `:layout/update` only reflows POSITIONS, so grid needs assign-cells
  first (it auto-adds a track for the orphan); flex just needs the reflow."
  [objects pid]
  (let [parent (get objects pid)]
    (cond
      (ctl/grid-layout? parent)
      (do (st/emit! (dwsh/update-shapes [pid]
                                        (fn [p objs] (-> (ctl/assign-cells p objs)
                                                         (ctl/reorder-grid-children)))
                                        {:with-objects? true}))
          (st/emit! (ptk/data-event :layout/update {:ids [pid]})))

      (ctl/flex-layout? parent)
      (st/emit! (ptk/data-event :layout/update {:ids [pid]})))))

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

(defn nest-vector-index
  "The relocate index for nesting `id` into `parent` at flow position
  `flow-index` (0 = first in reading order; nil = end of the flow). For a
  parent without a layout the position is z-order from the back instead.

  Two Penpot facts meet here: flex lays children out in REVERSE vector order
  (so a flow position maps to `count - position`), and `d/insert-at-index`
  applies the index BEFORE removing the moved shape — a same-parent reorder
  toward the end would land one slot short without the `(inc d)`. Public for
  tests."
  [parent id flow-index]
  (let [shapes  (vec (:shapes parent))
        same?   (some #(= id %) shapes)
        o       (when same? (count (take-while #(not= id %) shapes)))
        final-n (if same? (count shapes) (inc (count shapes)))
        f       (-> (or flow-index (dec final-n)) (max 0) (min (dec final-n)))
        ;; d = the desired index in the FINAL vector
        d       (if (and (ctl/any-layout? parent) (not (ctl/reverse? parent)))
                  (- (dec final-n) f)
                  f)]
    (if (and same? (< o d)) (inc d) d)))

(defn create-shape
  [{:keys [type x y width height fill parentId] :as input}]
  ;; via input-colors, not `fill` directly: both colour-setting tools share one
  ;; collector, so a param added later cannot quietly bypass the guard
  (if-let [problem (or (some-> (fill-problem fill) (->> (dm/str "create_shape: ")) (ex-info {}))
                       (some #(color-violation @st/state %) (input-colors input)))]
    (rx/throw problem)
    (let [nm       (:name input)
          state    @st/state
          page     (dsh/lookup-page state)
          objects  (:objects page)
          req-pid  (some-> parentId parse-uuid)
          ;; Hop out of any component copy: its structure is owned by the main
          ;; component, so adding a child directly would corrupt the copy.
          ;; create-and-add-shape guards the same way (shapes.cljs).
          pid      (when (and req-pid (contains? objects req-pid))
                     (:id (ctn/get-first-valid-parent objects req-pid)))
          parent   (get objects pid)
          parent?  (some? parent)
          ;; :frame-id must reference a FRAME. When the parent is a board it is
          ;; itself the frame; when it is a group (or anything else) inherit the
          ;; parent's own frame-id. Setting it to a group id — the old bug —
          ;; violates the data-model invariant and misleads render/selection.
          frame-id (when parent?
                     (if (cfh/frame-shape? parent) pid (:frame-id parent)))
          shape    (cond-> (cts/setup-shape
                            (cond-> {:type (shape-type type)
                                     :x (or x 0) :y (or y 0)
                                     :width (or width 100) :height (or height 100)}
                              nm (assoc :name nm)))
                     fill    (assoc :fills (fill->shape fill))
                     parent? (assoc :parent-id pid :frame-id frame-id))
          changes  (-> (cb/empty-changes)
                       (cb/with-page page)
                       (cb/with-objects objects)
                       ;; into a laid-out board, land at the END of the flow so
                       ;; creation order = reading order (see Flow order above)
                       (cb/add-object shape
                                      (when-let [idx (some-> parent flow-append-index)]
                                        {:index idx})))
          undo-id  (js/Symbol)]
      (interrupt!)
      ;; One undo step for the add AND its grid/flex reflow: without the
      ;; transaction ⌘Z reverts only the reflow and leaves the orphaned shape.
      (st/emit! (dwu/start-undo-transaction undo-id))
      (st/emit! (dch/commit-changes changes))
      (when parent?
        (reflow-parent! objects pid))
      (st/emit! (dwu/commit-undo-transaction undo-id))
      (rx/of {:id (dm/str (:id shape))
              :type type
              :parentId (when parent? (dm/str pid))
              :note "created — verify geometry with read_design"}))))
