;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.skill-state
  "Per-user on/off state for the design agent's built-in skills.

  A row keyed by (profile_id, skill, file_id) where a NULL `file_id` is the
  user's account-wide default for a skill and a set `file_id` overrides it for
  that one file. Both scopes are private to the user (per-profile) — a file
  override is never shared with other collaborators (contrast
  design_skill_override, which is the team baseline). The frontend resolves the
  effective state as built-in default → account → per-file."
  (:require
   [app.common.schema :as sm]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

;; --- Query: the user's stored states — account rows always, plus this file's

(def ^:private schema:get-skill-states
  [:map {:title "get-skill-states"}
   [:file-id {:optional true} [:maybe ::sm/uuid]]])

(def ^:private sql:get-states
  "SELECT skill, file_id, enabled
     FROM profile_skill_state
    WHERE profile_id = ?
      AND (file_id IS NULL OR file_id = ?)")

(sv/defmethod ::get-skill-states
  {::doc/added "2.13"
   ::sm/params schema:get-skill-states}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id file-id]}]
  (->> (db/exec! pool [sql:get-states profile-id file-id])
       (mapv (fn [row]
               {:skill (:skill row)
                :file-id (:file-id row)
                :enabled (:enabled row)}))))

;; --- Mutation: set a skill's on/off state (NULL file-id = account default)

(def ^:private schema:set-skill-enabled
  [:map {:title "set-skill-enabled"}
   [:skill [:string {:min 1 :max 200}]]
   [:enabled :boolean]
   [:file-id {:optional true} [:maybe ::sm/uuid]]])

;; No ORM upsert helper in app.db — a parameterized ON CONFLICT is the house
;; pattern (cf. ai-providers). The conflict target repeats the COALESCE
;; unique-index expression so a NULL file_id maps to a single account slot.
(def ^:private sql:upsert-enabled
  "INSERT INTO profile_skill_state (profile_id, skill, file_id, enabled)
   VALUES (?, ?, ?, ?)
   ON CONFLICT (profile_id, skill, COALESCE(file_id, '00000000-0000-0000-0000-000000000000'::uuid))
   DO UPDATE SET enabled = ?, updated_at = now()")

(sv/defmethod ::set-skill-enabled
  {::doc/added "2.13"
   ::sm/params schema:set-skill-enabled}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id skill enabled file-id]}]
  (db/exec-one! pool [sql:upsert-enabled profile-id skill file-id enabled enabled])
  {:skill skill :file-id file-id :enabled enabled})
