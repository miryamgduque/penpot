;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.agent-chats
  "Per-user, per-file conversations with the embedded design agent.

  One row per conversation, keyed by a client-generated id and owned by
  (profile_id, file_id): every command is scoped to the calling profile, so a
  user only ever sees their own conversations — a file's collaborators each
  keep private histories (contrast design_skill, the team baseline).

  `data` is a transit-encoded map `{:messages … :history … :usage …}` — the
  rendered transcript, the canonical provider history, and the spend-meter
  totals. It is stored via `db/tjson` because the history is keyword-heavy
  frontend data that plain JSON would mangle. The schema accepts it as `:any`
  and the backend never interprets the content, but it does bound it: the
  encoded payload is capped at `max-data-size` and a profile keeps at most
  `max-chats-per-file` conversations per file. The panel trims well below the
  first limit (40 messages, ≤20k chars per tool result, no image payloads); the
  caps are for callers that do not go through the panel.

  Listing and saving need read permission on the file: a conversation is
  private to its owner, but it must not be attachable to a file the profile
  cannot open."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

(def ^:private max-data-size
  "Cap, in characters, for one conversation's encoded payload."
  (* 4 1024 1024))

(def ^:private max-chats-per-file
  "Cap for the conversations one profile keeps on one file."
  200)

;; --- Query: a file's conversation list (metadata only, never `data`)

(def ^:private schema:get-agent-chats
  [:map {:title "get-agent-chats"}
   [:file-id ::sm/uuid]])

(def ^:private sql:get-chats
  "SELECT id, title, created_at, updated_at
     FROM profile_agent_chat
    WHERE profile_id = ?
      AND file_id = ?
    ORDER BY updated_at DESC")

(sv/defmethod ::get-agent-chats
  {::doc/added "2.20"
   ::sm/params schema:get-agent-chats}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id file-id]}]
  (files/check-read-permissions! pool profile-id file-id)
  (->> (db/exec! pool [sql:get-chats profile-id file-id])
       (mapv (fn [row]
               {:id (:id row)
                :title (:title row)
                :created-at (:created-at row)
                :updated-at (:updated-at row)}))))

;; --- Query: one conversation, full payload

(def ^:private schema:get-agent-chat
  [:map {:title "get-agent-chat"}
   [:id ::sm/uuid]])

(sv/defmethod ::get-agent-chat
  {::doc/added "2.20"
   ::sm/params schema:get-agent-chat}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id]}]
  ;; profile-id in the lookup makes "not yours" and "does not exist"
  ;; indistinguishable — an id must not be probeable
  (let [row (db/get* pool :profile-agent-chat {:id id :profile-id profile-id})]
    (when-not row
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "agent chat not found"))
    {:id (:id row)
     :file-id (:file-id row)
     :title (:title row)
     :created-at (:created-at row)
     :updated-at (:updated-at row)
     :data (db/decode-transit-pgobject (:data row))}))

;; --- Mutation: save a conversation (insert or update)

(def ^:private schema:upsert-agent-chat
  [:map {:title "upsert-agent-chat"}
   [:id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:title [:string {:max 250}]]
   ;; transit map {:messages … :history … :usage …}; opaque to the backend
   [:data :any]])

;; No ORM upsert helper in app.db — a parameterized ON CONFLICT is the house
;; pattern (cf. ai-providers, skill-state). The update arm re-checks ownership:
;; without it, upserting a foreign id would let one profile overwrite (or
;; effectively read the existence of) another's conversation. A blocked
;; conflicting update simply affects zero rows — surfaced as not-found.
;;
;; `title` is written on INSERT only: it is derived from the conversation's
;; first user message (which never changes), and leaving it out of the update
;; arm is what makes a manual rename (`::rename-agent-chat`) durable across
;; later saves.
(def ^:private sql:upsert-chat
  "INSERT INTO profile_agent_chat (id, profile_id, file_id, title, data)
   VALUES (?, ?, ?, ?, ?)
   ON CONFLICT (id)
   DO UPDATE SET data = ?, updated_at = now()
    WHERE profile_agent_chat.profile_id = ?
      AND profile_agent_chat.file_id = ?")

(def ^:private sql:count-other-chats
  "SELECT count(*) AS total
     FROM profile_agent_chat
    WHERE profile_id = ?
      AND file_id = ?
      AND id <> ?")

(defn- check-chat-limits!
  "Rejects a payload over `max-data-size`, and a NEW conversation once the
  profile already keeps `max-chats-per-file` on the file. Saving an existing
  conversation is never blocked by the count."
  [pool profile-id file-id id ^org.postgresql.util.PGobject data]
  (when (and (some? data) (> (count (.getValue data)) max-data-size))
    (ex/raise :type :validation
              :code :agent-chat-too-large
              :hint "the conversation is too large to be saved"))
  (let [others (:total (db/exec-one! pool [sql:count-other-chats profile-id file-id id]))]
    (when (and (>= others max-chats-per-file)
               (nil? (db/get* pool :profile-agent-chat {:id id})))
      (ex/raise :type :validation
                :code :agent-chat-limit-reached
                :hint "too many conversations on this file; delete some first"))))

(sv/defmethod ::upsert-agent-chat
  {::doc/added "2.20"
   ::sm/params schema:upsert-agent-chat}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id file-id title data]}]
  (files/check-read-permissions! pool profile-id file-id)
  (let [data     (db/tjson data)
        _        (check-chat-limits! pool profile-id file-id id data)
        affected (->> (db/exec-one! pool [sql:upsert-chat
                                          id profile-id file-id title data
                                          data
                                          profile-id file-id])
                      (db/get-update-count))]
    (when (zero? affected)
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "agent chat not found"))
    ;; no :title echoed back: on conflict the stored title (possibly a manual
    ;; rename) wins over the derived one this call carried
    {:id id}))

;; --- Mutation: rename a conversation

(def ^:private schema:rename-agent-chat
  [:map {:title "rename-agent-chat"}
   [:id ::sm/uuid]
   [:title [:string {:min 1 :max 250}]]])

(def ^:private sql:rename-chat
  "UPDATE profile_agent_chat
      SET title = ?, updated_at = now()
    WHERE id = ? AND profile_id = ?")

(sv/defmethod ::rename-agent-chat
  {::doc/added "2.20"
   ::sm/params schema:rename-agent-chat}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id title]}]
  (let [affected (->> (db/exec-one! pool [sql:rename-chat title id profile-id])
                      (db/get-update-count))]
    (when (zero? affected)
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "agent chat not found"))
    {:id id :title title}))

;; --- Mutation: delete a conversation

(def ^:private schema:delete-agent-chat
  [:map {:title "delete-agent-chat"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-agent-chat
  {::doc/added "2.20"
   ::sm/params schema:delete-agent-chat}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id]}]
  (db/delete! pool :profile-agent-chat {:id id :profile-id profile-id})
  nil)
