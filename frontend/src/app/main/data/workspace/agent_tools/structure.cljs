;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.structure
  "Structural tools: create/modify/nest shapes, text, components,
  masks, SVG import, booleans, delete/duplicate and group/ungroup."
  (:require
   [app.common.agent.structure :as cas]
   [app.common.agent.write :as caw]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-tools.common :as atc]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt-text]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.features :as features]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; --- Shared with the server
;;
;; The refusals of the structure tools live in `app.common.agent.structure`,
;; shared with the server-side tools. They keep their names here.

(def mask-problem cas/mask-problem)

(def unmask-problem cas/unmask-problem)

(def nest-problem cas/nest-problem)

(def unparsable-problem cas/unparsable-problem)

(def ids-problem cas/ids-problem)

(def delete-problem cas/delete-problem)

(def duplicate-problem cas/duplicate-problem)

(def group-problem cas/group-problem)

(def ungroup-problem cas/ungroup-problem)

(def boolean-problem cas/boolean-problem)

;; --- Mask / unmask
;;
;; A mask clips content to any shape (boards only clip to a rectangle). Same
;; silent-filter disease as group/ungroup: `mask-group` drops copy-children and
;; no-ops on empty; `unmask-group` keeps only group/bool and commits whatever
;; survived — pass a rect and it "succeeds" having done nothing. It also changes
;; the user's selection, which we restore.

