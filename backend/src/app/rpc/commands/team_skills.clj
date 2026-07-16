;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.team-skills
  "Team-promoted skills (US #12).

  A personal `profile_skill` promoted to a team lands here, shaped the same way
  (label/category/reactive/body) so it merges into every member's agent catalog
  by name, default on. Promotion disables the promoter's personal copy by
  pointing its `promoted_to` at the new row (a light link). Team-side management
  of these — edit / remove / inspect — is story #13."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :refer [check-read-permissions! check-edition-permissions!]]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))

(defn- row->skill
  [row]
  {:id (:id row)
   :name (:name row)
   :label (:label row)
   :category (:category row)
   :reactive (:reactive row)
   :trigger (:trigger-on row)
   :description (:description row)
   :body (:body row)
   :promoted-by (:promoted-by row)
   :source-skill-id (:source-profile-skill-id row)})

;; --- Query: a team's promoted skills

(def ^:private sql:get-team-skills
  "SELECT id, name, label, category, reactive, trigger_on, description, body,
          promoted_by, source_profile_skill_id
     FROM team_skill
    WHERE team_id = ?
    ORDER BY created_at")

(sv/defmethod ::get-team-skills
  {::doc/added "2.13"
   ::sm/params [:map {:title "get-team-skills"}
                [:team-id ::sm/uuid]]}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id team-id]}]
  (check-read-permissions! pool profile-id team-id)
  (->> (db/exec! pool [sql:get-team-skills team-id])
       (mapv row->skill)))

;; --- Mutation: promote a personal skill to the team

(def ^:private sql:names-like
  "SELECT name FROM team_skill WHERE team_id = ? AND (name = ? OR name LIKE ?)")

(defn- unique-name
  "The source slug, or the first free `slug-N`, so promoting a same-named skill
  into a team never collides."
  [conn team-id base]
  (let [taken (into #{} (map :name) (db/exec! conn [sql:names-like team-id base (str base "-%")]))]
    (if-not (contains? taken base)
      base
      (loop [n 2]
        (let [candidate (str base "-" n)]
          (if (contains? taken candidate)
            (recur (inc n))
            candidate))))))

(def ^:private schema:promote-skill
  [:map {:title "promote-skill"}
   [:source-id ::sm/uuid]
   [:team-id ::sm/uuid]
   [:name [:string {:min 1 :max 200}]]
   [:description {:optional true} [:maybe [:string {:max 4000}]]]])

(sv/defmethod ::promote-skill
  {::doc/added "2.13"
   ::sm/params schema:promote-skill
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id source-id team-id name description]}]
  (check-edition-permissions! conn profile-id team-id)
  (let [src (db/get conn :profile-skill {:id source-id})]
    (when (not= (:profile-id src) profile-id)
      (ex/raise :type :not-found :code :skill-not-found))
    (when (:promoted-to src)
      (ex/raise :type :validation :code :skill-already-promoted))
    ;; label = the team-facing name the promoter reviewed; the slug is inherited
    ;; from the source (deduped within the team) so it stays stable.
    (let [team-skill (db/insert! conn :team-skill
                                 {:team-id team-id
                                  :promoted-by profile-id
                                  :source-profile-skill-id source-id
                                  :name (unique-name conn team-id (:name src))
                                  :label name
                                  :category (:category src)
                                  :reactive (:reactive src)
                                  :trigger-on (:trigger-on src)
                                  :description (or description "")
                                  :body (:body src)})]
      (db/update! conn :profile-skill
                  {:promoted-to (:id team-skill)}
                  {:id source-id})
      (row->skill team-skill))))
