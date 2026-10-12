;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.components
  "The design agent's component and variant tools as pure functions; see
  `app.common.agent.write` for what a tool takes and answers. The changes come
  from the generators the workspace uses (`app.common.logic.libraries`,
  `app.common.logic.variants`)."
  (:require
   [app.common.agent.write :as caw]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.files.variant :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variant-properties :as clvp]
   [app.common.logic.variants :as clv]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.fills :as types.fills]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;; --- Refusals and facts

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
      (dm/str "create_variant: " (caw/labels variants)
              (if (= 1 (count variants)) " is" " are")
              " already part of a variant set — use add_variant to extend it")

      (seq non-main)
      (dm/str "create_variant: " (caw/labels non-main)
              (if (= 1 (count non-main))
                " is not a main component — call create_component on it first"
                " are not main components — call create_component on them first")
              ", then pass the main instance")

      :else nil)))

(defn add-variant-problem
  "Why `add_variant` cannot grow this set, as a message the agent can act on, or
  nil. Accepts either a member or the container: `add-new-variant` resolves a
  container to its primary variant (`variants.cljs:360`)."
  [objects id]
  (let [shape (get objects id)]
    (cond
      (nil? id)
      (dm/str "add_variant: shapeId is required — pass a variant set's container"
              " or one of its members (see read_design)")

      (nil? shape)
      (dm/str "add_variant: no shape on this page with id " (str id)
              " — it may be on another page, or gone; check read_design")

      (or (ctc/is-variant-container? shape) (ctc/is-variant? shape))
      nil

      :else
      (dm/str "add_variant: " (caw/shape-label shape) " is not part of a variant set"
              " — a set needs at least two main components; build one with"
              " create_variant"))))

(defn instance-problem
  "Why `create_instance` cannot place this component, or nil. Pure.

  `instantiate-component` asserts on `file-id`, `component-id` and
  `(gpt/point? position)` — an assert throws rather than returning a message the
  agent can read, so everything is checked here first."
  [libraries current-file-id {:keys [componentId fileId x y]}]
  (let [file-id (or (some-> fileId parse-uuid) current-file-id)
        cid     (some-> componentId parse-uuid)
        found   (some-> (get-in libraries [file-id :data])
                        (ctkl/components)
                        (get cid))]
    (cond
      (nil? cid)
      "create_instance: componentId is required — see the components in read_design"

      ;; not truthiness: 0 is a legitimate coordinate
      (or (nil? x) (nil? y))
      "create_instance: x and y are required — a component is placed at a point"

      (nil? found)
      (dm/str "create_instance: no component " componentId
              (if fileId
                (dm/str " in library " fileId)
                " in this file")
              " — check the components listed by read_design"
              (when-not fileId
                ", and pass fileId if it belongs to a connected library")))))

(defn instance-copy
  "The shape as a component COPY head (not a main), or nil. Copies are what these
  tools drive; a main is the component itself."
  [objects id]
  (let [shape (get objects id)]
    (when (and shape (ctc/instance-head? shape) (not (ctc/main-instance? shape)))
      shape)))

(defn not-a-copy-msg
  [tool shape id]
  (cond
    (nil? shape) (dm/str tool ": no shape on this page with id " (str id) " — check read_design")
    (ctc/main-instance? shape) (dm/str tool ": " (caw/shape-label shape) " is a component MAIN, not a"
                                       " copy — these drive a placed instance; edit the main directly")
    :else (dm/str tool ": " (caw/shape-label shape) " is not a component instance — place one with"
                  " create_instance first")))

(defn detach-problem
  "Why `detach_instance` cannot run, or nil. Pure."
  [objects id]
  (let [shape (get objects id)]
    (cond
      (nil? id)
      "detach_instance: shapeId is required"

      (nil? shape)
      (dm/str "detach_instance: no shape on this page with id " (str id)
              " — check read_design")

      (ctc/is-variant? shape)
      (dm/str "detach_instance: " (caw/shape-label shape) " is a member of a variant set"
              " — a member is the set's structure, so detaching it would gut the set."
              " Detach a COPY of it instead, or delete the member if you meant to"
              " shrink the set")

      (ctc/main-instance? shape)
      (dm/str "detach_instance: " (caw/shape-label shape) " is a component's MAIN"
              " — it is the component, so there is nothing to detach it from. You"
              " probably meant one of its copies; place one with create_instance,"
              " or edit the main directly")

      (not (ctc/in-component-copy? shape))
      (dm/str "detach_instance: " (caw/shape-label shape) " is not a component copy"
              " — it has no main to be severed from")

      (not (ctc/instance-root? shape))
      (let [root (ctn/get-instance-root objects shape)]
        (dm/str "detach_instance: " (caw/shape-label shape) " is a piece of a copy,"
                " not the copy itself — detach its root instead"
                (when root (dm/str ": " (dm/str (:id root)))))))))

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

