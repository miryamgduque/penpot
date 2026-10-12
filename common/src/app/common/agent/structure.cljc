;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.structure
  "The design agent's structure tools — nest, delete, duplicate, group,
  ungroup, mask, lines, batch updates — as pure functions; see
  `app.common.agent.write` for what a tool takes and answers.

  The changes come from the generators the workspace itself uses
  (`app.common.logic.*`), so a tool run here does to the file what the same
  action does in the editor. What the editor adds on top — selection, undo
  steps, layer-panel state — has no meaning without a workspace."
  (:require
   [app.common.agent.write :as caw]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.files.shapes-builder :as sb]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.math :as mth]
   [app.common.types.component :as ctc]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;; --- Refusals

(defn nest-problem
  "Why `nest_shape` cannot run, or nil. Pure. Public for tests.

  relocate-shapes filters silently — a stale id, a cycle, or a copy-owned
  target all no-op and read as success, which is how the Kahoot session lost
  half its content to nests that never landed. Name the refusal instead."
  [objects id pid]
  (let [shape  (get objects id)
        parent (get objects pid)]
    (cond
      (or (nil? id) (nil? pid))
      "nest_shape: missing or invalid shapeId/parentId"

      (nil? shape)
      (dm/str "nest_shape: no shape on this page with id " (str id)
              " — it may already be deleted; check read_design")

      (nil? parent)
      (dm/str "nest_shape: no shape on this page with id " (str pid)
              " (the parentId) — check read_design")

      (= id pid)
      "nest_shape: a shape cannot be nested into itself"

      (not (or (cfh/frame-shape? parent) (cfh/group-shape? parent)))
      (dm/str "nest_shape: " (caw/shape-label parent) " is a "
              (some-> (:type parent) name)
              " — the parent must be a board or a group")

      (cfh/is-parent? objects pid id)
      (dm/str "nest_shape: " (caw/shape-label parent) " is inside "
              (caw/shape-label shape)
              " — nesting a shape into its own descendant would create a cycle")

      (or (ctc/in-component-copy? parent)
          (ctn/has-any-copy-parent? objects parent))
      (dm/str "nest_shape: " (caw/shape-label parent) " is part of a component copy,"
              " whose structure is owned by the main component — nest into the"
              " main component instead, or sever the copy with detach_instance"))))

