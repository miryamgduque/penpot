;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.design-skills
  "CRUD for design skills & rules.

  A design skill is a small markdown document with metadata that agents
  working on Penpot files inherit as context (kind=skill) or as a checkable
  constraint (kind=rule). Rows with team_id NULL form the app-level
  (instance-wide) set, seeded from the official penpot-ai-kit; team rows are
  managed per team. File-scope skills live inside the design files themselves
  (shared pluginData), not here.

  The app-level (team_id NULL) set is not writable through these RPCs: it is
  seed-only, so a team member cannot rewrite the instance-wide context every
  other team inherits. Only team-scoped rows can be created, edited or deleted,
  and only by a team editor."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :refer [check-read-permissions! check-edition-permissions!]]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

(def ^:private valid-kinds
  #{"skill" "rule"})

(def ^:private valid-enforcements
  #{"advisory" "triggered" "enforced"})

(defn- check-team-scope!
  "The app-level (team_id NULL) set is seed-only over RPC; only team-scoped
  rows may be mutated, and only by a team editor."
  [conn profile-id team-id]
  (when (nil? team-id)
    (ex/raise :type :validation
              :code :not-allowed
              :hint "app-level design skills are read-only"))
  (check-edition-permissions! conn profile-id team-id))

;; --- Query: get design skills (app + team + team overrides)

(def ^:private sql:get-app-skills
  "SELECT * FROM design_skill WHERE team_id IS NULL ORDER BY name")

(def ^:private sql:get-team-skills
  "SELECT * FROM design_skill WHERE team_id = ? ORDER BY name")

(def ^:private sql:get-overrides
  "SELECT skill_name FROM design_skill_override
    WHERE team_id = ? AND is_enabled IS FALSE")

(def ^:private schema:get-design-skills
  [:map {:title "get-design-skills"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-design-skills
  {::doc/added "2.13"
   ::sm/params schema:get-design-skills}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (check-read-permissions! pool profile-id team-id)
  {:app (vec (db/exec! pool [sql:get-app-skills]))
   :team (vec (db/exec! pool [sql:get-team-skills team-id]))
   :overrides (mapv :skill-name (db/exec! pool [sql:get-overrides team-id]))})

;; --- Mutation: create design skill

(def ^:private schema:create-design-skill
  [:map {:title "create-design-skill"}
   [:team-id {:optional true} [:maybe ::sm/uuid]]
   [:name [:string {:min 1 :max 250}]]
   [:kind [::sm/one-of {:format "string"} valid-kinds]]
   [:enforcement [::sm/one-of {:format "string"} valid-enforcements]]
   [:is-mandatory {:optional true} ::sm/boolean]
   [:trigger-on {:optional true} [:maybe [:string {:max 100}]]]
   [:description {:optional true} [:string {:max 2000}]]
   [:body {:optional true} :string]])

(sv/defmethod ::create-design-skill
  {::doc/added "2.13"
   ::sm/params schema:create-design-skill}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id team-id name kind enforcement
                               is-mandatory trigger-on description body]}]
  (check-team-scope! pool profile-id team-id)
  (db/insert! pool :design-skill
              {:team-id team-id
               :name name
               :kind kind
               :enforcement enforcement
               :is-mandatory (boolean is-mandatory)
               :trigger-on trigger-on
               :description (or description "")
               :body (or body "")}))

;; --- Mutation: update design skill

(def ^:private schema:update-design-skill
  [:map {:title "update-design-skill"}
   [:id ::sm/uuid]
   [:name {:optional true} [:string {:min 1 :max 250}]]
   [:kind {:optional true} [::sm/one-of {:format "string"} valid-kinds]]
   [:enforcement {:optional true} [::sm/one-of {:format "string"} valid-enforcements]]
   [:is-mandatory {:optional true} ::sm/boolean]
   [:trigger-on {:optional true} [:maybe [:string {:max 100}]]]
   [:description {:optional true} [:string {:max 2000}]]
   [:body {:optional true} :string]
   [:is-enabled {:optional true} ::sm/boolean]])

(sv/defmethod ::update-design-skill
  {::doc/added "2.13"
   ::sm/params schema:update-design-skill}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id id] :as params}]
  (let [skill (db/get pool :design-skill {:id id})]
    (check-team-scope! pool profile-id (:team-id skill))
    (let [patch (-> (select-keys params [:name :kind :enforcement :is-mandatory
                                         :trigger-on :description :body :is-enabled])
                    (assoc :updated-at (ct/now)))]
      (db/update! pool :design-skill patch {:id id} {::db/return-keys true}))))

;; --- Mutation: delete design skill

(def ^:private schema:delete-design-skill
  [:map {:title "delete-design-skill"}
   [:id ::sm/uuid]])

(sv/defmethod ::delete-design-skill
  {::doc/added "2.13"
   ::sm/params schema:delete-design-skill
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id]}]
  (let [skill (db/get conn :design-skill {:id id})]
    (check-team-scope! conn profile-id (:team-id skill))
    (db/delete! conn :design-skill {:id id})
    nil))

;; --- Mutation: enable/disable an inherited app skill for one team

(def ^:private schema:set-inherited-skill-enabled
  [:map {:title "set-inherited-skill-enabled"}
   [:team-id ::sm/uuid]
   [:skill-name [:string {:min 1 :max 250}]]
   [:enabled ::sm/boolean]])

(sv/defmethod ::set-inherited-skill-enabled
  {::doc/added "2.13"
   ::sm/params schema:set-inherited-skill-enabled
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id team-id skill-name enabled]}]
  (check-edition-permissions! conn profile-id team-id)
  (db/delete! conn :design-skill-override {:team-id team-id :skill-name skill-name})
  (when-not enabled
    (db/insert! conn :design-skill-override
                {:team-id team-id
                 :skill-name skill-name
                 :is-enabled false}))
  nil)
