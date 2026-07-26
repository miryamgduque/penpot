;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.design-sessions
  "Recorded design sessions: what happened to a file, who did it, and on which
  model.

  A session is a record of identifiable people's activity on a shared document,
  so who may read one is a product decision, not a default. Santi's direction
  (2026-07-25): **the audience is a bot** — \"something admins can export to feed
  agent sessions\". That splits the surface three ways:

  - **WRITE** belongs to the recorder. Creating and updating a session needs
    EDIT permission on the file (you may record what you may edit) and the row is
    owned by `(profile_id, session_id)`. A finished session is immutable.
  - **READ** (`::get-design-sessions`, `::get-design-session`) is for team
    **admins**, plus the recorder's own sessions so the person who pressed Record
    can see what they captured.
  - **EXPORT** (`::export-design-sessions`) is **admins only**. This is the
    bot-facing path: bulk, structured, ready to feed to an agent.

  ## Two pools, deliberately

  Sessions live in a SEPARATE database with no foreign keys to product tables
  (see `app.migrations/session-migrations`), so no join can answer \"may this
  profile read this session?\". These handlers therefore touch BOTH pools: the
  main pool for the permission check, the sessions pool for the data. That is the
  one place the isolation is intentionally crossed, and it is why every command
  resolves permissions explicitly instead of relying on SQL.

  The sessions database is optional — Penpot runs without it. When it is absent
  the pool is nil and these commands fail with `:sessions-database-unavailable`
  rather than NPE-ing.

  NOTE(prototype): no per-file row quota (same gap as `profile_agent_chat`), and
  no reaper for sessions whose file or profile has been deleted — cross-database
  cascades do not exist."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

;; The sessions pool is injected into `:app.rpc/methods` under `app.main`'s own
;; component key. Referenced as a literal keyword rather than through a require:
;; `app.main` pulls in the whole rpc tree, so requiring it here would cycle.
(def ^:private pool-key :app.main/sessions-pool)

(defn- check-enabled!
  "Recording is off unless a deployment turns it on. Enforced here and not only
  in the UI: a hidden button is not an access boundary, and this feature records
  identifiable people."
  []
  (when-not (contains? cf/flags :design-session-recording)
    (ex/raise :type :restriction
              :code :feature-disabled
              :hint "design session recording is not enabled on this instance")))

(defn- get-sessions-pool
  [cfg]
  (check-enabled!)
  (or (get cfg pool-key)
      (ex/raise :type :precondition
                :code :sessions-database-unavailable
                :hint "the design sessions database is not configured")))

(def ^:private sql:file-team
  "SELECT p.team_id
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE f.id = ?
      AND f.deleted_at IS NULL")

(defn- get-file-team-id
  [conn file-id]
  (or (:team-id (db/exec-one! conn [sql:file-team file-id]))
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "file not found")))

(defn- admin?
  "True when the caller administers the team owning `file-id`. Owners count as
  admins (`teams/get-permissions`)."
  [conn profile-id file-id]
  (let [team-id (get-file-team-id conn file-id)]
    (teams/has-admin-permissions? conn profile-id team-id)))

(defn- check-can-read!
  "A session is readable by a team admin, or by the profile that recorded it."
  [conn profile-id file-id owner-id]
  (when-not (or (= profile-id owner-id)
                (admin? conn profile-id file-id))
    (ex/raise :type :not-found
              :code :object-not-found
              :hint "design session not found")))

(defn- decode-row
  [row]
  (-> row
      (dissoc :events)
      (assoc :events (db/decode-transit-pgobject (:events row)))))

;; --- Mutation: save a session (insert or update)
;;
;; One idempotent upsert rather than create/append/finish, matching
;; `::upsert-agent-chat`: the client owns a bounded timeline (capped at 5000
;; events, raw ops never sent) and re-sends it, so a replayed or retried flush
;; cannot duplicate anything. `stop-reason` arriving non-nil is what finishes the
;; session.

(def ^:private schema:upsert-design-session
  [:map {:title "upsert-design-session"}
   [:id ::sm/uuid]
   [:file-id ::sm/uuid]
   [:session-id ::sm/uuid]
   ;; transit vector of session events; opaque to the backend, bounded client-side
   [:events :any]
   [:raw-dropped {:optional true} ::sm/int]
   ;; non-nil finishes the session and makes it immutable
   [:stop-reason {:optional true} [:maybe [:string {:max 50}]]]])

