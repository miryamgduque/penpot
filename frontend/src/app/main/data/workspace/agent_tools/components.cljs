;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.components
  "Component tools: variants, instances, overrides and detach."
  (:require
   [app.common.agent.components :as cac]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.types.component :as ctc]
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


;; --- Shared with the server
;;
;; The pure halves of the component tools live in
;; `app.common.agent.components`, shared with the server-side tools. They keep
;; their names here.

(def variant-member-ids cac/variant-member-ids)

(def variant-naming-hint cac/variant-naming-hint)

(def variant-members-problem cac/variant-members-problem)

(def add-variant-problem cac/add-variant-problem)

(def instance-problem cac/instance-problem)

(def detach-problem cac/detach-problem)

(def axis-pos cac/axis-pos)

(def variant-property-problem cac/variant-property-problem)

(def ^:private instance-copy cac/instance-copy)

(def ^:private not-a-copy-msg cac/not-a-copy-msg)

(def ^:private variant-facts cac/variant-facts)

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