(defn variant-facts
  "The set's shape as `variant-property-problem` wants it. Axes come from the
  same component `update-property-name` reads, so a resolved pos is an accepted
  pos."
  [data objects variant-id]
  (let [components (cfv/find-variant-components data objects variant-id)]
    {:container? (ctc/is-variant-container? (get objects variant-id))
     :axes (mapv :name (:variant-properties (last components)))
     :member-ids (into #{} (comp (remove nil?) (map #(str (:id %)))) components)}))

;; --- Helpers

(defn- page-of
  [data page-id]
  (get-in data [:pages-index page-id]))

(defn- fit-new-texts
  "Texts that came with new shapes and size themselves to their words get
  measured, as the workspace does after a swap."
  [changes {:keys [measure-text] :as engine} ids]
  (let [objects (cb/lookup-objects (cb/apply-changes-local changes))
        texts   (filter (fn [id]
                          (let [shape (get objects id)]
                            (and (cfh/text-shape? shape) (not= :fixed (:grow-type shape)))))
                        ids)]
    (if (or (nil? measure-text) (empty? texts))
      changes
      (reduce (fn [changes id] (caw/fit-text changes engine id)) changes texts))))

;; --- create_instance

(defn create-instance
  [{:keys [file-id page-id objects data libraries]} {:keys [componentId fileId x y] :as input}
   {:keys [propagate]}]
  (if-let [problem (instance-problem libraries file-id input)]
    {:problem problem}
    (let [[shape changes]
          (cll/generate-instantiate-component (caw/page-changes page-id objects)
                                              objects
                                              (or (some-> fileId parse-uuid) file-id)
                                              (parse-uuid componentId)
                                              (gpt/point x y)
                                              (page-of data page-id)
                                              libraries)]
      {:changes (-> changes
                    (caw/reflow-all propagate [(:id shape)])
                    (caw/redo))
       :result (cond-> {:note (str "instance placed — it stays linked to its main, "
                                   "so editing the main updates it. Verify with "
                                   "read_design.")}
                 (:id shape) (assoc :shapeId (dm/str (:id shape))))})))

;; --- detach_instance / reset_overrides

(defn detach-instance
  [{:keys [page-id objects data libraries]} {:keys [shapeId]} _]
  (let [id (some-> shapeId parse-uuid)]
    (if-let [problem (detach-problem objects id)]
      {:problem problem}
      {:changes (-> (cb/empty-changes)
                    (cll/generate-detach-component id data page-id libraries)
                    (caw/redo))
       :result {:shapeId shapeId
                :note (str "detached — these shapes are now local copies and will "
                           "NOT follow their main any more. Tell the user you did "
                           "this; it is not reversible by editing the main.")}})))

(defn reset-overrides
  [{:keys [file-id page-id objects data libraries]} {:keys [shapeId]} {:keys [propagate]}]
  (let [id   (some-> shapeId parse-uuid)
        copy (instance-copy objects id)]
    (if (nil? copy)
      {:problem (not-a-copy-msg "reset_overrides" (get objects id) id)}
      {:changes (-> (cb/empty-changes)
                    (cll/generate-reset-component {:id file-id :data data}
                                                  libraries
                                                  (ctn/get-container data :page page-id)
                                                  id)
                    (cb/with-page-id page-id)
                    (cb/with-objects objects)
                    (caw/reflow-all propagate [(:parent-id copy)])
                    (caw/redo))
       :result {:note "reset — the copy discards its own overrides and matches its main again. Verify with read_design."}})))

;; --- swap_component / switch_variant

(defn- swap
  "→ the changes that replace copy `shape` by an instance of `new-component-id`
  from library `library-id`. `keep-touched?` carries the copy's overrides over
  to the new instance, as a variant switch does."
  [{:keys [page-id objects data libraries]} shape library-id new-component-id keep-touched?
   {:keys [propagate] :as engine}]
  (let [page    (page-of data page-id)
        parent  (get objects (:parent-id shape))
        ldata   (get-in libraries [library-id :data])
        origs   (when keep-touched? (cfh/get-children-with-self objects (:id shape)))
        cell    (when (ctl/grid-layout? parent)
                  (ctl/get-cell-by-shape-id parent (:id shape)))
        index   (or (->> (:shapes parent)
                         (keep-indexed (fn [i id] (when (= id (:id shape)) i)))
                         (first))
                    0)
        [new-shape parents changes]
        (-> (cb/empty-changes nil page-id)
            (cll/generate-component-swap objects shape ldata page libraries new-component-id
                                         index cell (select-keys shape ctc/swap-keep-attrs)
                                         keep-touched?))
        new-ids (cfh/get-children-ids-with-self (cb/get-objects changes) (:id new-shape))
        [changes swapped-parents]
        (if keep-touched?
          (clv/generate-keep-touched changes new-shape shape origs page libraries ldata)
          [changes []])]
    (-> changes
        (fit-new-texts engine new-ids)
        (caw/reflow-all propagate (concat parents swapped-parents new-ids))
        (caw/redo))))

(defn swap-component
  [{:keys [file-id objects libraries] :as view} {:keys [shapeId componentId fileId]} engine]
  (let [id      (some-> shapeId parse-uuid)
        copy    (instance-copy objects id)
        lib-id  (or (some-> fileId parse-uuid) file-id)
        new-cid (some-> componentId parse-uuid)
        target  (when new-cid (get-in libraries [lib-id :data :components new-cid]))]
    (cond
      (nil? copy)
      {:problem (not-a-copy-msg "swap_component" (get objects id) id)}

      (nil? target)
      {:problem (dm/str "swap_component: no component " componentId
                        (if fileId (dm/str " in library " fileId) " in this file")
                        " — see the components in read_design")}

      :else
      {:changes (swap view copy lib-id new-cid false engine)
       :result {:note "swapped — the instance is now the other component, keeping its position. Verify with read_design."}})))

(defn switch-variant
  [{:keys [objects libraries] :as view} {:keys [shapeId property value]} engine]
  (let [id    (some-> shapeId parse-uuid)
        copy  (instance-copy objects id)
        comp  (when copy (ctf/get-component libraries (:component-file copy) (:component-id copy)))
        props (when comp (:variant-properties comp))
        pos   (when props (first (keep-indexed (fn [i p] (when (= (:name p) property) i)) props)))]
    (cond
      (nil? copy)
      {:problem (not-a-copy-msg "switch_variant" (get objects id) id)}

      (not (ctc/is-variant? comp))
      {:problem (dm/str "switch_variant: " (caw/shape-label copy) " is not a variant instance"
                        " — it belongs to a plain component, not a set")}

      (nil? pos)
      {:problem (dm/str "switch_variant: no axis named \"" property "\" on this set"
                        (when (seq props) (dm/str " (it has: " (str/join ", " (map :name props)) ")")))}

      :else
      (let [note    (str "switched — the copy now shows the member with " property
                         " = " value " (nearest match if that exact combination has no "
                         "member). Verify with read_design.")
            lib-id  (:component-file copy)
            ldata   (get-in libraries [lib-id :data])
            main-objects (get-in ldata [:pages-index (:main-instance-page comp) :objects])
            members (cfv/find-variant-components ldata main-objects (:variant-id comp))
            wanted  (update (vec props) pos assoc :value value)
            valid   (->> members
                         (remove #(= (:id %) (:id comp)))
                         (filter #(= value (get-in % [:variant-properties pos :value])))
                         (reverse))
            nearest (when (seq valid)
                      (apply min-key #(ctv/distance wanted (:variant-properties %)) valid))
            loop?   (when nearest
                      (seq? (cfh/components-nesting-loop?
                             (cfh/get-children-with-self main-objects (:main-instance-id nearest))
                             (cfh/get-parents-with-self objects (:parent-id copy)))))]
        (if (or (= value (get-in comp [:variant-properties pos :value]))
                (nil? nearest)
                loop?)
          ;; already there, or no member has that value: nothing changes, as
          ;; in the workspace
          {:changes [] :result {:note note}}
          {:changes (swap view copy lib-id (:id nearest) true engine)
           :result {:note note}})))))

;; --- create_variant
;;
;; The workspace builds a variant set as a string of separate edits
;; (`combine-as-variants` → `transform-in-variant`); the same string is run
;; here, each edit reading the file as the last one left it.

(defn- bounding-rect
  [frames]
  (let [min-x (apply min (map :x frames))
        min-y (apply min (map :y frames))
        max-x (apply max (map #(+ (:x %) (:width %)) frames))
        max-y (apply max (map #(+ (:y %) (:height %)) frames))]
    {:x min-x :y min-y :width (- max-x min-x) :height (- max-y min-y)}))

(defn- common-prefix
  [paths]
  (->> (apply map vector paths)
       (take-while #(apply = %))
       (map first)
       (vec)))

(def ^:private variant-stroke
  {:stroke-alignment :inner
   :stroke-style :solid
   :stroke-color "#bb97d8"
   :stroke-opacity 1
   :stroke-width 2})

(defn- wrap-in-variant
  "Turns the first member into a set: a container board around it, the
  component marked as a variant, and one property per extra path segment of
  its name."
  [session main-id variant-id delta prefix add-wrapper?]
  (let [main         (get (caw/session-objects session) main-id)
        component-id (:component-id main)
        full-name    (if add-wrapper? (str "Component/" (:name main)) (:name main))
        cpath        (cond->> (cpn/split-path full-name)
                       (seq prefix) (#(->> % (drop (count prefix)) (cons (cpn/join-path prefix)) (vec))))
        set-name     (first cpath)
        num-props    (max 1 (dec (count cpath)))
        container    {:is-variant-container true
                      :name set-name
                      :r1 20 :r2 20 :r3 20 :r4 20
                      :layout-item-absolute true}]
    (as-> session s
      (cond-> s
        (not= set-name (:name main))
        (caw/step (fn [changes _ data] (cll/generate-rename-component changes component-id set-name data))))
      (caw/step s (fn [changes objects _]
                    (second (cfsh/prepare-create-artboard-from-selection
                             changes variant-id nil objects [main-id] nil nil false nil delta))))
      (caw/step s (fn [changes _ _]
                    (-> changes
                        (cb/update-shapes [variant-id] #(assoc % :fills (types.fills/create)))
                        (cb/update-shapes [variant-id] #(merge % container))
                        (cb/update-shapes [main-id] #(merge % {:name set-name :variant-id variant-id}))
                        (cb/update-shapes [variant-id] #(update % :strokes (fn [strokes] (into [variant-stroke] strokes)))))))
      (caw/step s (fn [changes _ _]
                    (cb/update-component changes component-id #(assoc % :variant-id variant-id))))
      (reduce (fn [s _]
                (caw/step s (fn [changes _ _]
                              (clvp/generate-add-new-property changes variant-id {:fill-values? true}))))
              s
              (range num-props))
      (if (> (count cpath) 1)
        (reduce (fn [s i]
                  (caw/step s (fn [changes _ _]
                                (clvp/generate-update-property-value changes component-id i (nth cpath (inc i))))))
                s
                (range num-props))
        s)
      (caw/step s (fn [changes _ _]
                    (cb/update-shapes changes [variant-id] #(dissoc % :layout-item-absolute)))))))

(defn create-variant
  [{:keys [page-id objects data selected]} {:keys [shapeIds]} {:keys [propagate]}]
  (let [ids (if (seq shapeIds)
              (variant-member-ids shapeIds)
              (vec (filter #(contains? objects %) selected)))]
    (if-let [problem (variant-members-problem objects ids)]
      {:problem problem}
      (let [variant-id (uuid/next)
            hint       (variant-naming-hint (map #(:name (get objects %)) ids))
            ;; a member nested in another member goes with its parent
            ids        (filterv (set (cfh/clean-loops objects (set ids))) ids)
            shapes     (mapv #(get objects %) ids)
            rect       (bounding-rect shapes)
            prefix     (common-prefix (mapv #(cpn/split-path (:name %)) shapes))
            head       (first shapes)
            delta      (gpt/point (- (:x rect) (:x head) 30) (- (:y rect) (:y head) 30))
            parent     (->> ids
                            (mapv #(reverse (cfh/get-parent-ids objects %)))
                            (common-prefix)
                            (last))
            index      (inc (count (:shapes (get objects parent))))
            resize     (fn [session attr value]
                         (caw/step session
                                   (fn [changes objects _]
                                     (caw/propagate-modifiers
                                      changes propagate variant-id
                                      (ctm/change-dimensions-modifiers (get objects variant-id) attr value)))))
            session
            (-> (caw/session data page-id)
                (wrap-in-variant (first ids) variant-id delta prefix (empty? prefix))
                ;; the other members join the set: relocating a main component
                ;; into a variant container is what makes it a variant
                (caw/step (fn [changes _ _]
                            (cls/generate-relocate changes variant-id 0 (vec (rest ids)))))
                ;; in the order they were given
                (caw/step (fn [changes objects _]
                            (let [children (set (get-in objects [variant-id :shapes]))
                                  members  (->> ids (filter children) (mapv #(get objects %)))]
                              (cond-> changes
                                (> (count members) 1)
                                (cb/change-parent variant-id members 0)))))
                (caw/step (fn [changes _ _]
                            (cb/update-shapes changes ids
                                              #(assoc % :constraints-h :left :constraints-v :top :fixed-scroll false))))
                (caw/step (fn [changes _ _]
                            (cls/generate-relocate changes parent index [variant-id])))
                (resize :width (+ (:width rect) 60))
                (resize :height (+ (:height rect) 60))
                (caw/step (fn [changes _ _]
                            (caw/reflow-all changes propagate [variant-id parent]))))]
        {:changes (:redo session)
         :result (cond-> {:variantId (dm/str variant-id)
                          :members (count ids)
                          :note (str "variant set created — its name and properties come from "
                                     "the members' names. Axes are called \"Property 1\"…; "
                                     "name them with set_variant_property. Verify with read_design.")}
                   hint (assoc :namingHint hint))}))))

;; --- add_variant

(defn add-variant
  [{:keys [page-id objects data]} {:keys [shapeId]} {:keys [propagate]}]
  (let [id (some-> shapeId parse-uuid)]
    (if-let [problem (add-variant-problem objects id)]
      {:problem problem}
      (let [given     (get objects id)
            ;; a container stands for its primary member
            shape     (if (ctc/is-variant-container? given)
                        (get objects (last (:shapes given)))
                        given)
            component (ctkl/get-component data (:component-id shape))
            container (get objects (:parent-id shape))
            new-cid   (uuid/next)
            new-id    (uuid/next)
            changes   (-> (caw/page-changes page-id objects)
                          (cb/with-library-data data)
                          (clv/generate-add-new-variant shape (:variant-id component) new-cid new-id
                                                        (dec (count (:variant-properties component))))
                          (cb/apply-changes-local))
            changes
            (if (ctl/any-layout? container)
              changes
              ;; a container with no layout grows by hand: wider by the new
              ;; member plus the default gap, the member 30px from the right
              (let [objects' (cb/lookup-objects changes)
                    added    (get objects' new-id)
                    cont     (get objects' (:id container))
                    width    (+ (:width cont) (:width added) 20)
                    x        (- width (+ (:width added) 30))]
                (-> changes
                    (caw/propagate-modifiers propagate (:id container)
                                             (ctm/change-dimensions-modifiers cont :width width))
                    (as-> changes
                          (let [objects' (cb/lookup-objects (cb/apply-changes-local changes))]
                            (caw/propagate-modifiers
                             changes propagate new-id
                             (ctm/move-modifiers (caw/position-delta objects' (get objects' new-id) {:x x}))))))))]
        {:changes (-> changes
                      (caw/reflow-all propagate [(:parent-id shape)])
                      (caw/redo))
         :result {:note (str "variant added — it starts with a placeholder value "
                             "(\"Value N\"); give it a real one with "
                             "set_variant_property. Verify with read_design.")
                  :shapeId (dm/str new-id)}}))))

;; --- set_variant_property

(defn set-variant-property
  [{:keys [page-id objects data]} {:keys [variantId property rename componentId value] :as input} _]
  (let [vid   (some-> variantId parse-uuid)
        facts (if vid
                (variant-facts data objects vid)
                {:container? false :axes [] :member-ids #{}})]
    (if-let [problem (variant-property-problem facts input)]
      {:problem problem}
      (let [pos (axis-pos (:axes facts) property)]
        {:changes (-> (caw/page-changes page-id objects)
                      (cb/with-library-data data)
                      ;; renaming an axis to the name it has changes nothing
                      (cond-> (and rename (not= rename property))
                        (clvp/generate-update-property-name vid pos rename))
                      (cond-> (and componentId value)
                        (clvp/generate-update-property-value (parse-uuid componentId) pos value))
                      (caw/redo))
         :result {:axis (or rename property)
                  :note (str "updated — the axis is set-wide, values are per member. "
                             "Verify with read_design.")}}))))