;; The update arm re-checks ownership AND that the session is not already
;; finished. Without the ownership check, upserting a foreign id would let one
;; profile overwrite another's recording; without the stopped_at check, a late
;; flush could reopen and rewrite a closed session. A blocked update affects zero
;; rows, which surfaces as not-found.
(def ^:private sql:upsert-session
  "INSERT INTO design_session (id, file_id, profile_id, session_id, events,
                               raw_dropped, stop_reason, stopped_at)
   VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END)
   ON CONFLICT (id)
   DO UPDATE SET events = ?,
                 raw_dropped = ?,
                 stop_reason = ?,
                 stopped_at = CASE WHEN ?::text IS NULL
                                   THEN design_session.stopped_at
                                   ELSE now() END,
                 updated_at = now()
    WHERE design_session.profile_id = ?
      AND design_session.session_id = ?
      AND design_session.stopped_at IS NULL")

(sv/defmethod ::upsert-design-session
  {::doc/added "2.13"
   ::sm/params schema:upsert-design-session}
  [{:keys [::db/pool] :as cfg}
   {:keys [::rpc/profile-id id file-id session-id events raw-dropped stop-reason]}]
  ;; recording is a write on the file's behalf: it needs edit permission, and
  ;; this is the check that stops a viewer from recording a file they can only see
  (files/check-edition-permissions! pool profile-id file-id)

  (let [spool    (get-sessions-pool cfg)
        events   (db/tjson events)
        dropped  (or raw-dropped 0)
        affected (->> (db/exec-one! spool [sql:upsert-session
                                           id file-id profile-id session-id events
                                           dropped stop-reason stop-reason
                                           events dropped stop-reason stop-reason
                                           profile-id session-id])
                      (db/get-update-count))]
    (when (zero? affected)
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "design session not found, not yours, or already finished"))
    {:id id}))

;; --- Query: a file's sessions (metadata only, never the timeline)

(def ^:private schema:get-design-sessions
  [:map {:title "get-design-sessions"}
   [:file-id ::sm/uuid]])

(def ^:private sql:sessions-for-file
  "SELECT id, file_id, profile_id, session_id, started_at, stopped_at,
          stop_reason, raw_dropped, created_at, updated_at,
          jsonb_array_length(events) AS event_count,
          (review IS NOT NULL) AS has_review
     FROM design_session
    WHERE file_id = ?
    ORDER BY started_at DESC")

(sv/defmethod ::get-design-sessions
  {::doc/added "2.13"
   ::sm/params schema:get-design-sessions}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id]}]
  (files/check-read-permissions! pool profile-id file-id)
  (let [spool (get-sessions-pool cfg)
        rows  (db/exec! spool [sql:sessions-for-file file-id])]
    ;; admins see every session on the file; everyone else sees only their own
    (if (admin? pool profile-id file-id)
      (vec rows)
      (filterv #(= profile-id (:profile-id %)) rows))))

;; --- Query: one session, full timeline

(def ^:private schema:get-design-session
  [:map {:title "get-design-session"}
   [:id ::sm/uuid]])

(sv/defmethod ::get-design-session
  {::doc/added "2.13"
   ::sm/params schema:get-design-session}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id id]}]
  (let [spool (get-sessions-pool cfg)
        row   (db/get* spool :design-session {:id id})]
    (when-not row
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "design session not found"))
    (check-can-read! pool profile-id (:file-id row) (:profile-id row))
    (decode-row row)))

;; --- Query: bulk export for a bot
;;
;; The reason this feature exists: an admin hands a file's recorded sessions to
;; an agent to critique. Admins only — a plain editor may read their own sessions
;; but not harvest everybody's.
;;
;; Deliberately NOT rendered to prose here. The events are already semantic
;; (`:kind`, `:label`, `:who`, `:model`, `:at`), so a consumer can feed them
;; directly, and rendering server-side would mean a second copy of
;; `session-events/timeline->prompt-text` in Clojure that could drift from the
;; frontend's.

(def ^:private schema:export-design-sessions
  [:map {:title "export-design-sessions"}
   [:file-id ::sm/uuid]
   ;; omit to export every session on the file
   [:ids {:optional true} [:vector ::sm/uuid]]])

(sv/defmethod ::export-design-sessions
  {::doc/added "2.13"
   ::sm/params schema:export-design-sessions}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id ids]}]
  (files/check-read-permissions! pool profile-id file-id)
  (let [team-id (get-file-team-id pool file-id)]
    (teams/check-admin-permissions! pool profile-id team-id))

  (let [spool    (get-sessions-pool cfg)
        wanted   (some-> (seq ids) set)
        sessions (->> (db/exec! spool [sql:sessions-for-file file-id])
                      (filter (fn [row] (or (nil? wanted) (contains? wanted (:id row)))))
                      (mapv (fn [row]
                              (let [full (db/get* spool :design-session {:id (:id row)})]
                                (decode-row full)))))]
    {:file-id file-id
     :session-count (count sessions)
     :sessions sessions}))
