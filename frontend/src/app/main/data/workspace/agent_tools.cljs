;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools
  "The Agents' native design tools — the CLJS port of the plugin tools in
  `ai-skills/src/ui/agent.ts` + `plugin.ts`. Each tool reads or mutates the
  workspace through Penpot's internal APIs (no plugin runtime, no
  `execute_code`).

  `execute-tool` returns an rx observable of the tool result (a
  JSON-serializable value) or an observable that errors with a
  `{:rule …}`-tagged ex-info (used by the token-only-colors enforcement in a
  later phase). Read tools resolve synchronously; mutating tools (later
  phases) settle asynchronously through the changes pipeline.

  Phase 02 ships the read-only orientation tool `read_design`; structural,
  text/component, token, and audit tools arrive in the following phases."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.variant :as cfv]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctc]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-skills :as ask]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwv]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(declare audit-violations)

;; --- Tool declarations (provider-agnostic; encoded per provider in agent.cljs)

(def tool-specs
  [{:name "read_design"
    :description
    (str "One-call orientation: the current file, page, selection, the page's "
         "top-level shapes, and its variant sets (each with its members and "
         "their properties). Call this FIRST each task to see what is in the "
         "file instead of guessing — including whether a variant set already "
         "exists before building another.")
    :input-schema {:type "object" :properties {}}}

   {:name "get_design_skills"
    :description
    (str "Your playbooks for this file. Called with no argument it lists them "
         "(name, category, mode, what it does) — cheap. Called with a `name` it "
         "returns that skill's FULL playbook: the method, the order to work in, "
         "the critical rules and the checkpoints. When a task matches a skill in "
         "your instructions' skills list, fetch it by name and follow it — do "
         "not guess or reconstruct its content from the blurb.")
    :input-schema {:type "object"
                   :properties {:name {:type "string" :description "return this skill's full playbook"}}}}

   {:name "render_board"
    :description
    (str "Renders boards to images and SHOWS them to you — this is how you "
         "look at the design rather than infer it from JSON. Use it when the "
         "question is visual: does this layout work, do these overlap, is the "
         "spacing even, does that text fit, did my edit land the way I meant. "
         "read_design gives you coordinates; this gives you the picture. "
         "Pass board names (as read_design reports them) or ids; omit `names` "
         "to render the current selection. Up to 5 at once. "
         "Boards and shapes only — there is no way to render 'the page', so "
         "name the boards you want. If it errors, do not retry: say what you "
         "could not see and continue from read_design.")
    :input-schema {:type "object"
                   :properties {:names {:type "array"
                                        :items {:type "string"}
                                        :description "board names or ids; omit for the current selection"}
                                :scale {:type "number"
                                        :description "1-4, default 2; raise only if you need to read small text"}}}}

   {:name "create_shape"
    :description
    (str "Creates a rectangle, ellipse or board (frame) at the given "
         "position/size. Pass parentId to nest it inside a board. Returns the "
         "new shape id. Geometry settles asynchronously — re-read to confirm.")
    :input-schema {:type "object"
                   :properties {:type {:type "string" :enum ["rect" "ellipse" "board"]}
                                :x {:type "number"}
                                :y {:type "number"}
                                :width {:type "number"}
                                :height {:type "number"}
                                :name {:type "string"}
                                :fill {:type "string" :description "solid fill hex, e.g. #6366f1"}
                                :parentId {:type "string" :description "board/group id to nest into"}}
                   :required ["type" "x" "y" "width" "height"]}}

   {:name "modify_shape"
    :description
    (str "Modifies an existing shape: rename, move (x/y), resize (width/"
         "height), and/or set a solid fill/stroke color (hex). Only the given "
         "fields change. Geometry settles asynchronously — re-read to confirm.")
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string"}
                                :name {:type "string"}
                                :x {:type "number"}
                                :y {:type "number"}
                                :width {:type "number"}
                                :height {:type "number"}
                                :fill {:type "string" :description "hex, e.g. #6366f1"}
                                :stroke {:type "string" :description "hex, e.g. #111111"}}
                   :required ["shapeId"]}}

   {:name "nest_shape"
    :description
    "Moves a shape into a parent board/group at an optional index (default 0)."
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string"}
                                :parentId {:type "string"}
                                :index {:type "number"}}
                   :required ["shapeId" "parentId"]}}

   {:name "create_text"
    :description
    (str "Creates an auto-width text shape with the given string at x,y. "
         "Optional fill (hex, default black) and parentId to nest it. Width/"
         "height settle asynchronously — re-read to confirm.")
    :input-schema {:type "object"
                   :properties {:text {:type "string"}
                                :x {:type "number"}
                                :y {:type "number"}
                                :name {:type "string"}
                                :fill {:type "string" :description "hex, default #000000"}
                                :parentId {:type "string"}}
                   :required ["text" "x" "y"]}}

   {:name "create_component"
    :description
    (str "Turns the given shapes (or the current selection if shapeIds is "
         "omitted) into a reusable component. Returns the new component id.")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}}}}}

   {:name "create_variant"
    :description
    (str "Combines two or more main components into a Penpot variant set — the "
         "real thing: a variant container whose members switch by property. The "
         "shapes must already be main components (create_component first) and "
         "must not already belong to a variant set.\n\n"
         "NAME THE COMPONENTS FIRST — the set's name and its properties are "
         "derived from their names, so this is how you design the matrix:\n"
         "  \"Badge / Compact\" + \"Badge / Large\"  -> set \"Badge\", one axis "
         "(Compact | Large)\n"
         "  \"Chip / Small / Hover\" + \"Chip / Large / Default\" -> set \"Chip\", "
         "TWO axes (Small|Large, Hover|Default)\n"
         "The shared leading path becomes the set name; each further segment "
         "becomes another property. Components sharing no path give a set named "
         "\"Component\" — valid, but unreadable. Use create_shape/modify_shape "
         "to name them before combining.\n\n"
         "Input order determines variant order. Never emulate variants by naming "
         "layers \"Prop=Value\" — that is not a variant set. Asynchronous: verify "
         "with read_design.")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}
                                           :description
                                           "main-instance ids; defaults to the selection"}}}}

   {:name "set_variant_property"
    :description
    (str "Names a variant axis, and sets one member's value on it — turns the "
         "placeholder \"Property 1\" into e.g. \"Size\". Renaming an axis "
         "affects every member of the set (properties are uniform by design); a "
         "value applies only to the component given. New sets already take their "
         "values from the component names, so usually only the axis needs a name. "
         "Find the axis and member ids with read_design. Asynchronous.")
    :input-schema {:type "object"
                   :properties {:variantId {:type "string" :description "the variant container id"}
                                :property {:type "string" :description "the axis's current name, e.g. Property 1"}
                                :rename {:type "string" :description "new name for the axis, e.g. Size"}
                                :componentId {:type "string" :description "required when setting a value"}
                                :value {:type "string" :description "this member's value, e.g. Compact"}}
                   :required ["variantId" "property"]}}

   {:name "create_color_token"
    :description
    (str "Creates a color design token in the file's token library (e.g. "
         "name \"color.brand.primary\", value \"#6366f1\"). This is the safe, "
         "reusable way to define colors — apply it to shapes with apply_tokens.")
    :input-schema {:type "object"
                   :properties {:name {:type "string" :description "e.g. color.brand.primary"}
                                :value {:type "string" :description "hex, e.g. #6366f1"}}
                   :required ["name" "value"]}}

   {:name "audit_file"
    :description
    (str "Scans the current page against the file's active rules "
         "(token-only-colors, layer-naming) and returns the open violations "
         "(rule, shape, reason). Use it to ground a fix-up task and to confirm "
         "your fixes cleared the list.")
    :input-schema {:type "object" :properties {}}}

   {:name "apply_tokens"
    :description
    (str "Binds color tokens to shapes in batch — the safe coloring path "
         "(never rejected by token-only-colors). Each application names a "
         "shape, a token, and optionally which properties (fill and/or stroke, "
         "default fill). Application is asynchronous — verify with read_design "
         "or audit_file afterwards, not in the same call.")
    :input-schema {:type "object"
                   :properties {:applications
                                {:type "array"
                                 :items {:type "object"
                                         :properties {:shapeId {:type "string"}
                                                      :tokenName {:type "string" :description "e.g. color.brand.primary"}
                                                      :properties {:type "array"
                                                                   :items {:type "string" :enum ["fill" "stroke"]}}}
                                         :required ["shapeId" "tokenName"]}}}
                   :required ["applications"]}}])

