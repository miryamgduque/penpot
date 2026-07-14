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
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.features :as features]
   [app.main.store :as st]
   [beicon.v2.core :as rx]))

;; --- Tool declarations (provider-agnostic; encoded per provider in agent.cljs)

(def tool-specs
  [{:name "read_design"
    :description
    (str "One-call orientation: the current file, page, selection and the "
         "page's top-level shapes. Call this FIRST each task to see what is in "
         "the file instead of guessing.")
    :input-schema {:type "object" :properties {}}}

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
                   :properties {:shapeIds {:type "array" :items {:type "string"}}}}}])

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
     ;; Seams filled by later phases:
     :colorTokens []     ; Phase 05 (token tools)
     :skills []          ; Phase 07 (skills resolution)
     :openViolations 0})) ; Phase 08 (audit_file)

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

(defn- create-shape
  [{:keys [type x y width height fill parentId] :as input}]
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
            :note "created — verify geometry with read_design"})))

(defn- modify-shape
  [{:keys [shapeId x y width height fill stroke] :as input}]
  (let [nm (:name input)
        id (some-> shapeId parse-uuid)]
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
        (rx/of {:id shapeId :note "modified — verify with read_design"})))))

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

;; --- Dispatch

(defn execute-tool
  [name input]
  (case name
    "read_design"      (rx/of (read-design))
    "create_shape"     (create-shape input)
    "modify_shape"     (modify-shape input)
    "nest_shape"       (nest-shape input)
    "create_text"      (create-text input)
    "create_component" (create-component input)
    (rx/throw (ex-info (dm/str "Unknown tool: " name) {}))))