(defn mask-shapes
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (into [] (comp (keep parse-uuid) (distinct)) shapeIds)]
    (if-let [problem (or (unparsable-problem "mask_shapes" shapeIds)
                         (mask-problem objects ids))]
      (rx/throw (ex-info problem {}))
      (let [before (dsh/get-selected-ids state)]
        (atc/interrupt!)
        (st/emit! (dwg/mask-group (into #{} ids)))
        ;; the event re-selects the new mask group; put the user's selection back
        (st/emit! (dws/select-shapes before))
        (rx/of {:note (str "masked — the shapes are now a masked group clipped to "
                           "the topmost of them. Not a board (no layout/fill). "
                           "Verify with read_design.")})))))

(defn unmask-shapes
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (into [] (comp (keep parse-uuid) (distinct)) shapeIds)]
    (if-let [problem (or (unparsable-problem "unmask_shapes" shapeIds)
                         (unmask-problem objects ids))]
      (rx/throw (ex-info problem {}))
      (do
        (atc/interrupt!)
        (st/emit! (dwg/unmask-group (into #{} ids)))
        (rx/of {:note "unmasked — the group's children are no longer clipped. Verify with read_design."})))))

;; --- SVG import
;;
;; Vector paths are a blank in the registry, and point-level bezier surgery has
;; zero playbook demand. The demand that exists — icons — has a cheaper answer
;; the internals ship: `dwm/create-svg-shape` turns an SVG string into real
;; Penpot shapes (it powers paste-SVG and the plugin's createShapeFromSvg).
;; Models write SVG well; this is a passthrough to a tested pipeline.

(defn create-from-svg
  [{:keys [svg x y]}]
  (cond
    (not (dwm/valid-svg-string? svg))
    (rx/throw (ex-info (str "create_from_svg: not valid SVG — pass a complete <svg>…</svg> "
                            "string. Good for icons and small illustrations; for a "
                            "rectangle/ellipse/board use create_shape.")
                       {}))
    :else
    ;; the root id is caller-supplied, so it is knowable before the async import,
    ;; as with create_variant. An <svg> with several elements imports as a group.
    (let [id (uuid/next)]
      (atc/interrupt!)
      (st/emit! (dwm/create-svg-shape id "svg" svg (gpt/point (or x 0) (or y 0))))
      (rx/of {:id (dm/str id)
              :note (str "SVG imported as real shapes (a group when it has several "
                         "elements). Its own fills are NOT checked against "
                         "token-only-colors — that is deliberate for icons, but "
                         "audit_file still flags raw colors. Verify with read_design.")}))))

(def shadow->shape caw/shadow->shape)

(def style-attrs caw/style-attrs)

(def ^:private stroke-styles caw/stroke-styles)

(def ^:private stroke-cap-inputs caw/stroke-cap-inputs)

(def ^:private ->stroke-cap caw/->stroke-cap)

(def locked-problem caw/locked-problem)

(defn- modify-throwable
  "The rejection (an ex-info, carrying :rule for guard hits) for ONE
  modify_shape-style update, or nil. Shared by modify_shape and the
  update_shapes batch; `tool` names whichever surfaced it."
  [tool state objects input]
  (when-let [{:keys [problem rule]} (caw/modify-problem tool objects input (atc/color-guard state))]
    (ex-info problem (if rule {:rule rule} {}))))

(defn- emit-shape-update!
  "Emits one already-validated modify_shape-style update for `id`. No undo
  transaction of its own — the caller brackets one around the whole call (or
  the whole batch)."
  [{:keys [x y width height hidden locked rotation flipH flipV] :as input} id]
  (let [;; Every plain attribute write (name, styles, shadow, fill, stroke) is
        ;; composed into ONE update-shapes pass instead of one full
        ;; changes-build+commit per attribute. The specialized transform events
        ;; (flags, rotation, flips, position, dimensions) stay separate — they
        ;; are not plain assocs and each computes its own modifiers.
        attr-fn (caw/attr-update-fn input)]
    (when (some? attr-fn)
      (st/emit! (dwsh/update-shapes [id] attr-fn)))
    ;; hide/lock — some? so `false` genuinely unhides/unlocks
    (when (or (some? hidden) (some? locked))
      (st/emit! (dwsh/update-shape-flags [id]
                                         (cond-> {}
                                           (some? hidden) (assoc :hidden hidden)
                                           (some? locked) (assoc :blocked locked)))))
    (when (some? rotation)
      ;; absolute (the event computes the delta from the shape's current angle)
      (st/emit! (dwt/increase-rotation [id] rotation)))
    (when flipH (st/emit! (dwt/flip-horizontal-selected [id])))
    (when flipV (st/emit! (dwt/flip-vertical-selected [id])))
    (when (or (some? x) (some? y))
      (st/emit! (dwt/update-position id (cond-> {}
                                          (some? x) (assoc :x x)
                                          (some? y) (assoc :y y)))))
    (when (some? width)
      (st/emit! (dwt/update-dimensions [id] :width width)))
    (when (some? height)
      (st/emit! (dwt/update-dimensions [id] :height height)))))

(def ^:private geometry-settle-ms
  "How long modify_shape waits before reading geometry back.

  A geometry write on a laid-out child is repositioned by the parent layout,
  which runs through a 100ms-buffered reflow (initialize-shape-layout buffers
  :layout/update by 100ms in shape-layout.cljs) — plus the transform-modifier /
  WASM tick on top. The old 80ms wait fired BEFORE that buffer even elapsed, so
  it read the pre-reflow numbers and reported them as 'settled' (the confirmed
  race, since 80 < 100). This clears the buffer window with margin.

  There is no reflow-completion event to await, so the wait stays time-based; a
  pathologically slow reflow can still exceed it, and the note already tells the
  agent to confirm with read_design."
  220)

(defn modify-shape
  [{:keys [shapeId x y width height] :as input}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)]
    (if-let [throwable (modify-throwable "modify_shape" state objects input)]
      (rx/throw throwable)
      (let [id (parse-uuid shapeId)
            tx (random-uuid)]
        (atc/interrupt!)
        (st/emit! (dwu/start-undo-transaction tx))
        (emit-shape-update! input id)
        (st/emit! (dwu/commit-undo-transaction tx))
        (if-not (or (some? x) (some? y) (some? width) (some? height))
          (rx/of {:id shapeId :note "modified — verify with read_design"})
          ;; geometry writes can be constrained — a parent layout owns the
          ;; position, an instance or auto-sized text normalizes itself — so
          ;; report where the shape actually SETTLED instead of claiming the
          ;; numbers took (the Kahoot session re-sent the same y five times).
          ;; Wait past the layout reflow buffer (see geometry-settle-ms) so the
          ;; readback sees the post-reflow geometry, not the value mid-flight.
          (->> (rx/timer geometry-settle-ms)
               (rx/map
                (fn [_]
                  (let [s       (get (dsh/lookup-page-objects @st/state) id)
                        settled {:x (:x s) :y (:y s)
                                 :width (:width s) :height (:height s)}
                        off?    (fn [want got]
                                  (and (some? want) (some? got)
                                       (> (js/Math.abs (- want got)) 0.5)))
                        drift?  (or (off? x (:x s)) (off? y (:y s))
                                    (off? width (:width s)) (off? height (:height s)))]
                    {:id shapeId
                     :settled settled
                     :note (if drift?
                             (str "modified — but the shape SETTLED at a different "
                                  "geometry than requested (see settled). Something "
                                  "owns it: a parent layout positions its children "
                                  "(use set_layout/set_layout_child or move the "
                                  "parent), auto-width text re-measures itself. Do "
                                  "not re-send the same x/y.")
                             "modified — settled at the requested geometry")})))))))))

(defn update-shapes-batch
  "update_shapes: many modify_shape-style updates as ONE call and ONE undo
  step. All-or-nothing — every update is validated before anything is touched,
  so a bad entry cannot leave the batch half-applied."
  [{:keys [updates]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)]
    (if-not (and (sequential? updates) (seq updates))
      (rx/throw (ex-info (str "update_shapes: pass updates — an array of "
                              "modify_shape-style {shapeId, …} maps")
                         {}))
      (if-let [throwable
               (some (fn [[i u]]
                       (when-let [t (modify-throwable "update_shapes" state objects u)]
                         (ex-info (dm/str "updates[" i "]: " (ex-message t))
                                  (or (ex-data t) {}))))
                     (map-indexed vector updates))]
        (rx/throw throwable)
        (do
          (atc/interrupt!)
          (let [tx (random-uuid)]
            (st/emit! (dwu/start-undo-transaction tx))
            (run! (fn [u] (emit-shape-update! u (parse-uuid (:shapeId u)))) updates)
            (st/emit! (dwu/commit-undo-transaction tx)))
          (rx/of {:updated (count updates)
                  :note (str "updated — one undo step. Geometry settles "
                             "asynchronously; verify with read_design.")}))))))

(defn nest-shape
  [{:keys [shapeId parentId index]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)
        pid     (some-> parentId parse-uuid)]
    (if-let [problem (nest-problem objects id pid)]
      (rx/throw (ex-info problem {}))
      (let [parent  (get objects pid)
            ;; default (nil flow-index): end of the flow in a laid-out board,
            ;; top of the z-stack in a plain one — visible either way
            to-idx  (atc/nest-vector-index parent id (some-> index js/Math.round))
            undo-id (js/Symbol)]
        (atc/interrupt!)
        ;; One undo step for the relocate AND its grid reflow: the reflow used
        ;; to be a separate emission, so a single ⌘Z reverted only it and left
        ;; the reparent in place (a half-undone move).
        (st/emit! (dwu/start-undo-transaction undo-id))
        (st/emit! (dwsh/relocate-shapes #{id} pid to-idx))
        ;; relocate-shapes filters its input silently (loops, structure
        ;; ownership) and applies SYNCHRONOUSLY (ptk processes the update before
        ;; emit! returns), so read the move back now rather than on a timer —
        ;; the old rx/timer 80 raced nothing here (unlike modify_shape, whose
        ;; geometry settles through a buffered layout pass) and only delayed the
        ;; result. Read it back rather than report success on faith.
        (let [shape' (get (dsh/lookup-page-objects @st/state) id)]
          (if (= pid (:parent-id shape'))
            (do
              ;; Seat the newly-moved child WITHOUT rebuilding the grid:
              ;; assign-cells adds a cell (and a track if needed) for the orphan
              ;; and leaves every existing track, span and manual placement
              ;; intact. reflow-grid-cells would reset all of that — harmless
              ;; for an auto-flowed grid but destructive to a user-authored one
              ;; (the confirmed bug) — and grid position is by cell, not vector
              ;; index, so honoring `index` is not worth wiping the layout.
              (when (ctl/grid-layout? parent)
                (st/emit! (dwsh/update-shapes [pid]
                                              (fn [p objs] (-> (ctl/assign-cells p objs)
                                                               (ctl/reorder-grid-children)))
                                              {:with-objects? true}))
                (st/emit! (ptk/data-event :layout/update {:ids [pid]})))
              (st/emit! (dwu/commit-undo-transaction undo-id))
              (rx/of {:id shapeId :parentId parentId
                      :note (str "reparented"
                                 (when (ctl/any-layout? parent)
                                   (str " — the parent lays out its children,"
                                        " so it now controls this shape's position"))
                                 (when (ctl/grid-layout? parent)
                                   " (a grid seats it in the next free cell)")
                                 ". Verify with read_design.")}))
            (do
              (st/emit! (dwu/commit-undo-transaction undo-id))
              (rx/throw (ex-info
                         (str "nest_shape: the move did not take — Penpot "
                              "refused it (typically component or variant "
                              "structure ownership). The shape is still under "
                              "its previous parent; check read_design.")
                         {})))))))))

;; --- Text & component tools

(defn create-line
  "Draws a straight two-node vector PATH from (x1,y1) to (x2,y2) — the direct way
  to make a line/connector, no hand-written SVG and never a board. Stroke color,
  width, style, dash/gap and end caps all land in this one call. The geometry
  (content/selrect/points) mirrors the native Line tool. Like create_from_svg, a
  raw stroke color is allowed here (audit_file still flags it)."
  [{:keys [x1 y1 x2 y2 stroke strokeWidth strokeStyle strokeDash strokeGap
           strokeCapStart strokeCapEnd parentId] :as input}]
  (if-let [problem (or (when-not (every? number? [x1 y1 x2 y2])
                         "create_line: x1, y1, x2 and y2 are all required numbers")
                       (when (and (= x1 x2) (= y1 y2))
                         "create_line: start and end are the same point — a line needs length")
                       (atc/enum-problem "create_line" "strokeStyle" strokeStyle stroke-styles)
                       (atc/enum-problem "create_line" "strokeCapStart" strokeCapStart stroke-cap-inputs)
                       (atc/enum-problem "create_line" "strokeCapEnd" strokeCapEnd stroke-cap-inputs))]
    (rx/throw (ex-info problem {}))
    (let [nm       (:name input)
          state    @st/state
          page     (dsh/lookup-page state)
          objects  (:objects page)
          req-pid  (some-> parentId parse-uuid)
          ;; same guards as create-shape: never inject into a component copy,
          ;; and resolve a frame-id that actually points at a frame
          pid      (when (and req-pid (contains? objects req-pid))
                     (:id (ctn/get-first-valid-parent objects req-pid)))
          parent   (get objects pid)
          parent?  (some? parent)
          frame-id (when parent?
                     (if (cfh/frame-shape? parent) pid (:frame-id parent)))
          content  (path/points->content [(gpt/point x1 y1) (gpt/point x2 y2)])
          selrect  (path/calc-selrect content)
          pts      (grc/rect->points selrect)
          stroke*  (cond-> {:stroke-color (or stroke "#000000")
                            :stroke-opacity 1
                            :stroke-width (or strokeWidth 1)
                            :stroke-style (keyword (or strokeStyle "solid"))
                            :stroke-alignment :center}
                     (some? strokeDash)     (assoc :stroke-dash strokeDash)
                     (some? strokeGap)      (assoc :stroke-gap strokeGap)
                     (some? strokeCapStart) (assoc :stroke-cap-start (->stroke-cap strokeCapStart))
                     (some? strokeCapEnd)   (assoc :stroke-cap-end (->stroke-cap strokeCapEnd)))
          shape    (-> (cts/setup-shape
                        (cond-> {:type :path
                                 :strokes [stroke*]
                                 :fills []
                                 :content content}
                          nm (assoc :name nm)))
                       (assoc :selrect selrect :points pts)
                       (cond-> parent? (assoc :parent-id pid :frame-id frame-id)))
          changes  (-> (cb/empty-changes)
                       (cb/with-page page)
                       (cb/with-objects objects)
                       (cb/add-object shape
                                      (when-let [idx (some-> parent atc/flow-append-index)]
                                        {:index idx})))]
      (atc/interrupt!)
      (st/emit! (dch/commit-changes changes))
      (rx/of {:id (dm/str (:id shape))
              :note "line created as a single vector path — verify with read_design"}))))

(defn create-text
  [{:keys [text x y parentId fill align] :as input}]
  (if-let [problem (or (when (or (not (string? text)) (empty? text))
                         "create_text: text must be a non-empty string")
                       (atc/enum-problem "create_text" "align" align atc/text-aligns))]
    (rx/throw (ex-info problem {}))
    (let [nm      (:name input)
          color   (or fill "#000000")
          state    @st/state
          page     (dsh/lookup-page state)
          objects  (:objects page)
          req-pid  (some-> parentId parse-uuid)
          ;; same guards as create-shape: never inject into a component copy,
          ;; and resolve a frame-id that actually points at a frame
          pid      (when (and req-pid (contains? objects req-pid))
                     (:id (ctn/get-first-valid-parent objects req-pid)))
          parent   (get objects pid)
          parent?  (some? parent)
          frame-id (when parent?
                     (if (cfh/frame-shape? parent) pid (:frame-id parent)))
          shape   (-> (cts/setup-shape
                       (cond-> {:type :text
                                :x (or x 0) :y (or y 0)
                                :width 1 :height 1
                                :grow-type :auto-width}
                         nm (assoc :name nm)))
                      (update :content txt/change-text text
                              (cond-> {:fills [{:fill-color color :fill-opacity 1}]}
                                ;; creating right beats creating then fixing
                                align (assoc :text-align align)))
                      (dissoc :position-data)
                      (cond-> parent? (assoc :parent-id pid :frame-id frame-id)))
          changes (-> (cb/empty-changes)
                      (cb/with-page page)
                      (cb/with-objects objects)
                      (cb/add-object shape
                                     (when-let [idx (some-> parent atc/flow-append-index)]
                                       {:index idx})))]
      (atc/interrupt!)
      (st/emit! (dch/commit-changes changes))
      (when (features/active-feature? @st/state "render-wasm/v1")
        (st/emit! (dwwt/resize-wasm-text-debounce (:id shape))))
      (rx/of {:id (dm/str (:id shape))
              :note "text created — size settles async; verify with read_design"}))))

;; --- Text
;;
;; Scope came from the phase's own instruction to check the token path first, and
;; it narrowed the phase sharply: `apply_tokens` ALREADY reaches fontSize,
;; fontFamily, fontWeight, letterSpacing, lineHeight, textCase and
;; textDecoration (Phase 09 derived them from `cto/all-keys`). Verified live — a
;; `fontSizes` token applied to a text really does resize it. So
;; `penpot-foundations`' type scale already works, and the premise "every text is
;; the same default-sized paragraph" was only half true.
;;
;; What is genuinely unreachable: the WORDS (no path at all) and ALIGNMENT (not a
;; token attr — there is no text-align token). This covers exactly those, and
;; points at apply_tokens for the rest, the same relationship `fill` has with
;; colour tokens.

(defn text-problem
  "Why `set_text` cannot run, or nil. Pure."
  [objects id {:keys [text align]}]
  (let [shape (get objects id)]
    (cond
      (nil? id)
      "set_text: shapeId is required"

      (nil? shape)
      (dm/str "set_text: no shape on this page with id " (str id) " — check read_design")

      (not (cfh/text-shape? shape))
      (dm/str "set_text: " (atc/shape-label shape) " is a "
              (some-> (:type shape) name)
              ", not a text shape — note modify_shape's `name` renames the LAYER,"
              " it does not change the words; create text with create_text")

      ;; `some?`, not truthiness: "" clears the text, which is a real edit
      (and (nil? text) (nil? align))
      "set_text: nothing to change — pass text (the words) and/or align"

      :else
      (atc/enum-problem "set_text" "align" align atc/text-aligns))))

(defn set-text
  [{:keys [shapeId text align]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)]
    (if-let [problem (text-problem objects id {:text text :align align})]
      (rx/throw (ex-info problem {}))
      (do
        (atc/interrupt!)
        (when (some? text)
          ;; the same pure content transform create_text seeds with, so it keeps
          ;; the first paragraph's styling rather than resetting it
          (st/emit! (dwsh/update-shapes [id] #(update % :content txt/change-text text))))
        (when align
          ;; update-paragraph-attrs has a headless branch: with no editor open it
          ;; falls through to update-shapes. The agent never has an editor.
          (st/emit! (dwt-text/update-paragraph-attrs {:id id :attrs {:text-align align}})))
        (when (features/active-feature? @st/state "render-wasm/v1")
          (st/emit! (dwwt/resize-wasm-text-debounce id)))
        (rx/of {:id shapeId
                :note (str "text updated — size settles async. Font size, family and "
                           "weight are design tokens: author one with create_token "
                           "(fontSizes) and bind it with apply_tokens rather than "
                           "hardcoding. Verify with read_design.")})))))

(defn create-component
  [{:keys [shapeIds]}]
  (let [ids (if (seq shapeIds)
              (into #{} (keep parse-uuid) shapeIds)
              (dsh/get-selected-ids @st/state))]
    (if (empty? ids)
      (rx/throw (ex-info "create_component: no shapes given and nothing selected" {}))
      (try
        (let [id-ref (atom nil)]
          (atc/interrupt!)
          (st/emit! (dwl/add-component id-ref ids))
          (if-let [cid (deref id-ref)]
            (rx/of {:componentId (dm/str cid) :note "component created"})
            (rx/throw (ex-info "create_component: shapes are not eligible for a component" {}))))
        (catch :default e
          (rx/throw e))))))

;; --- Delete / duplicate
;;
;; `create_shape` shipped without a counterpart, so the agent could make a mess
;; and not clean it up. Both events filter silently — `delete-shapes` asserts a
;; set, and `duplicate-shapes` drops anything `allow-duplicate?` refuses and then
;; no-ops on the empty set, which reads as success.

(defn delete-shape
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (into [] (comp (keep parse-uuid) (distinct)) shapeIds)]
    (if-let [problem (or (unparsable-problem "delete_shape" shapeIds)
                         (delete-problem objects ids))]
      (rx/throw (ex-info problem {}))
      ;; disclose the cascade: deleting a container takes every descendant
      ;; with it, and "deleted 1" hid exactly that (the Kahoot session lost a
      ;; card's icon and texts to a container it thought was empty)
      (let [id-set   (set ids)
            children (->> ids
                          (mapcat #(cfh/get-children-ids objects %))
                          (remove id-set)
                          (distinct)
                          (count))]
        (atc/interrupt!)
        ;; delete-shapes asserts a set; combine-as-variants wanted a vector.
        ;; Two conventions live in this file — convert at the boundary.
        (st/emit! (dwsh/delete-shapes id-set))
        (rx/of (cond-> {:deleted (count ids)
                        :note (str "deleted"
                                   (when (pos? children)
                                     (str " — INCLUDING " children " nested "
                                          (if (= 1 children) "child" "children")
                                          " that lived inside"))
                                   " — undo with ⌘Z (it is one undo step)")}
                 (pos? children) (assoc :childrenDeleted children)))))))

(defn duplicate-shape
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (into [] (comp (keep parse-uuid) (distinct)) shapeIds)]
    (if-let [problem (or (unparsable-problem "duplicate_shape" shapeIds)
                         (duplicate-problem objects ids))]
      (rx/throw (ex-info problem {}))
      (let [id-ref (atom nil)]
        (atc/interrupt!)
        (st/emit! (dws/duplicate-shapes (set ids)
                                        ;; offset like ⌘D: a copy exactly on top of
                                        ;; its original is invisible to the agent
                                        :move-delta? true
                                        ;; never move the user's selection
                                        :change-selection? false
                                        :return-ref id-ref))
        ;; `return-ref` gets ONE id — the representative duplicate — not a
        ;; collection, and it is reset from an `rx/tap` on the emitted stream, so
        ;; it can still be nil here. Report it only when it is both present and
        ;; unambiguous; never guess an id.
        (let [new-id (deref id-ref)]
          (rx/of (cond-> {:duplicated (count ids)
                          :note (str "duplicated. Penpot places a duplicated BOARD to "
                                     "the right of its original, but leaves every other "
                                     "shape exactly on top of the one it copied — so "
                                     "unless you duplicated a board, move it with "
                                     "modify_shape or nest it with nest_shape, or it is "
                                     "invisible. Verify with read_design.")}
                   (and new-id (= 1 (count ids)))
                   (assoc :shapeId (dm/str new-id)))))))))

;; --- Group / ungroup
;;
;; Both events mirror the pattern this file exists to close: they filter their
;; input silently and then `(when-not (empty? …))`, so a fully-filtered call
;; does nothing and returns success.

(defn group-shapes
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (into [] (comp (keep parse-uuid) (distinct)) shapeIds)]
    (if-let [problem (or (unparsable-problem "group_shapes" shapeIds)
                         (group-problem objects ids))]
      (rx/throw (ex-info problem {}))
      ;; group-shapes takes the new group's id, so it is knowable up front
      (let [group-id (uuid/next)]
        (atc/interrupt!)
        (st/emit! (dwg/group-shapes group-id (into #{} ids)))
        (rx/of {:groupId (dm/str group-id)
                :note (str "grouped. A group is not a board: it has no layout, fill "
                           "or clip — if you want these arranged, use a board with "
                           "set_layout instead. Verify with read_design.")})))))

(defn ungroup-shapes
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (into [] (comp (keep parse-uuid) (distinct)) shapeIds)]
    (if-let [problem (or (unparsable-problem "ungroup_shapes" shapeIds)
                         (ungroup-problem objects ids))]
      (rx/throw (ex-info problem {}))
      (do
        (atc/interrupt!)
        (st/emit! (dwg/ungroup-shapes (into #{} ids)))
        (rx/of {:note "ungrouped — the children stay where they were. Verify with read_design."})))))

;; --- Boolean operations
;;
;; The one shape-MAKING primitive left out — union / difference / intersection /
;; exclusion. ungroup_shapes already dissolves a bool, so the registry ended a
;; capability it could not begin. Same silent filter as group/bool everywhere:
;; create-bool drops frames, variants and copy-children, no-ops on empty, and
;; selects the new bool (which we restore).

(defn create-boolean
  [{:keys [operation shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (into [] (comp (keep parse-uuid) (distinct)) shapeIds)]
    (if-let [problem (or (unparsable-problem "create_boolean" shapeIds)
                         (boolean-problem objects operation ids))]
      (rx/throw (ex-info problem {}))
      (let [before  (dsh/get-selected-ids state)
            bool-id (uuid/next)]
        (atc/interrupt!)
        (st/emit! (dwb/create-bool (keyword operation) :ids (into #{} ids) :force-shape-id bool-id))
        ;; create-bool selects the new bool; put the user's selection back
        (st/emit! (dws/select-shapes before))
        (rx/of {:shapeId (dm/str bool-id)
                :note (str "created a " operation " of the shapes — a single editable "
                           "boolean shape; the operands are now its children. Dissolve "
                           "it with ungroup_shapes. Verify with read_design.")})))))
