;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.read
  "The design agent's read tools, as pure functions of file data.

  They take the file and the page explicitly instead of reading an
  application state, so the same code answers `read_design` and
  `find_shapes` in the browser and on the server. Whatever only a running
  workspace knows — the selection, the enabled skills, the open violations —
  is handed in by the caller."
  (:require
   [app.common.agent.token-status :as tst]
   [app.common.data.macros :as dm]
   [app.common.files.variant :as cfv]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;; --- Shape and token summaries

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
        ;; a shape with no radius attrs at all is common in stored files, and
        ;; comparing nil is an error off the browser
        radius (when (some #(and (some? %) (pos? %)) [r1 r2 r3 r4])
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

;; --- find_shapes

;; Tool results are hard-capped by the caller, and `read_design` is the
;; most-called tool there is. Every list returned here is bounded, and every
;; bound announces itself: a list silently cut is read as the whole file, which
;; is worse than a short list that says it is short.

(def ^:private max-listed 60)

(defn find-shapes-problem
  "Why this query cannot be run, or nil."
  [{:keys [name type]}]
  (cond
    (and (nil? name) (nil? type))
    (str "find_shapes: pass a name and/or a type to search for — an empty"
         " query would return the whole file, which is what read_design"
         " is for")

    ;; an unmapped type matches nothing, and "0 found" reads as "none
    ;; exist" — say it was the query that was wrong
    (and (some? type) (nil? (get searchable-types (str/lower type))))
    (dm/str "find_shapes: \"" type "\" is not a shape type — use one of: "
            (str/join ", " (sort (distinct (keys searchable-types)))))))

(defn find-shapes
  "The page's shapes matching `query`. Call `find-shapes-problem` first."
  [objects query]
  (let [hits (->> (vals objects)
                  (filter #(shape-matches? % query))
                  (sort-by :name))
        [items omitted] (bounded hits max-listed "matches")]
    (cond-> {:matches (mapv #(summarize-shape objects (:id %) {:look? true}) items)
             :found (count hits)}
      omitted (assoc :omitted omitted))))

;; --- read_design

(defn library-components
  "Every component the agent can instantiate — this file's and every connected
  library's. Variant members are omitted: they are already listed under
  `:variants` with their component ids, and repeating them here would double the
  payload on a set-heavy file."
  [libraries current-file-id]
  (->> libraries
       (mapcat (fn [[file-id file]]
                 (->> (ctkl/components-seq (:data file))
                      (remove ctc/is-variant?)
                      (map (fn [component]
                             (cond-> {:componentId (dm/str (:id component))
                                      :name (:name component)}
                               ;; the local file is the default target, so saying
                               ;; so on every entry is payload for nothing
                               (not= file-id current-file-id)
                               (assoc :fileId (dm/str file-id)
                                      :library (:name file))))))))
       (vec)))

(defn variant-sets
  "Every variant container on the page with its members and their properties.

  Scans the whole objects map rather than the top level: a container relocated
  into a board still exists, and an agent that cannot see it rebuilds a set that
  is already there. Member order is `find-variant-components`' own (it reverses
  the container's child order deliberately) — do not re-sort it."
  [data objects]
  (->> objects
       (keep (fn [[id shape]]
               (when (ctc/is-variant-container? shape)
                 {:variantId (dm/str id)
                  :name (:name shape)
                  :members (->> (cfv/find-variant-components data objects id)
                                (remove nil?)
                                (mapv (fn [component]
                                        {:componentId (dm/str (:id component))
                                         :name (:name component)
                                         :properties (mapv #(select-keys % [:name :value])
                                                           (:variant-properties component))})))})))
       (vec)))

(defn library-variant-sets
  "The variant sets of every connected library, each with the library's file
  id: `library-components` leaves variants out, and a library built of
  variant sets would otherwise look empty."
  [libraries current-file-id]
  (into []
        (mapcat (fn [[file-id {:keys [data name]}]]
                  (when (not= file-id current-file-id)
                    (->> (:pages data)
                         (mapcat #(variant-sets data (get-in data [:pages-index % :objects])))
                         (map #(assoc % :fileId (dm/str file-id) :library name))))))
        libraries))

(defn sets-and-themes
  "The token library's sets (with active flag) and themes — `read_design`
  flattens tokens into active sets only, so without this the agent cannot see
  that an inactive `modes/dark` exists at all."
  [data]
  (when-let [lib (:tokens-lib data)]
    (let [active (tst/active-set-names data)
          themes (->> (ctob/get-themes lib)
                      (remove #(= ctob/hidden-theme-name (ctob/get-name %)))
                      (mapv (fn [t] (cond-> {:name (ctob/get-name t)
                                             :sets (vec (:sets t))}
                                      (tst/theme-active? data (ctob/get-id t))
                                      (assoc :active true)))))
          sets   (->> (ctob/get-sets lib)
                      (mapv (fn [s] (cond-> {:name (ctob/get-name s)}
                                      (contains? active (ctob/get-name s))
                                      (assoc :active true)))))]
      (cond-> {}
        (seq sets)   (assoc :sets sets)
        (seq themes) (assoc :themes themes)))))

(defn has-design-doc?
  "Whether the file carries a project brief (the vibes foundation)."
  [data]
  (let [doc (get-in data [:plugin-data :penpot-vibes "design-md"])]
    (and (string? doc) (not (str/blank? doc)))))

(defn read-design
  "One-call orientation on a file.

  `view` is what is being looked at: `:file-id`, `:file-name`, the file `:data`,
  the `:page-id` in view, the `:libraries` (file id → file, this file included)
  and the `:selected` shape ids. `extras` are the facts only a running
  workspace knows — `:skills`, `:hasDesignDoc`, `:openViolations` — merged in
  as given; a caller with no workspace leaves out what it cannot know."
  [{:keys [file-id file-name data page-id libraries selected]} extras]
  (let [objects  (get-in data [:pages-index page-id :objects])
        top-ids  (get-in objects [uuid/zero :shapes])
        variants (into (variant-sets data objects) (library-variant-sets libraries file-id))
        comps    (library-components libraries file-id)
        [shapes shapes-omitted]     (bounded top-ids max-listed "top-level shapes")
        [variants variants-omitted] (bounded variants max-listed "variant sets")
        [comps comps-omitted]       (bounded comps max-listed "components")

        omitted (cond-> {}
                  shapes-omitted   (assoc :shapes shapes-omitted)
                  variants-omitted (assoc :variants variants-omitted)
                  comps-omitted    (assoc :components comps-omitted))
        sets    (sets-and-themes data)]
    (cond-> {:file file-name
             :page (get-in data [:pages-index page-id :name])
             ;; every page, so the agent knows others exist and can switch/create;
             ;; the shape lists above are still just THIS page's
             :pages (mapv (fn [pid]
                            (cond-> {:id (dm/str pid)
                                     :name (get-in data [:pages-index pid :name])}
                              (= pid page-id) (assoc :current true)))
                          (:pages data))
             :selection (mapv #(summarize-shape objects % {:look? true})
                              (filter #(contains? objects %) selected))
             :shapes (mapv #(summarize-shape objects %) shapes)
             :variants variants
             :components comps
             :tokens (tokens-by-type (some-> (tst/active-tokens data) vals))}
      (seq extras) (merge extras)

      ;; sets/themes only when the file has any — a single-set file pays nothing
      (seq sets) (merge sets)

      ;; only when something was actually held back — an always-present "nothing
      ;; omitted" key is payload for nothing
      (seq omitted) (assoc :omitted omitted))))

;; --- audit_file
;;
;; A fresh scan of one page for the violations of the rules that are active.
;; A rule that is not active is not checked, so a file with no active rules
;; audits clean.

(def ^:private max-audit-shapes 1000)

(def ^:private default-name-re
  #"(?i)^(rectangle|ellipse|circle|board|frame|group|path|text|image|bool|curve|line|arc)(\s+\d+)?$")

(defn- default-name?
  [shape]
  (boolean (some->> (:name shape) (re-matches default-name-re))))

(defn- raw-colors
  "The fill and stroke colors of a shape that are neither bound to a library
  color nor among the `allowed` ones. `normalize` is the canonical form of a
  color, as `allowed` holds them."
  [shape allowed normalize]
  (concat
   (keep (fn [fill]
           (let [color (:fill-color fill)]
             (when (and color
                        (nil? (:fill-color-ref-id fill))
                        (not (contains? allowed (normalize color))))
               color)))
         (:fills shape))
   (keep (fn [stroke]
           (let [color (:stroke-color stroke)]
             (when (and color
                        (nil? (:stroke-color-ref-id stroke))
                        (not (contains? allowed (normalize color))))
               color)))
         (:strokes shape))))

(defn audit-violations
  "`enforced` is the set of active rule names; `allowed` the colors
  token-only-colors lets through."
  [objects {:keys [enforced allowed normalize]}]
  (let [colors? (contains? enforced "token-only-colors")
        names?  (contains? enforced "layer-naming")
        shapes  (->> (dissoc objects uuid/zero) vals (take max-audit-shapes))]
    (vec
     (concat
      (when colors?
        (for [shape shapes
              :let  [bad (seq (raw-colors shape allowed normalize))]
              :when bad]
          {:rule "token-only-colors" :shapeId (dm/str (:id shape)) :shapeName (:name shape)
           :reason (dm/str "raw colors not bound to a token: " (str/join ", " bad))}))
      (when names?
        (for [shape shapes
              :when (default-name? shape)]
          {:rule "layer-naming" :shapeId (dm/str (:id shape)) :shapeName (:name shape)
           :reason "uses a default/auto-generated layer name"}))))))

(defn audit-result
  [violations]
  {:violations violations
   :count (count violations)
   :note (if (seq violations)
           "fix shape by shape, then run audit_file again to confirm"
           "no open violations for the active rules")})

;; --- render_board
;;
;; Which shapes a render_board call means. Rendering is per shape and is the
;; host's: the workspace draws with its own engine, the exporter with the
;; headless one.

(def max-render-boards 5)

(def default-render-scale 2)

(defn render-scale
  [scale]
  (-> (or scale default-render-scale) (max 1) (min 4)))

(defn- resolve-board
  "Accepts an id or a name. Names are what the model has — `read_design`
  reports them."
  [objects term]
  (or (some->> (uuid/parse* term) (get objects) :id)
      (->> (vals objects)
           (filter #(= term (:name %)))
           (first)
           (:id))))

(defn render-targets
  "`names` (or, with none, the `selected` ids) → one `{:name :id}` or `{:name
  :error}` per shape asked for, capped at `max-render-boards`."
  [objects names selected]
  (->> (or (seq names) (map str selected))
       (take max-render-boards)
       (mapv (fn [term]
               (let [id (when (string? term) (resolve-board objects term))]
                 (cond
                   (nil? id)
                   {:name term :error "no shape with that name or id"}

                   ;; the page root has no size: rendering it gives one grey
                   ;; pixel and no error. Better a refusal naming the fix.
                   (= id uuid/zero)
                   {:name term
                    :error (str "the page as a whole cannot be rendered — "
                                "name the boards you want instead, e.g. "
                                (->> (get-in objects [uuid/zero :shapes])
                                     (keep #(:name (get objects %)))
                                     (take 3)
                                     (str/join ", ")))}

                   :else
                   {:name (or (:name (get objects id)) term) :id id}))))))

(def no-render-targets
  "Nothing to render: pass board names, or ask the user to select something.")

(defn render-failure
  [failed]
  (str "Could not render: "
       (str/join "; " (map #(str (:name %) " — " (:error %)) failed))))

;; Past the soft threshold a render is encoded again as JPEG (a photographic
;; board compresses ~10×; crisp UI rarely crosses it); past the hard budget it
;; is refused with the fix named.
(def jpeg-threshold-b64-chars 300000)

(def max-render-b64-chars 1000000)

(def render-too-large
  (str "render too large to attach even compressed — "
       "render a smaller shape (one section, not the "
       "whole screen) or pass scale 1"))