(defn unparsable-problem
  "Why a call must be rejected because some `shapeIds` entry is not a valid
  shape id, or nil. The handlers parse ids with `(keep parse-uuid)`, which
  drops malformed entries silently — so without this a request like
  [\"<valid>\" \"b7f3-truncated\"] would act on the valid subset and read as
  success, leaving the model believing a shape it named was handled. Reject the
  whole call and name the offenders; nothing is mutated. Pure."
  [tool shapeIds]
  (let [bad (into [] (remove #(and (string? %) (some? (parse-uuid %)))) shapeIds)]
    (when (seq bad)
      (dm/str tool ": not valid shape ids: "
              (str/join ", " (map pr-str bad))
              " — pass the exact ids from read_design; nothing was changed"))))

(defn ids-problem
  [tool objects ids]
  (let [missing (remove #(contains? objects %) ids)]
    (cond
      (empty? ids)
      (dm/str tool ": shapeIds is required — pass the ids to act on (see read_design)")

      (seq missing)
      (dm/str tool ": no shape on this page with id "
              (str/join ", " (map str missing))
              " — it may be on another page, or already gone; check read_design"))))

(defn delete-problem
  "Why `delete_shape` cannot run, or nil. Pure."
  [objects ids]
  (ids-problem "delete_shape" objects ids))

(defn duplicate-problem
  "Why `duplicate_shape` cannot run, or nil. Pure."
  [objects ids]
  (or (ids-problem "duplicate_shape" objects ids)
      (let [blocked (remove #(ctc/allow-duplicate? objects (get objects %)) ids)]
        (when (seq blocked)
          (dm/str "duplicate_shape: " (caw/labels (map #(get objects %) blocked))
                  (if (= 1 (count blocked)) " is" " are")
                  " inside a component copy, whose structure is owned by the main"
                  " component — duplicate the main instead, or sever this copy with"
                  " detach_instance first")))))

(defn mask-problem
  "Why `mask_shapes` cannot run, or nil. Pure."
  [objects ids]
  (or (ids-problem "mask_shapes" objects ids)
      (let [in-copy (filter #(ctn/has-any-copy-parent? objects (get objects %)) ids)]
        (when (seq in-copy)
          (dm/str "mask_shapes: " (caw/labels (map #(get objects %) in-copy))
                  (if (= 1 (count in-copy)) " is" " are")
                  " inside a component copy, whose structure is owned by the main"
                  " component — mask the main instead, or detach the copy first")))))

(defn unmask-problem
  "Why `unmask_shapes` cannot run, or nil. Pure."
  [objects ids]
  (or (ids-problem "unmask_shapes" objects ids)
      (let [shapes (map #(get objects %) ids)
            wrong  (remove #(and (or (cfh/group-shape? %) (cfh/bool-shape? %))
                                 (:masked-group %))
                           shapes)]
        (when (seq wrong)
          (dm/str "unmask_shapes: " (caw/labels wrong)
                  (if (= 1 (count wrong)) " is not a masked group" " are not masked groups")
                  " — only a shape created by mask_shapes can be unmasked (see its"
                  " isMask flag in read_design)")))))

(defn group-problem
  "Why `group_shapes` cannot run, or nil. Mirrors `group-shapes`' own filters
  (`groups.cljs:195-200`)."
  [objects ids]
  (or (ids-problem "group_shapes" objects ids)
      (let [shapes   (map #(get objects %) ids)
            variants (filter ctc/is-variant? shapes)
            in-copy  (filter #(ctn/has-any-copy-parent? objects %) shapes)]
        (cond
          (seq variants)
          (dm/str "group_shapes: " (caw/labels variants)
                  (if (= 1 (count variants)) " is" " are")
                  " part of a variant set, which owns its own structure"
                  " — a variant set cannot be grouped")

          (seq in-copy)
          (dm/str "group_shapes: " (caw/labels in-copy)
                  (if (= 1 (count in-copy)) " is" " are")
                  " inside a component copy, whose structure is owned by the main"
                  " component — group the main instead, or sever this copy with"
                  " detach_instance first")))))

(defn ungroup-problem
  "Why `ungroup_shapes` cannot run, or nil. Mirrors `ungroup-shapes`' filters
  (`groups.cljs:242-246`) and what its `prepare` can actually handle."
  [objects ids]
  (or (ids-problem "ungroup_shapes" objects ids)
      (let [shapes    (map #(get objects %) ids)
            in-copy   (filter #(ctn/has-any-copy-parent? objects %) shapes)
            comps     (filter ctc/instance-head? shapes)
            containers (filter ctc/is-variant-container? shapes)
            ;; prepare handles group / bool / frame; anything else yields no
            ;; changes and is dropped without a word
            wrong     (remove #(or (cfh/group-shape? %)
                                   (cfh/bool-shape? %)
                                   (cfh/frame-shape? %))
                              shapes)]
        (cond
          (seq containers)
          (dm/str "ungroup_shapes: " (caw/labels containers)
                  (if (= 1 (count containers)) " is a variant container" " are variant containers")
                  " — ungrouping one would destroy the set; delete it instead if that is the intent")

          (seq comps)
          (dm/str "ungroup_shapes: " (caw/labels comps)
                  (if (= 1 (count comps)) " is a component" " are components")
                  " and components cannot be ungrouped — sever a copy with detach_instance,"
                  " or delete the component")

          (seq in-copy)
          (dm/str "ungroup_shapes: " (caw/labels in-copy)
                  (if (= 1 (count in-copy)) " is" " are")
                  " inside a component copy, whose structure is owned by the main component")

          (seq wrong)
          (dm/str "ungroup_shapes: " (caw/labels wrong)
                  (if (= 1 (count wrong)) " is a " " are ")
                  (some-> (:type (first wrong)) name)
                  " — only a group, a board or a boolean can be ungrouped")))))

(def ^:private bool-ops #{"union" "difference" "intersection" "exclusion"})

(defn boolean-problem
  "Why `create_boolean` cannot run, or nil. Pure — mirrors create-bool's filter."
  [objects operation ids]
  (or (caw/enum-problem "create_boolean" "operation" operation bool-ops)
      (when (< (count ids) 2)
        (dm/str "create_boolean: needs at least 2 shapes (got " (count ids)
                ") — a boolean combines shapes"))
      (ids-problem "create_boolean" objects ids)
      (let [usable (remove (fn [id]
                             (let [s (get objects id)]
                               (or (cfh/frame-shape? s)
                                   (ctc/is-variant? s)
                                   (ctn/has-any-copy-parent? objects s))))
                           ids)]
        (when (< (count usable) 2)
          (dm/str "create_boolean: after skipping boards, variants and copy-children"
                  " fewer than 2 usable shapes remain — a board is not a boolean"
                  " operand; use plain shapes or paths")))))

;; --- Helpers

(defn- parse-ids
  [shapeIds]
  (into [] (comp (keep #(when (string? %) (parse-uuid %))) (distinct)) shapeIds))

(defn- shapes-for-grouping
  "The shapes to group, in the order the workspace groups them."
  [objects ids]
  (->> ids
       (cfh/order-by-indexed-shapes objects)
       (reverse)
       (map #(get objects %))))

(defn- redo
  [changes]
  (caw/redo changes))

;; --- update_shapes

(defn update-shapes
  "Many modify_shape-style updates as ONE call. All-or-nothing: every update is
  validated before anything is touched, so a bad entry cannot leave the batch
  half-applied."
  [{:keys [page-id objects]} {:keys [updates]} {:keys [guard] :as engine}]
  (if-not (and (sequential? updates) (seq updates))
    {:problem (str "update_shapes: pass updates — an array of "
                   "modify_shape-style {shapeId, …} maps")}
    (or (some (fn [[i u]]
                (when-let [refusal (caw/modify-problem "update_shapes" objects u guard)]
                  (update refusal :problem #(dm/str "updates[" i "]: " %))))
              (map-indexed vector updates))
        (some (fn [u] (some->> (caw/modify-fallback objects u engine) (hash-map :fallback))) updates)
        {:changes (redo (reduce (fn [changes u] (caw/modify-into changes u engine))
                                (caw/page-changes page-id objects)
                                updates))
         :result {:updated (count updates)
                  :note (str "updated. Geometry settles "
                             "asynchronously; verify with read_design.")}})))

;; --- nest_shape

(defn nest-shape
  [{:keys [page-id objects data]} {:keys [shapeId parentId index]} {:keys [propagate]}]
  (let [id  (some-> shapeId parse-uuid)
        pid (some-> parentId parse-uuid)]
    (if-let [problem (nest-problem objects id pid)]
      {:problem problem}
      (let [parent  (get objects pid)
            ;; default (nil flow index): end of the flow in a laid-out board,
            ;; top of the z-stack in a plain one — visible either way
            to-idx  (caw/nest-vector-index parent id (some-> index mth/round int))
            parents (conj #{pid} (cfh/get-parent-id objects id))
            changes (-> (caw/page-changes page-id objects)
                        (cb/with-library-data data)
                        (cls/generate-relocate pid to-idx [id])
                        (cb/apply-changes-local))]
        ;; the generator filters what it is given (component and variant
        ;; structure): read the move back rather than report it on faith
        (if (not= pid (:parent-id (get (cb/lookup-objects changes) id)))
          {:problem (str "nest_shape: the move did not take — Penpot "
                         "refused it (typically component or variant "
                         "structure ownership). The shape is still under "
                         "its previous parent; check read_design.")}
          {:changes (-> changes
                        ;; seat the newly-moved child WITHOUT rebuilding the
                        ;; grid: every existing track, span and manual
                        ;; placement stays as it is
                        (cond-> (ctl/grid-layout? parent)
                          (caw/seat-in-grid pid))
                        (caw/reflow-all propagate (conj (vec parents) id))
                        (redo))
           :result {:id shapeId
                    :parentId parentId
                    :note (str "reparented"
                               (when (ctl/any-layout? parent)
                                 (str " — the parent lays out its children,"
                                      " so it now controls this shape's position"))
                               (when (ctl/grid-layout? parent)
                                 " (a grid seats it in the next free cell)")
                               ". Verify with read_design.")}})))))

;; --- create_line

(defn create-line
  "A straight two-node vector PATH from (x1,y1) to (x2,y2). The geometry
  mirrors the native Line tool. A raw stroke color is allowed here, as for an
  imported SVG."
  [{:keys [page-id objects]}
   {:keys [x1 y1 x2 y2 stroke strokeWidth strokeStyle strokeDash strokeGap
           strokeCapStart strokeCapEnd parentId]
    :as input}
   {:keys [propagate]}]
  (if-let [problem (or (when-not (every? number? [x1 y1 x2 y2])
                         "create_line: x1, y1, x2 and y2 are all required numbers")
                       (when (and (= x1 x2) (= y1 y2))
                         "create_line: start and end are the same point — a line needs length")
                       (caw/enum-problem "create_line" "strokeStyle" strokeStyle caw/stroke-styles)
                       (caw/enum-problem "create_line" "strokeCapStart" strokeCapStart caw/stroke-cap-inputs)
                       (caw/enum-problem "create_line" "strokeCapEnd" strokeCapEnd caw/stroke-cap-inputs))]
    {:problem problem}
    (let [nm       (:name input)
          req-pid  (some-> parentId parse-uuid)
          pid      (when (and req-pid (contains? objects req-pid))
                     (:id (ctn/get-first-valid-parent objects req-pid)))
          parent   (get objects pid)
          frame-id (when parent
                     (if (cfh/frame-shape? parent) pid (:frame-id parent)))
          content  (path/points->content [(gpt/point x1 y1) (gpt/point x2 y2)])
          selrect  (path/calc-selrect content)
          stroke*  (cond-> {:stroke-color (or stroke "#000000")
                            :stroke-opacity 1
                            :stroke-width (or strokeWidth 1)
                            :stroke-style (keyword (or strokeStyle "solid"))
                            :stroke-alignment :center}
                     (some? strokeDash)     (assoc :stroke-dash strokeDash)
                     (some? strokeGap)      (assoc :stroke-gap strokeGap)
                     (some? strokeCapStart) (assoc :stroke-cap-start (caw/->stroke-cap strokeCapStart))
                     (some? strokeCapEnd)   (assoc :stroke-cap-end (caw/->stroke-cap strokeCapEnd)))
          shape    (-> (cts/setup-shape
                        (cond-> {:type :path
                                 :strokes [stroke*]
                                 :fills []
                                 :content content}
                          nm (assoc :name nm)))
                       (assoc :selrect selrect :points (grc/rect->points selrect))
                       (cond-> parent (assoc :parent-id pid :frame-id frame-id)))
          index    (some-> parent caw/flow-append-index)]
      {:changes (-> (caw/page-changes page-id objects)
                    (cb/add-object shape (when (some? index) {:index index}))
                    (cond-> (ctl/grid-layout? parent)
                      (caw/seat-in-grid pid))
                    (cond-> (ctl/any-layout? parent)
                      (caw/reflow-all propagate [pid]))
                    (redo))
       :result {:id (dm/str (:id shape))
                :note "line created as a single vector path — verify with read_design"}})))

;; --- delete_shape

(defn delete-shape
  [{:keys [page-id objects data]} {:keys [shapeIds]} {:keys [propagate]}]
  (let [ids (parse-ids shapeIds)]
    (if-let [problem (or (unparsable-problem "delete_shape" shapeIds)
                         (delete-problem objects ids))]
      {:problem problem}
      (let [id-set   (set ids)
            children (->> ids
                          (mapcat #(cfh/get-children-ids objects %))
                          (remove id-set)
                          (distinct)
                          (count))
            page     (get-in data [:pages-index page-id])
            [parents changes]
            (-> (cb/empty-changes nil page-id)
                (cls/generate-delete-shapes data page objects id-set {}))]
        {:changes (-> changes
                      (caw/reflow-all propagate parents)
                      (redo))
         :result (cond-> {:deleted (count ids)
                          :note (str "deleted"
                                     (when (pos? children)
                                       (str " — INCLUDING " children " nested "
                                            (if (= 1 children) "child" "children")
                                            " that lived inside")))}
                   (pos? children) (assoc :childrenDeleted children))}))))

;; --- duplicate_shape

(defn duplicate-shape
  [{:keys [file-id page-id objects data libraries]} {:keys [shapeIds]} {:keys [propagate]}]
  (let [ids (parse-ids shapeIds)]
    (if-let [problem (or (unparsable-problem "duplicate_shape" shapeIds)
                         (duplicate-problem objects ids))]
      {:problem problem}
      (let [id-set (set ids)
            page   (get-in data [:pages-index page-id])
            obj    (get objects (first ids))
            ;; a board goes to the right of its original; anything else stays
            ;; exactly on top of the one it copied
            delta  (if (and (cfh/frame-shape? obj) (not (ctc/instance-head? obj)))
                     (gpt/point (+ (:width obj) 50) 0)
                     (gpt/point 0 0))
            changes (-> (cb/empty-changes)
                        (cll/generate-duplicate-changes objects page id-set delta libraries data file-id {})
                        (cll/generate-duplicate-changes-update-indices objects id-set))
            new-ids (->> (:redo-changes changes)
                         (filter #(= :add-obj (:type %)))
                         (filter #(contains? id-set (:old-id %)))
                         (mapv #(get-in % [:obj :id])))
            frames  (into #{} (map #(get-in objects [% :frame-id])) ids)]
        {:changes (-> changes
                      (caw/reflow-all propagate frames)
                      (redo))
         :result (cond-> {:duplicated (count ids)
                          :note (str "duplicated. Penpot places a duplicated BOARD to "
                                     "the right of its original, but leaves every other "
                                     "shape exactly on top of the one it copied — so "
                                     "unless you duplicated a board, move it with "
                                     "modify_shape or nest it with nest_shape, or it is "
                                     "invisible. Verify with read_design.")}
                   (and (first new-ids) (= 1 (count ids)))
                   (assoc :shapeId (dm/str (first new-ids))))}))))

;; --- group_shapes / ungroup_shapes
;;
;; The group and ungroup changes are the workspace's own
;; (`app.main.data.workspace.groups`), which has no shared counterpart.

(defn- empty-groups-after-grouping
  "The groups that end up empty once `shapes` have moved into a new group, and
  so should go."
  [objects parent-id shapes]
  (let [ids     (cfh/clean-loops objects (into #{} (map :id) shapes))
        parents (into #{} (map #(cfh/get-parent-id objects %)) ids)]
    (loop [current-id  (first parents)
           to-check    (rest parents)
           removed-id? ids
           result      #{}]
      (if-not current-id
        result
        (let [group (get objects current-id)]
          (if (and (not= :frame (:type group))
                   (not= current-id parent-id)
                   (empty? (remove removed-id? (:shapes group))))
            (let [to-check (concat to-check [(cfh/get-parent-id objects current-id)])]
              (recur (first to-check)
                     (rest to-check)
                     (conj removed-id? current-id)
                     (conj result current-id)))
            (recur (first to-check)
                   (rest to-check)
                   removed-id?
                   result)))))))

(defn- create-group
  "→ `[group changes]` for a new group `id` holding `shapes`."
  [changes id objects shapes base-name keep-name?]
  (let [frame-id  (:frame-id (first shapes))
        parent-id (:parent-id (first shapes))
        gname     (if (and keep-name?
                           (= (count shapes) 1)
                           (= (:type (first shapes)) :group))
                    (:name (first shapes))
                    base-name)
        selrect   (gsh/shapes->rect shapes)
        group-idx (->> shapes last :id (cfh/get-position-on-parent objects) inc)
        group     (cts/setup-shape {:id id
                                    :type :group
                                    :name gname
                                    :shapes (mapv :id shapes)
                                    :selrect selrect
                                    :x (:x selrect)
                                    :y (:y selrect)
                                    :width (:width selrect)
                                    :height (:height selrect)
                                    :parent-id parent-id
                                    :frame-id frame-id
                                    :index group-idx})
        to-detach (filter ctc/in-component-copy-not-head? shapes)
        to-delete (empty-groups-after-grouping objects parent-id shapes)
        target-cell
        (when (ctl/grid-layout? objects parent-id)
          (ctl/get-cell-by-shape-id (get objects parent-id) (-> shapes last :id)))
        grid-parents
        (into []
              (comp (map :parent-id)
                    (filter (partial ctl/grid-layout? objects)))
              shapes)]
    [group
     (-> changes
         (cb/add-object group {:index group-idx})
         (cb/update-shapes (map :id shapes)
                           (fn [shape]
                             (-> shape
                                 (d/assoc-when :constraints-h :scale)
                                 (d/assoc-when :constraints-v :scale))))
         (cb/update-shapes (map :id shapes) ctl/remove-layout-item-data)
         (cb/change-parent (:id group) (reverse shapes))
         (cb/update-shapes (map :id to-detach) ctc/detach-shape)
         (cond-> target-cell
           (cb/update-shapes [parent-id]
                             (fn [parent]
                               (assoc-in parent [:layout-grid-cells (:id target-cell) :shapes] [(:id group)]))))
         (cb/update-shapes grid-parents ctl/assign-cells {:with-objects? true})
         (cb/remove-objects to-delete))]))

(defn group-shapes
  [{:keys [page-id objects]} {:keys [shapeIds]} {:keys [propagate]}]
  (let [ids (parse-ids shapeIds)]
    (if-let [problem (or (unparsable-problem "group_shapes" shapeIds)
                         (group-problem objects ids))]
      {:problem problem}
      (let [group-id (uuid/next)
            shapes   (->> (cfh/clean-loops objects (set ids))
                          (shapes-for-grouping objects))
            parents  (into #{} (map :parent-id) shapes)
            [_ changes] (create-group (caw/page-changes page-id objects) group-id objects shapes "Group" false)]
        {:changes (-> changes
                      (caw/reflow-all propagate parents)
                      (redo))
         :result {:groupId (dm/str group-id)
                  :note (str "grouped. A group is not a board: it has no layout, fill "
                             "or clip — if you want these arranged, use a board with "
                             "set_layout instead. Verify with read_design.")}}))))

(defn- dissolve
  "Appends the changes that dissolve one group, boolean or board: its children
  move up to its parent and it goes."
  [changes objects shape]
  (let [children  (->> (:shapes shape)
                       (cfh/order-by-indexed-shapes objects)
                       (mapv #(get objects %)))
        parent-id (cfh/get-parent-id objects (:id shape))
        index     (inc (cfh/get-position-on-parent objects (:id shape)))]
    (-> changes
        (cond-> (and (cfh/frame-shape? shape) (ctl/any-layout? shape))
          (cb/update-shapes (:shapes shape) ctl/remove-layout-item-data))
        (cb/change-parent parent-id children index)
        (cb/remove-objects [(:id shape)])
        (cond-> (ctl/grid-layout? objects parent-id)
          (cb/update-shapes [parent-id] ctl/assign-cells {:with-objects? true})))))

(defn ungroup-shapes
  [{:keys [page-id objects]} {:keys [shapeIds]} {:keys [propagate]}]
  (let [ids (parse-ids shapeIds)]
    (if-let [problem (or (unparsable-problem "ungroup_shapes" shapeIds)
                         (ungroup-problem objects ids))]
      {:problem problem}
      (let [parents (into #{} (keep #(cfh/get-parent-id objects %)) ids)]
        {:changes (-> (reduce (fn [changes id]
                                (dissolve changes (cb/lookup-objects changes) (get objects id)))
                              (caw/page-changes page-id objects)
                              ids)
                      (caw/reflow-all propagate parents)
                      (redo))
         :result {:note "ungrouped — the children stay where they were. Verify with read_design."}}))))

;; --- mask_shapes / unmask_shapes

(defn mask-shapes
  [{:keys [page-id objects]} {:keys [shapeIds]} {:keys [propagate]}]
  (let [ids (parse-ids shapeIds)]
    (if-let [problem (or (unparsable-problem "mask_shapes" shapeIds)
                         (mask-problem objects ids))]
      {:problem problem}
      (let [shapes      (->> (cfh/clean-loops objects (set ids))
                             (shapes-for-grouping objects))
            first-shape (first shapes)
            start       (caw/page-changes page-id objects)
            ;; a single group is masked as it is; anything else is grouped first
            [group changes]
            (if (and (= 1 (count shapes)) (= :group (:type first-shape)))
              [first-shape start]
              (create-group start (uuid/next) objects shapes "Mask" true))]
        {:changes (-> changes
                      (cb/update-shapes (:shapes group)
                                        #(assoc % :constraints-h :scale :constraints-v :scale))
                      (cb/update-shapes [(:id group)]
                                        #(assoc %
                                                :masked-group true
                                                :selrect (:selrect first-shape)
                                                :points (:points first-shape)
                                                :transform (:transform first-shape)
                                                :transform-inverse (:transform-inverse first-shape)))
                      (cb/resize-parents [(:id group)])
                      (caw/reflow-all propagate [(:id group)])
                      (redo))
         :result {:note (str "masked — the shapes are now a masked group clipped to "
                             "the topmost of them. Not a board (no layout/fill). "
                             "Verify with read_design.")}}))))

(defn unmask-shapes
  [{:keys [page-id objects]} {:keys [shapeIds]} _]
  (let [ids (parse-ids shapeIds)]
    (if-let [problem (or (unparsable-problem "unmask_shapes" shapeIds)
                         (unmask-problem objects ids))]
      {:problem problem}
      {:changes (redo (reduce (fn [changes id]
                                (-> changes
                                    (cb/update-shapes [id] #(dissoc % :masked-group))
                                    (cb/resize-parents [id])))
                              (caw/page-changes page-id objects)
                              ids))
       :result {:note "unmasked — the group's children are no longer clipped. Verify with read_design."}})))

;; --- create_boolean

(defn- boolean-shape
  "→ `[shape index]`: the boolean of `shapes` and where it goes among its
  siblings. It takes its look from the head operand — the first for a
  difference, the last otherwise — as the workspace does."
  [id operation shapes objects]
  (let [head  (if (= operation :difference) (first shapes) (last shapes))
        head  (cond-> head
                (and (contains? head :svg-attrs) (empty? (:fills head)))
                (assoc :fills (path/get-default-bool-fills)))
        shape (-> {:id id
                   :type :bool
                   :bool-type operation
                   :frame-id (:frame-id head)
                   :parent-id (:parent-id head)
                   :name (str/capital (name operation))
                   :shapes (into [] (map :id) shapes)}
                  (merge (select-keys head path/bool-style-properties))
                  (cts/setup-shape)
                  (path/update-bool-shape objects))]
    [shape (cfh/get-position-on-parent objects (:id head))]))

(defn create-boolean
  [{:keys [page-id objects]} {:keys [operation shapeIds]} {:keys [propagate]}]
  (let [ids (parse-ids shapeIds)]
    (if-let [problem (or (unparsable-problem "create_boolean" shapeIds)
                         (boolean-problem objects operation ids))]
      {:problem problem}
      (let [bool-id (uuid/next)
            shapes  (->> (cfh/clean-loops objects (set ids))
                         (cfh/order-by-indexed-shapes objects)
                         (map #(get objects %))
                         (remove cfh/frame-shape?)
                         (remove ctc/is-variant?)
                         (remove #(ctn/has-any-copy-parent? objects %))
                         (vec))
            [shape index] (boolean-shape bool-id (keyword operation) (reverse shapes) objects)]
        {:changes (-> (caw/page-changes page-id objects)
                      (cb/add-object shape {:index (inc index)})
                      (cb/update-shapes (map :id shapes) ctl/remove-layout-item-data)
                      (cb/change-parent bool-id shapes)
                      (caw/reflow-all propagate [(:parent-id shape)])
                      (redo))
         :result {:shapeId (dm/str bool-id)
                  :note (str "created a " operation " of the shapes — a single editable "
                             "boolean shape; the operands are now its children. Dissolve "
                             "it with ungroup_shapes. Verify with read_design.")}}))))

;; --- create_from_svg

(defn import-svg
  "The changes that add parsed SVG `svg-data` to the page as shapes named
  `nm`, at `x`,`y` → `{:id :changes}`, the changes still a builder."
  [{:keys [page-id objects]} svg-data nm x y {:keys [propagate]}]
  (let [id       (uuid/next)
        position (gpt/point (or x 0) (or y 0))
        frame-id (ctst/top-nested-frame objects position)
        [shape children]
        (sb/create-svg-shapes id (assoc svg-data :name nm) position objects frame-id frame-id #{} true)
        changes  (reduce cb/add-object
                         (-> (caw/page-changes page-id objects)
                             (cb/add-object shape))
                         children)
        added    (->> (:redo-changes changes)
                      (filter #(= :add-obj (:type %)))
                      (map :id)
                      (reverse)
                      (vec))]
    {:id id
     :changes (-> changes
                  (cb/resize-parents added)
                  (caw/reflow-all propagate [(:id shape)]))}))

(defn create-from-svg
  "Imports an SVG as real shapes. `parse-svg` turns the markup into the tree
  the shape builder reads, or nil when it is not SVG; parsing XML is the
  host's job."
  [view {:keys [svg x y]} {:keys [parse-svg] :as engine}]
  (if (nil? parse-svg)
    {:fallback "SVG cannot be parsed on the server here"}
    (let [svg-data (when (and (string? svg) (not (str/blank? svg))) (parse-svg svg))]
      (if (nil? svg-data)
        {:problem (str "create_from_svg: not valid SVG — pass a complete <svg>…</svg> "
                       "string. Good for icons and small illustrations; for a "
                       "rectangle/ellipse/board use create_shape.")}
        (let [{:keys [id changes]} (import-svg view svg-data "svg" x y engine)]
          {:changes (redo changes)
           :result {:id (dm/str id)
                    :note (str "SVG imported as real shapes (a group when it has several "
                               "elements). Its own fills are NOT checked against "
                               "token-only-colors — that is deliberate for icons, but "
                               "audit_file still flags raw colors. Verify with read_design.")}})))))

;; --- create_component

(defn create-component
  "Turns shapes into a main component. With no `shapeIds` it takes what the
  user has selected, when a workspace reported a selection."
  [{:keys [file-id page-id objects selected]} {:keys [shapeIds]} {:keys [propagate]}]
  (let [ids (if (seq shapeIds)
              (into #{} (keep #(when (string? %) (parse-uuid %))) shapeIds)
              (into #{} (filter #(contains? objects %)) selected))]
    (if (empty? ids)
      {:problem "create_component: no shapes given and nothing selected"}
      (let [shapes  (shapes-for-grouping objects (filter #(contains? objects %) ids))
            parents (into #{} (map :parent-id) shapes)
            [_ component-id changes]
            (when (seq shapes)
              (cll/generate-add-component (cb/empty-changes) shapes objects page-id file-id
                                          cfsh/prepare-create-artboard-from-selection))]
        (if (or (nil? component-id) (empty? (:redo-changes changes)))
          {:problem "create_component: shapes are not eligible for a component"}
          {:changes (-> changes
                        (caw/reflow-all propagate parents)
                        (redo))
           :result {:componentId (dm/str component-id) :note "component created"}})))))