;; --- read_design

(defn summarize-shape
  "The shape as the agent sees it. Variant keys are added only when truthy —
  `read_design` is called constantly, so a file without variants should not pay
  for the feature in every payload."
  [objects id]
  (let [shape (get objects id)]
    (cond-> {:id (dm/str id)
             :name (:name shape)
             :type (some-> (:type shape) name)
             :x (:x shape)
             :y (:y shape)
             :width (:width shape)
             :height (:height shape)}
      (ctc/is-variant-container? shape)
      (assoc :isVariantContainer true)

      (ctc/is-variant? shape)
      (assoc :variantId (dm/str (:variant-id shape))
             :variantName (:variant-name shape))

      (:variant-error shape)
      (assoc :variantError (:variant-error shape)))))

(defn variant-sets
  "Every variant container on the page with its members and their properties —
  the structural view `create_variant` writes and Phase 03/04 target.

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

(defn- read-design
  []
  (let [state    @st/state
        file-id  (:current-file-id state)
        page     (dsh/lookup-page state)
        objects  (dsh/lookup-page-objects state)
        data     (dsh/lookup-file-data state)
        top-ids  (get-in objects [uuid/zero :shapes])
        selected (dsh/get-selected-ids state)
        variants (variant-sets data objects)]
    {:file (get-in state [:files file-id :name])
     :page (:name page)
     :selection (mapv #(summarize-shape objects %) selected)
     :shapes (mapv #(summarize-shape objects %) top-ids)
     :variants variants
     :colorTokens (->> (some-> data :tokens-lib ctob/get-tokens-in-active-sets vals)
                       (filter #(= :color (:type %)))
                       (mapv (fn [t] {:name (:name t) :value (or (:resolved-value t) (:value t))})))
     :skills (ask/catalog-manifest state)
     :openViolations (count (audit-violations state))}))

;; --- render_board
;;
;; The one tool that returns pixels. `render-shape-pixels` draws to a dedicated
;; export surface rather than the viewport, so a board that is scrolled away or
;; zoomed past renders identically — verified byte-identical in Phase 01.
;;
;; It is per-SHAPE: `_render_shape_pixels` takes `(id, scale)` and there is no
;; rectangle, so there is no "render this region" and no "render the page" (the
;; root frame is 0.01×0.01 and returns a 1×1 PNG, silently).

(def ^:private max-render-boards 5)
;; Phase 01 measured scale 2 as the sweet spot: text already legible, ~15kB,
;; ~23ms. Scale 8 costs a 112ms synchronous main-thread block and buys nothing
;; visible.
(def ^:private default-render-scale 2)

(defn- uint8->base64
  "The WASM side hands back raw PNG bytes; the wire wants base64.

  Chunked because `String.fromCharCode` is variadic — spreading a whole
  200kB image across the argument list overflows the stack. 32k is comfortably
  under every engine's limit."
  [^js bytes]
  (let [len (.-length bytes)
        buf (js/Array.)]
    (loop [i 0]
      (when (< i len)
        (let [end (min len (+ i 32768))]
          (.push buf (.apply js/String.fromCharCode nil (.subarray bytes i end)))
          (recur end))))
    (js/btoa (.join buf ""))))

(defn- render-available?
  "The real precondition is the WASM renderer being live for this file — NOT
  `:wasm-export`, which gates Penpot's own export feature, is undeclared in
  `all-flags`, and is off everywhere. Without render-wasm the shape tree is not
  loaded and the call aborts the WASM module rather than returning."
  [state]
  (features/active-feature? state "render-wasm/v1"))

(defn- resolve-board
  "Accepts an id or a name. Names are what the model actually has — `read_design`
  reports them — and it should not have to care that Penpot thinks in uuids."
  [objects term]
  ;; `parse*` and not `uuid/uuid`: the latter is documented UNSAFE and will
  ;; happily build a nonsense uuid out of a board called \"Card\"
  (or (some->> (uuid/parse* term) (get objects) :id)
      (->> (vals objects)
           (filter #(= term (:name %)))
           (first)
           (:id))))

(defn- render-board
  [{:keys [names scale]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        scale   (-> (or scale default-render-scale) (max 1) (min 4))
        terms   (or (seq names)
                    (->> (dsh/get-selected-ids state) (mapv str)))]
    (cond
      (not (render-available? state))
      (rx/throw (ex-info (str "Rendering is unavailable: this file is using the SVG renderer. "
                              "The user can switch it on in Settings › Options › "
                              "\"Use WebGL renderer\". Carry on with read_design instead.")
                         {}))

      (empty? terms)
      (rx/throw (ex-info "Nothing to render: pass board names, or ask the user to select something." {}))

      :else
      (let [terms   (vec (take max-render-boards terms))
            results (mapv (fn [term]
                            (let [id (resolve-board objects term)]
                              (cond
                                (nil? id)
                                {:name term :error "no shape with that name or id"}

                                ;; The page root has no dimensions, so rendering
                                ;; it returns a 1×1 PNG with no error at all —
                                ;; the agent would "see the page", get a single
                                ;; grey pixel, and describe it in good faith.
                                ;; Better a refusal that names the alternative.
                                (= id uuid/zero)
                                {:name term
                                 :error (str "the page as a whole cannot be rendered — "
                                             "name the boards you want instead, e.g. "
                                             (->> (get-in objects [uuid/zero :shapes])
                                                  (keep #(:name (get objects %)))
                                                  (take 3)
                                                  (str/join ", ")))}

                                :else
                                (try
                                  (let [bytes (wasm.api/render-shape-pixels id scale)]
                                    {:name (or (:name (get objects id)) term)
                                     :id (str id)
                                     ;; base64 of the PNG the WASM side wrote
                                     :data (uint8->base64 bytes)})
                                  (catch :default cause
                                    {:name term :error (or (ex-message cause) "render failed")})))))
                          terms)
            ok      (filterv :data results)
            failed  (filterv :error results)]
        (if (empty? ok)
          (rx/throw (ex-info (str "Could not render: "
                                  (str/join "; " (map #(str (:name %) " — " (:error %)) failed)))
                             {}))
          ;; `:images` is lifted out before the rest is JSON-stringified into the
          ;; tool result — otherwise 200kB of base64 would be truncated into the
          ;; 20k-char content string and the model would see a mangled prefix.
          (rx/of {:images (mapv (fn [r] {:mtype "image/png" :data (:data r)}) ok)
                  :rendered (mapv (fn [r] (select-keys r [:name :id])) ok)
                  :failed (when (seq failed) (mapv #(select-keys % [:name :error]) failed))
                  :scale scale}))))))

;; --- Structural tools (create / modify / nest)
;;
;; Each mutation is emitted through the internal changes pipeline, so it is a
;; normal, undoable Penpot edit. Writes apply to the store synchronously (a
;; following read_design sees them); geometry via the transform modifiers and
;; WASM rendering settle on a later tick — hence the "verify with read_design"
;; note in each result.

(defn- interrupt!
  "Clear any transient drawing/edition state before a structural mutation —
  operating mid-drawing leaves the path/draw overlay mounted with stale data
  and crashes its renderer."
  []
  (st/emit! :interrupt))

(defn- shape-type
  [type]
  (case type
    "board"   :frame
    "ellipse" :circle
    :rect))

;; --- token-only-colors enforcement (tool boundary)
;;
;; Port of skills-core/src/guard.ts. When the file enforces `token-only-colors`,
;; the color-setting tools accept only colors that are a design token value or a
;; library color; a raw hex is rejected with a rule-tagged error the agent
;; recovers from by using create_color_token + apply_tokens. Which rules are
;; enforced is read from `[:ai-panel <file-id> :enforced-rules]` (populated by
;; the backend skills resolution — phase 07).

(defn- normalize-hex
  "Lowercase, `#`-prefixed 6-digit hex, or nil for a non-hex value."
  [s]
  (when (string? s)
    (let [h (cond-> (str/lower (str/trim s))
              (str/starts-with? (str/lower (str/trim s)) "#") (subs 1))]
      (cond
        (= 3 (count h)) (dm/str "#" (apply str (mapcat #(list % %) h)))
        (= 6 (count h)) (dm/str "#" h)
        :else nil))))

(defn- allowed-colors
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

(defn- rule-enforced?
  [state rule]
  (when-let [file-id (:current-file-id state)]
    (contains? (dm/get-in state [:ai-panel file-id :enforced-rules]) rule)))

(defn- color-violation
  "Returns a token-only-colors ex-info when `hex` is a raw color that the rule
  forbids, or nil when it is allowed / the rule is not enforced."
  [state hex]
  (when (and (string? hex)
             (seq hex)
             (rule-enforced? state "token-only-colors"))
    (let [allowed (allowed-colors state)]
      (when-not (contains? allowed (normalize-hex hex))
        (ex-info (dm/str "token-only-colors: raw color " hex " is not a design token. "
                         "Create it with create_color_token and bind it with apply_tokens, "
                         "or use an existing token"
                         (when (seq allowed)
                           (dm/str " (allowed: " (str/join ", " (take 20 allowed)) ")"))
                         ".")
                 {:rule "token-only-colors"})))))

(defn- create-shape
  [{:keys [type x y width height fill parentId] :as input}]
  (if-let [violation (color-violation @st/state fill)]
    (rx/throw violation)
    (let [nm      (:name input)
          state   @st/state
          page    (dsh/lookup-page state)
          objects (:objects page)
          pid     (some-> parentId parse-uuid)
          parent? (boolean (and pid (contains? objects pid)))
          shape   (cond-> (cts/setup-shape
                           (cond-> {:type (shape-type type)
                                    :x (or x 0) :y (or y 0)
                                    :width (or width 100) :height (or height 100)}
                             nm (assoc :name nm)))
                    fill    (assoc :fills [{:fill-color fill :fill-opacity 1}])
                    parent? (assoc :parent-id pid :frame-id pid))
          changes (-> (cb/empty-changes)
                      (cb/with-page page)
                      (cb/with-objects objects)
                      (cb/add-object shape))]
      (interrupt!)
      (st/emit! (dch/commit-changes changes))
      (rx/of {:id (dm/str (:id shape))
              :type type
              :parentId (when parent? (dm/str pid))
              :note "created — verify geometry with read_design"}))))

(defn- modify-shape
  [{:keys [shapeId x y width height fill stroke] :as input}]
  (let [nm    (:name input)
        id    (some-> shapeId parse-uuid)
        state @st/state]
    (if-let [violation (or (color-violation state fill) (color-violation state stroke))]
      (rx/throw violation)
      (if (nil? id)
        (rx/throw (ex-info "modify_shape: missing or invalid shapeId" {}))
        (let [tx (random-uuid)]
          (interrupt!)
          (st/emit! (dwu/start-undo-transaction tx))
          (when nm
            (st/emit! (dwsh/update-shapes [id] #(assoc % :name nm))))
          (when (or (some? x) (some? y))
            (st/emit! (dwt/update-position id (cond-> {}
                                                (some? x) (assoc :x x)
                                                (some? y) (assoc :y y)))))
          (when (some? width)
            (st/emit! (dwt/update-dimensions [id] :width width)))
          (when (some? height)
            (st/emit! (dwt/update-dimensions [id] :height height)))
          (when fill
            (st/emit! (dwsh/update-shapes [id] #(assoc % :fills [{:fill-color fill :fill-opacity 1}]))))
          (when stroke
            (st/emit! (dwsh/update-shapes [id] #(assoc % :strokes [{:stroke-color stroke
                                                                    :stroke-opacity 1
                                                                    :stroke-width 1
                                                                    :stroke-style :solid
                                                                    :stroke-alignment :center}]))))
          (st/emit! (dwu/commit-undo-transaction tx))
          (rx/of {:id shapeId :note "modified — verify with read_design"}))))))

(defn- nest-shape
  [{:keys [shapeId parentId index]}]
  (let [id  (some-> shapeId parse-uuid)
        pid (some-> parentId parse-uuid)]
    (if (and id pid)
      (do (interrupt!)
          (st/emit! (dwsh/relocate-shapes #{id} pid (or index 0)))
          (rx/of {:id shapeId :parentId parentId
                  :note "reparented — verify with read_design"}))
      (rx/throw (ex-info "nest_shape: missing or invalid shapeId/parentId" {})))))

;; --- Text & component tools

(defn- create-text
  [{:keys [text x y parentId fill] :as input}]
  (if (or (not (string? text)) (empty? text))
    (rx/throw (ex-info "create_text: text must be a non-empty string" {}))
    (let [nm      (:name input)
          color   (or fill "#000000")
          state   @st/state
          page    (dsh/lookup-page state)
          objects (:objects page)
          pid     (some-> parentId parse-uuid)
          parent? (boolean (and pid (contains? objects pid)))
          shape   (-> (cts/setup-shape
                       (cond-> {:type :text
                                :x (or x 0) :y (or y 0)
                                :width 1 :height 1
                                :grow-type :auto-width}
                         nm (assoc :name nm)))
                      (update :content txt/change-text text
                              {:fills [{:fill-color color :fill-opacity 1}]})
                      (dissoc :position-data)
                      (cond-> parent? (assoc :parent-id pid :frame-id pid)))
          changes (-> (cb/empty-changes)
                      (cb/with-page page)
                      (cb/with-objects objects)
                      (cb/add-object shape))]
      (interrupt!)
      (st/emit! (dch/commit-changes changes))
      (when (features/active-feature? @st/state "render-wasm/v1")
        (st/emit! (dwwt/resize-wasm-text-debounce (:id shape))))
      (rx/of {:id (dm/str (:id shape))
              :note "text created — size settles async; verify with read_design"}))))

(defn- create-component
  [{:keys [shapeIds]}]
  (let [ids (if (seq shapeIds)
              (into #{} (keep parse-uuid) shapeIds)
              (dsh/get-selected-ids @st/state))]
    (if (empty? ids)
      (rx/throw (ex-info "create_component: no shapes given and nothing selected" {}))
      (try
        (let [id-ref (atom nil)]
          (interrupt!)
          (st/emit! (dwl/add-component id-ref ids))
          (if-let [cid (deref id-ref)]
            (rx/of {:componentId (dm/str cid) :note "component created"})
            (rx/throw (ex-info "create_component: shapes are not eligible for a component" {}))))
        (catch :default e
          (rx/throw e))))))

;; --- Variants
;;
;; `dwv/combine-as-variants` filters out ids that are not main instances or are
;; already variants, and no-ops entirely below two ids — all silently. Silence
;; is the one answer the agent cannot act on: with no signal it improvises
;; something that resembles the capability (frames named `Card=Size=Compact`).
;; So the eligibility rules are checked here first, mirroring the event's own
;; filter, and rejected with a message that names the corrective action.

(defn- shape-label
  [shape]
  (dm/str "\"" (:name shape) "\" (" (dm/str (:id shape)) ")"))

(defn- labels
  [shapes]
  (str/join ", " (map shape-label shapes)))

(defn variant-member-ids
  "The parsed, de-duplicated member ids from a `create_variant` input, in the
  order given — `combine-as-variants` honours the order of a sequential
  collection and normalizes anything else to layer-tree order."
  [shapeIds]
  (into [] (comp (keep parse-uuid) (distinct)) shapeIds))

(defn variant-naming-hint
  "Nil when `names` share a path prefix; otherwise a note explaining what the
  agent is about to get and how to ask for better.

  `combine-as-variants` derives the set's name from the common *path* prefix of
  its members (`variants.cljs:667`). With no shared prefix, `transform-in-variant`
  falls back to `\"Component/\" + name` (`:413`), so the set is called
  \"Component\" and the member's own name becomes the first property's value.
  Path depth also decides how many axes the set gets — `num-props` is
  `(max 1 (dec (count cpath)))` at `:426`.

  So `Badge / Compact` + `Badge / Large` gives a set named \"Badge\" with one
  axis, and `Chip / Small / Hover` gives \"Chip\" with two. The operation is
  valid either way — this is a hint, not a rejection."
  [names]
  (let [paths  (mapv #(cpn/split-path (or % "")) names)
        shared (->> (apply map vector paths)
                    (take-while #(apply = %))
                    (map first)
                    (vec))]
    (when (empty? shared)
      (dm/str "note: these components share no common path, so Penpot named the set"
              " \"Component\" and used each member's own name as its first property"
              " value. To get a named set, name the components with a shared path"
              " first — e.g. \"Badge / Compact\" and \"Badge / Large\" produce a set"
              " called \"Badge\". Each extra path segment adds another property:"
              " \"Chip / Small / Hover\" gives two."))))

(defn variant-members-problem
  "Why `ids` cannot become a variant set, as a message the agent can act on, or
  nil when they can. Pure: `objects` is the current page's shape map."
  [objects ids]
  ;; distinct first: [a a] reaches combine-as-variants as one id and no-ops
  ;; silently, so it must not read as two members here
  (let [ids      (distinct ids)
        shapes   (keep #(get objects %) ids)
        missing  (remove #(contains? objects %) ids)
        ;; a variant member is also a main instance, so this branch is checked
        ;; first — "extend the set you already have" beats "make a component"
        variants (filter ctc/is-variant? shapes)
        non-main (remove ctc/main-instance? shapes)]
    (cond
      (< (count ids) 2)
      (dm/str "create_variant: needs at least 2 main components (got " (count ids)
              ") — a variant set is a comparison between members")

      (seq missing)
      (dm/str "create_variant: no shape on this page with id "
              (str/join ", " (map str missing))
              " — it may be on another page, or gone; check read_design")

      (seq variants)
      (dm/str "create_variant: " (labels variants)
              (if (= 1 (count variants)) " is" " are")
              " already part of a variant set — use add_variant to extend it")

      (seq non-main)
      (dm/str "create_variant: " (labels non-main)
              (if (= 1 (count non-main))
                " is not a main component — call create_component on it first"
                " are not main components — call create_component on them first")
              ", then pass the main instance")

      :else nil)))

(defn- create-variant
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (if (seq shapeIds)
                  (variant-member-ids shapeIds)
                  (vec (dsh/get-selected-ids state)))]
    (if-let [problem (variant-members-problem objects ids)]
      (rx/throw (ex-info problem {}))
      ;; the container's id *is* the variant-id, so pre-generating it lets us
      ;; return the id without awaiting the event chain (as api.cljs does)
      (let [variant-id (uuid/next)
            hint       (variant-naming-hint (map #(:name (get objects %)) ids))]
        (interrupt!)
        (st/emit! (dwv/combine-as-variants
                   ids {:trigger "agent:create_variant" :variant-id variant-id}))
        (rx/of (cond-> {:variantId (dm/str variant-id)
                        :members (count ids)
                        :note (str "variant set created — its name and properties come from "
                                   "the members' names. Axes are called \"Property 1\"…; "
                                   "name them with set_variant_property. Verify with read_design.")}
                 hint (assoc :namingHint hint)))))))

;; --- Variant properties
;;
;; `dwv/update-property-name` guards on `valid-pos?` and does nothing when the
;; index is out of range (variants.cljs:131) — another silent no-op. It reads the
;; property list from the *last* of `find-variant-components`, so positions are
;; resolved against that same component: a pos we resolve is a pos it accepts.

(defn axis-pos
  "The index of the axis named `property` among `axes`, or nil.

  Callers must resolve this *before* emitting a rename — the index is stable but
  the name is what we look up, so resolving afterwards finds nothing."
  [axes property]
  (first (keep-indexed (fn [i n] (when (= n property) i)) axes)))

(defn variant-property-problem
  "Why a `set_variant_property` call cannot be applied, as a message the agent
  can act on, or nil. Pure: `axes` are the set's property names in order,
  `member-ids` its component ids as strings."
  [{:keys [container? axes member-ids]} {:keys [variantId property rename componentId value]}]
  (cond
    (not container?)
    (dm/str "set_variant_property: " variantId " is not a variant container"
            " — build one with create_variant, or see read_design for the sets that exist")

    (nil? (axis-pos axes property))
    (dm/str "set_variant_property: no axis named \"" property "\" on this set"
            " (it has: " (str/join ", " axes) ") — pass one of those")

    (and (nil? rename) (nil? value))
    (dm/str "set_variant_property: nothing to do — pass rename to name the axis,"
            " and/or value together with componentId to set one member's value")

    (and (some? value) (nil? componentId))
    (dm/str "set_variant_property: setting a value needs componentId — a value"
            " belongs to one member, not the whole set")

    (and (some? componentId) (not (contains? member-ids componentId)))
    (dm/str "set_variant_property: component " componentId " is not a member of"
            " variant set " variantId " — see read_design")

    :else nil))

(defn- variant-facts
  "The set's shape as `variant-property-problem` wants it. Axes come from the
  same component `update-property-name` reads, so a resolved pos is an accepted
  pos."
  [data objects variant-id]
  (let [components (cfv/find-variant-components data objects variant-id)]
    {:container? (ctc/is-variant-container? (get objects variant-id))
     :axes (mapv :name (:variant-properties (last components)))
     :member-ids (into #{} (comp (remove nil?) (map #(str (:id %)))) components)}))

(defn- set-variant-property
  [{:keys [variantId property rename componentId value] :as input}]
  (let [state   @st/state
        data    (dsh/lookup-file-data state)
        objects (dsh/lookup-page-objects state)
        vid     (parse-uuid variantId)
        facts   (if vid
                  (variant-facts data objects vid)
                  {:container? false :axes [] :member-ids #{}})]
    (if-let [problem (variant-property-problem facts input)]
      (rx/throw (ex-info problem {}))
      ;; resolve the index from the current name before any rename lands
      (let [pos (axis-pos (:axes facts) property)]
        (interrupt!)
        (when rename
          (st/emit! (dwv/update-property-name vid pos rename
                                              {:trigger "agent:set_variant_property"})))
        (when (and componentId value)
          (st/emit! (dwv/update-property-value (parse-uuid componentId) pos value)))
        (rx/of {:axis (or rename property)
                :note (str "updated — the axis is set-wide, values are per member. "
                           "Verify with read_design.")})))))

;; --- Token tools (the safe coloring path)

(defn- create-color-token
  [{:keys [name value]}]
  (if (or (not (string? name)) (empty? name) (not (string? value)) (empty? value))
    (rx/throw (ex-info "create_color_token: name and value (hex) are required" {}))
    (let [token (ctob/make-token {:type :color :name name :value value})]
      ;; 1-arg create-token targets the current set, creating one if none exists
      (st/emit! (dwtl/create-token token))
      (rx/of {:name name :value value :note "color token created"}))))

(defn- attr-set
  "Maps the requested properties to shape color attributes (default fill)."
  [properties]
  (into #{} (map #(if (= % "stroke") :stroke-color :fill))
        (if (seq properties) properties ["fill"])))

(defn- apply-tokens
  [{:keys [applications]}]
  (if (empty? applications)
    (rx/throw (ex-info "apply_tokens: no applications given" {}))
    (let [all-tokens (some-> (dsh/lookup-file-data @st/state) :tokens-lib ctob/get-all-tokens-map)]
      (interrupt!)
      (let [results
            (mapv (fn [{:keys [shapeId tokenName properties]}]
                    (let [id    (some-> shapeId parse-uuid)
                          token (get all-tokens tokenName)]
                      (if (and id token)
                        (do (st/emit! (dwta/toggle-token {:token token
                                                          :attrs (attr-set properties)
                                                          :shape-ids [id]
                                                          :expand-with-children false}))
                            {:shapeId shapeId :token tokenName :ok true})
                        {:shapeId shapeId :token tokenName :ok false
                         :error (cond (nil? id) "invalid shapeId"
                                      (nil? token) "token not found"
                                      :else "unknown")})))
                  applications)]
        (rx/of {:results results
                :note "tokens resolve asynchronously — verify with read_design or audit_file"})))))

;; --- Skills

(defn- get-design-skills
  [{:keys [name]}]
  (let [state @st/state]
    (rx/of (if name
             (or (ask/catalog-manifest state name)
                 ;; A miss is usually the human label ("Accessibility audit")
                 ;; rather than the key ("penpot-audit-accessibility"). Hand back
                 ;; the valid names so the retry is one round, not a guess.
                 {:error (dm/str "No skill named \"" name "\". Use one of the names below "
                                 "(the `name` field, not the label).")
                  :available (mapv :name (ask/catalog-manifest state))})
             (ask/catalog-manifest state)))))

;; --- audit_file
;;
;; On-demand scan of the current page for the active rules' violations (a fresh
;; scan, no persistent ledger — that watcher is a later story). A rule is
;; checked only when it is active (`enforced-rules`), so a file with no active
;; rules audits clean. Reuses the Phase 06 allowed-colors logic.

(def ^:private max-audit-shapes 1000)

(def ^:private default-name-re
  #"(?i)^(rectangle|ellipse|circle|board|frame|group|path|text|image|bool|curve|line|arc)(\s+\d+)?$")

(defn- default-name?
  [shape]
  (boolean (some->> (:name shape) (re-matches default-name-re))))

(defn- shape-raw-colors
  "Raw (non-token, non-allowed) fill/stroke colors on a shape."
  [shape allowed]
  (concat
   (keep (fn [f]
           (let [c (:fill-color f)]
             (when (and c (nil? (:fill-color-ref-id f)) (not (contains? allowed (normalize-hex c)))) c)))
         (:fills shape))
   (keep (fn [s]
           (let [c (:stroke-color s)]
             (when (and c (nil? (:stroke-color-ref-id s)) (not (contains? allowed (normalize-hex c)))) c)))
         (:strokes shape))))

(defn audit-violations
  [state]
  (let [objects       (dsh/lookup-page-objects state)
        check-colors? (rule-enforced? state "token-only-colors")
        check-names?  (rule-enforced? state "layer-naming")
        allowed       (when check-colors? (allowed-colors state))
        shapes        (->> (dissoc objects uuid/zero) vals (take max-audit-shapes))]
    (vec
     (concat
      (when check-colors?
        (for [s shapes
              :let [bad (seq (shape-raw-colors s allowed))]
              :when bad]
          {:rule "token-only-colors" :shapeId (dm/str (:id s)) :shapeName (:name s)
           :reason (dm/str "raw colors not bound to a token: " (str/join ", " bad))}))
      (when check-names?
        (for [s shapes
              :when (default-name? s)]
          {:rule "layer-naming" :shapeId (dm/str (:id s)) :shapeName (:name s)
           :reason "uses a default/auto-generated layer name"}))))))

(defn- audit-file
  []
  (let [violations (audit-violations @st/state)]
    (rx/of {:violations violations
            :count (count violations)
            :note (if (seq violations)
                    "fix shape by shape, then run audit_file again to confirm"
                    "no open violations for the active rules")})))

;; --- Dispatch

(defn execute-tool
  [name input]
  (case name
    "read_design"        (rx/of (read-design))
    "render_board"       (render-board input)
    "get_design_skills"  (get-design-skills input)
    "audit_file"         (audit-file)
    "create_shape"       (create-shape input)
    "modify_shape"       (modify-shape input)
    "nest_shape"         (nest-shape input)
    "create_text"        (create-text input)
    "create_component"   (create-component input)
    "create_variant"     (create-variant input)
    "set_variant_property" (set-variant-property input)
    "create_color_token" (create-color-token input)
    "apply_tokens"       (apply-tokens input)
    (rx/throw (ex-info (dm/str "Unknown tool: " name) {}))))
