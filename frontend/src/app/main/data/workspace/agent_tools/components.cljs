;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.components
  "Component tools: variants, instances, overrides and detach."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.variant :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-tools.common :as atc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.variants :as dwv]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

;; --- Variants
;;
;; `dwv/combine-as-variants` filters out ids that are not main instances or are
;; already variants, and no-ops entirely below two ids — all silently. Silence
;; is the one answer the agent cannot act on: with no signal it improvises
;; something that resembles the capability (frames named `Card=Size=Compact`).
;; So the eligibility rules are checked here first, mirroring the event's own
;; filter, and rejected with a message that names the corrective action.


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
      (dm/str "create_variant: " (atc/labels variants)
              (if (= 1 (count variants)) " is" " are")
              " already part of a variant set — use add_variant to extend it")

      (seq non-main)
      (dm/str "create_variant: " (atc/labels non-main)
              (if (= 1 (count non-main))
                " is not a main component — call create_component on it first"
                " are not main components — call create_component on them first")
              ", then pass the main instance")

      :else nil)))

(defn create-variant
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
        (atc/interrupt!)
        (st/emit! (dwv/combine-as-variants
                   ids {:trigger "agent:create_variant" :variant-id variant-id}))
        (rx/of (cond-> {:variantId (dm/str variant-id)
                        :members (count ids)
                        :note (str "variant set created — its name and properties come from "
                                   "the members' names. Axes are called \"Property 1\"…; "
                                   "name them with set_variant_property. Verify with read_design.")}
                 hint (assoc :namingHint hint)))))))

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
      (dm/str "add_variant: " (atc/shape-label shape) " is not part of a variant set"
              " — a set needs at least two main components; build one with"
              " create_variant"))))

(defn add-variant
  [{:keys [shapeId]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)]
    (if-let [problem (add-variant-problem objects id)]
      (rx/throw (ex-info problem {}))
      ;; add-new-variant generates the new ids internally and exposes no id-ref,
      ;; but it ends by selecting the new shape — so the selection is the only
      ;; handle on it. Compare against the previous selection rather than trust
      ;; it: an unchanged selection means we have no id to report, not a wrong one.
      (let [before (dsh/get-selected-ids state)]
        (atc/interrupt!)
        (st/emit! (dwv/add-new-variant id))
        (let [after (dsh/get-selected-ids @st/state)
              new-id (first (remove before after))]
          (rx/of (cond-> {:note (str "variant added — it starts with a placeholder value "
                                     "(\"Value N\"); give it a real one with "
                                     "set_variant_property. Verify with read_design.")}
                   new-id (assoc :shapeId (dm/str new-id)))))))))

;; --- Component instances
;;
;; Without this a design system the agent builds is write-only: it can author a
;; component and never place one, so it redraws from primitives every time and
;; the library it just made goes unused.

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

(defn create-instance
  [{:keys [componentId fileId x y] :as input}]
  (let [state     @st/state
        libraries (dsh/lookup-libraries state)
        cur-id    (:current-file-id state)]
    (if-let [problem (instance-problem libraries cur-id input)]
      (rx/throw (ex-info problem {}))
      (let [id-ref (atom nil)]
        (atc/interrupt!)
        (st/emit! (dwl/instantiate-component (or (some-> fileId parse-uuid) cur-id)
                                             (parse-uuid componentId)
                                             (gpt/point x y)
                                             {:id-ref id-ref
                                              :origin "agent:create_instance"}))
        (let [new-id (deref id-ref)]
          (rx/of (cond-> {:note (str "instance placed — it stays linked to its main, "
                                     "so editing the main updates it. Verify with "
                                     "read_design.")}
                   new-id (assoc :shapeId (dm/str new-id)))))))))

;; --- Drive the copy
;;
;; An instance, once placed, was frozen. These three one-event wraps let the
;; agent switch a copy to another variant (the point of the sets it can build),
;; reset a drifted copy to its main, or swap it for a different component —
;; instead of delete + re-instantiate, or restyling by hand into the very
;; override drift the audit skill hunts. Push-to-main is deliberately OUT
;; (a shared-asset edit, never auto-fix).

(defn- instance-copy
  "The shape as a component COPY head (not a main), or nil. Copies are what these
  tools drive; a main is the component itself."
  [objects id]
  (let [shape (get objects id)]
    (when (and shape (ctc/instance-head? shape) (not (ctc/main-instance? shape)))
      shape)))

(defn- not-a-copy-msg
  [tool shape id]
  (cond
    (nil? shape) (dm/str tool ": no shape on this page with id " (str id) " — check read_design")
    (ctc/main-instance? shape) (dm/str tool ": " (atc/shape-label shape) " is a component MAIN, not a"
                                       " copy — these drive a placed instance; edit the main directly")
    :else (dm/str tool ": " (atc/shape-label shape) " is not a component instance — place one with"
                  " create_instance first")))

