;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.agent-state
  "Per-user, per-file on/off override of the design agent.

  The account setting lives in the profile props (`:agent-disabled`). A row
  in `profile_agent_state` is an exception to it for one file, and the
  frontend resolves the effective state as instance flag, then account, then
  file. Only true exceptions are stored: the frontend sends a nil `enabled`
  when the user picks the account's own value, and that deletes the row.

  Every command is scoped to the calling profile, so an override is private
  to its owner and never affects collaborators. Reading or setting one needs
  read permission on the file.

  This only hides the agent in the browser. The server does not read it to
  block model calls."
  (:require
   [app.common.schema :as sm]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

;; --- Query: this file's override

(def ^:private schema:get-agent-file-state
  [:map {:title "get-agent-file-state"}
   [:file-id ::sm/uuid]])

(sv/defmethod ::get-agent-file-state
  "The user's override for one file. `enabled` is nil when the file follows
  the account setting."
  {::doc/added "2.20"
   ::sm/params schema:get-agent-file-state}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id file-id]}]
  (files/check-read-permissions! pool profile-id file-id)
  (let [row (db/get* pool :profile-agent-state
                     {:profile-id profile-id :file-id file-id})]
    {:file-id file-id
     :enabled (:enabled row)}))

;; --- Mutation: set or clear this file's override

(def ^:private schema:set-agent-file-state
  [:map {:title "set-agent-file-state"}
   [:file-id ::sm/uuid]
   [:enabled [:maybe :boolean]]])

(def ^:private sql:upsert-state
  "INSERT INTO profile_agent_state (profile_id, file_id, enabled)
   VALUES (?, ?, ?)
   ON CONFLICT (profile_id, file_id)
   DO UPDATE SET enabled = ?, updated_at = now()")

(sv/defmethod ::set-agent-file-state
  "Store the user's override for one file, or delete it when `enabled` is
  nil so the file follows the account setting again."
  {::doc/added "2.20"
   ::sm/params schema:set-agent-file-state}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id file-id enabled]}]
  (files/check-read-permissions! pool profile-id file-id)
  (if (nil? enabled)
    (db/delete! pool :profile-agent-state
                {:profile-id profile-id :file-id file-id})
    (db/exec-one! pool [sql:upsert-state profile-id file-id enabled enabled]))
  {:file-id file-id
   :enabled enabled})

;; --- Query: how many files the user turned the agent on in

;; Files in the trash (or in a deleted project or team) and files the user
;; can no longer open are left out. The team, project and file memberships
;; are checked in the query, as `app.binfile.common/get-file-permissions`
;; does. A file the user reaches by none of them goes through the full read
;; check (`files/has-read-permissions?`), so the count agrees with the one
;; `set-agent-file-state` applies; that path is rare, so it costs at most a
;; few extra queries.
(def ^:private sql:enabled-files
  "SELECT s.file_id,
          (EXISTS (SELECT 1 FROM team_profile_rel AS tpr
                    WHERE tpr.team_id = t.id
                      AND tpr.profile_id = s.profile_id)
           OR EXISTS (SELECT 1 FROM project_profile_rel AS ppr
                       WHERE ppr.project_id = p.id
                         AND ppr.profile_id = s.profile_id)
           OR EXISTS (SELECT 1 FROM file_profile_rel AS fpr
                       WHERE fpr.file_id = f.id
                         AND fpr.profile_id = s.profile_id)) AS is_member
     FROM profile_agent_state AS s
    INNER JOIN file AS f ON (f.id = s.file_id)
    INNER JOIN project AS p ON (p.id = f.project_id)
    INNER JOIN team AS t ON (t.id = p.team_id)
    WHERE s.profile_id = ?
      AND s.enabled
      AND f.deleted_at IS NULL
      AND p.deleted_at IS NULL
      AND t.deleted_at IS NULL")

(sv/defmethod ::get-agent-enabled-file-count
  "Count of the files, still open to the user, where they turned the agent
  on as an exception to their account setting."
  {::doc/added "2.20"
   ::sm/params [:map {:title "get-agent-enabled-file-count"}]}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id]}]
  (let [rows (db/exec! pool [sql:enabled-files profile-id])]
    {:count (count (filter (fn [{:keys [is-member file-id]}]
                             (or is-member
                                 (files/has-read-permissions? pool profile-id file-id)))
                           rows))}))

;; --- Mutation: turn the agent off in every file the user turned it on in

(sv/defmethod ::disable-agent-in-enabled-files
  "Delete every file override that turns the agent on, so those files
  follow the account setting again. Rows for files the user can no longer
  reach go too: they could never apply again."
  {::doc/added "2.20"
   ::sm/params [:map {:title "disable-agent-in-enabled-files"}]}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id]}]
  (let [result (db/delete! pool :profile-agent-state
                           {:profile-id profile-id :enabled true})]
    {:count (db/get-update-count result)}))
