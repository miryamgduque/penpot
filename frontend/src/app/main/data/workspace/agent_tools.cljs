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
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.features :as features]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(declare audit-violations)

;; --- Tool declarations (provider-agnostic; encoded per provider in agent.cljs)

(def tool-specs
  [{:name "read_design"
    :description
    (str "One-call orientation: the current file, page, selection and the "
         "page's top-level shapes. Call this FIRST each task to see what is in "
         "the file instead of guessing.")
    :input-schema {:type "object" :properties {}}}

   {:name "get_design_skills"
    :description
    (str "Returns the design skills available for this file — your playbooks "
         "(name, category, mode, what it does). Call this before a task that "
         "matches one and follow it. Pass a name for one skill's details.")
    :input-schema {:type "object"
                   :properties {:name {:type "string" :description "return one skill's details"}}}}

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

(defn- summarize-shape
  [objects id]
  (let [shape (get objects id)]
    {:id (dm/str id)
     :name (:name shape)
     :type (some-> (:type shape) name)
     :x (:x shape)
     :y (:y shape)
     :width (:width shape)
     :height (:height shape)}))

(defn- read-design
  []
  (let [state    @st/state
        file-id  (:current-file-id state)
        page     (dsh/lookup-page state)
        objects  (dsh/lookup-page-objects state)
        top-ids  (get-in objects [uuid/zero :shapes])
        selected (dsh/get-selected-ids state)]
    {:file (get-in state [:files file-id :name])
     :page (:name page)
     :selection (mapv #(summarize-shape objects %) selected)
     :shapes (mapv #(summarize-shape objects %) top-ids)
     :colorTokens (->> (some-> (dsh/lookup-file-data state) :tokens-lib ctob/get-tokens-in-active-sets vals)
                       (filter #(= :color (:type %)))
                       (mapv (fn [t] {:name (:name t) :value (or (:resolved-value t) (:value t))})))
     :skills (ask/catalog-manifest state)
     :openViolations (count (audit-violations state))}))

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
                 {:error (dm/str "no skill named " name)})
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
    "get_design_skills"  (get-design-skills input)
    "audit_file"         (audit-file)
    "create_shape"       (create-shape input)
    "modify_shape"       (modify-shape input)
    "nest_shape"         (nest-shape input)
    "create_text"        (create-text input)
    "create_component"   (create-component input)
    "create_color_token" (create-color-token input)
    "apply_tokens"       (apply-tokens input)
    (rx/throw (ex-info (dm/str "Unknown tool: " name) {}))))