(defn switch-variant
  [{:keys [shapeId property value]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        libs    (dsh/lookup-libraries state)
        id      (some-> shapeId parse-uuid)
        copy    (instance-copy objects id)
        comp    (when copy (ctf/get-component libs (:component-file copy) (:component-id copy)))
        props   (when comp (:variant-properties comp))
        pos     (when props (first (keep-indexed (fn [i p] (when (= (:name p) property) i)) props)))]
    (cond
      (nil? copy)
      (rx/throw (ex-info (not-a-copy-msg "switch_variant" (get objects id) id) {}))

      (not (ctc/is-variant? comp))
      (rx/throw (ex-info (dm/str "switch_variant: " (atc/shape-label copy) " is not a variant instance"
                                 " — it belongs to a plain component, not a set") {}))

      (nil? pos)
      (rx/throw (ex-info (dm/str "switch_variant: no axis named \"" property "\" on this set"
                                 (when (seq props) (dm/str " (it has: " (str/join ", " (map :name props)) ")"))) {}))

      :else
      (do
        (atc/interrupt!)
        (st/emit! (dwv/variants-switch {:shapes [copy] :pos pos :val value}))
        (rx/of {:note (str "switched — the copy now shows the member with " property
                           " = " value " (nearest match if that exact combination has no "
                           "member). Verify with read_design.")})))))

(defn reset-overrides
  [{:keys [shapeId]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)
        copy    (instance-copy objects id)]
    (if (nil? copy)
      (rx/throw (ex-info (not-a-copy-msg "reset_overrides" (get objects id) id) {}))
      (do
        (atc/interrupt!)
        (st/emit! (dwl/reset-component id))
        (rx/of {:note "reset — the copy discards its own overrides and matches its main again. Verify with read_design."})))))

(defn swap-component
  [{:keys [shapeId componentId fileId]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        libs    (dsh/lookup-libraries state)
        cur     (:current-file-id state)
        id      (some-> shapeId parse-uuid)
        copy    (instance-copy objects id)
        file-id (or (some-> fileId parse-uuid) cur)
        new-cid (some-> componentId parse-uuid)
        target  (when new-cid (get-in libs [file-id :data :components new-cid]))]
    (cond
      (nil? copy)
      (rx/throw (ex-info (not-a-copy-msg "swap_component" (get objects id) id) {}))

      (nil? target)
      (rx/throw (ex-info (dm/str "swap_component: no component " componentId
                                 (if fileId (dm/str " in library " fileId) " in this file")
                                 " — see the components in read_design") {}))

      :else
      (do
        (atc/interrupt!)
        (st/emit! (dwl/component-swap copy file-id new-cid false))
        (rx/of {:note "swapped — the instance is now the other component, keeping its position. Verify with read_design."})))))

;; --- Detach
;;
;; The missing half of Wave 5, and the plan's standing rule pointed at itself:
;; `duplicate_shape` and `group_shapes` already reject with "…or detach the copy
;; first", naming a tool that did not exist.
;;
;; Gotcha #12 — detaching a variant instance "has corrupted files and hung all
;; subsequent saves" — is about the PLUGIN path. Tested natively on a scratch
;; file: revn advanced 97→100, later edits persisted, the file reloaded clean.
;; So the variant guard here refuses for the honest reason (a member is the set's
;; structure) rather than repeating a corruption claim we could not reproduce.

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
      (dm/str "detach_instance: " (atc/shape-label shape) " is a member of a variant set"
              " — a member is the set's structure, so detaching it would gut the set."
              " Detach a COPY of it instead, or delete the member if you meant to"
              " shrink the set")

      (ctc/main-instance? shape)
      (dm/str "detach_instance: " (atc/shape-label shape) " is a component's MAIN"
              " — it is the component, so there is nothing to detach it from. You"
              " probably meant one of its copies; place one with create_instance,"
              " or edit the main directly")

      (not (ctc/in-component-copy? shape))
      (dm/str "detach_instance: " (atc/shape-label shape) " is not a component copy"
              " — it has no main to be severed from")

      (not (ctc/instance-root? shape))
      (let [root (ctn/get-instance-root objects shape)]
        (dm/str "detach_instance: " (atc/shape-label shape) " is a piece of a copy,"
                " not the copy itself — detach its root instead"
                (when root (dm/str ": " (dm/str (:id root)))))))))

(defn detach-instance
  [{:keys [shapeId]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)]
    (if-let [problem (detach-problem objects id)]
      (rx/throw (ex-info problem {}))
      (do
        (atc/interrupt!)
        (st/emit! (dwl/detach-component id))
        (rx/of {:shapeId shapeId
                :note (str "detached — these shapes are now local copies and will "
                           "NOT follow their main any more. Tell the user you did "
                           "this; it is not reversible by editing the main.")})))))

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

(defn set-variant-property
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
        (atc/interrupt!)
        (when rename
          (st/emit! (dwv/update-property-name vid pos rename
                                              {:trigger "agent:set_variant_property"})))
        (when (and componentId value)
          (st/emit! (dwv/update-property-value (parse-uuid componentId) pos value)))
        (rx/of {:axis (or rename property)
                :note (str "updated — the axis is set-wide, values are per member. "
                           "Verify with read_design.")})))))
