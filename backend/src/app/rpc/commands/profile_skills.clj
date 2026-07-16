;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.profile-skills
  "Per-user, user-created skills (US #9).

  A profile's own skills, stored with the full generated document (label,
  category, reactive, trigger, body). On the client they merge into the agent's
  catalog and are toggled through `profile_skill_state` exactly like the built-in
  skills — `is_enabled` here is only the creation default (on). Contrast
  `design_skill`, which is the app/team registry."
  (:require
   [app.common.schema :as sm]
   [app.db :as db]
   [app.rpc :as-alias rpc]
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
   :enabled (:is-enabled row)})

;; --- Query: the caller's own created skills

(def ^:private sql:get-skills
  "SELECT id, name, label, category, reactive, trigger_on, description, body, is_enabled
     FROM profile_skill
    WHERE profile_id = ?
    ORDER BY created_at")

(sv/defmethod ::get-skills
  {::doc/added "2.13"
   ::sm/params [:map {:title "get-skills"}]}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id]}]
  (->> (db/exec! pool [sql:get-skills profile-id])
       (mapv row->skill)))

;; --- Mutation: create a skill

(def ^:private schema:create-skill
  [:map {:title "create-skill"}
   [:name [:string {:min 1 :max 200}]]
   [:label [:string {:min 1 :max 200}]]
   [:category [:string {:min 1 :max 100}]]
   [:reactive [:enum "on-call" "observer"]]
   [:trigger {:optional true} [:maybe [:string {:max 2000}]]]
   [:description {:optional true} [:maybe [:string {:max 4000}]]]
   [:body [:string {:min 1 :max 100000}]]])

(def ^:private sql:names-like
  "SELECT name FROM profile_skill
    WHERE profile_id = ? AND (name = ? OR name LIKE ?)")

(defn- unique-name
  "The requested slug, or the first free `slug-N`, so a create never collides with
  an existing skill of the same name for this profile."
  [pool profile-id base]
  (let [taken (into #{} (map :name) (db/exec! pool [sql:names-like profile-id base (str base "-%")]))]
    (if-not (contains? taken base)
      base
      (loop [n 2]
        (let [candidate (str base "-" n)]
          (if (contains? taken candidate)
            (recur (inc n))
            candidate))))))

(sv/defmethod ::create-skill
  {::doc/added "2.13"
   ::sm/params schema:create-skill}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id name label category reactive trigger description body]}]
  (let [uname (unique-name pool profile-id name)]
    (-> (db/insert! pool :profile-skill
                    {:profile-id profile-id
                     :name uname
                     :label label
                     :category category
                     :reactive reactive
                     :trigger-on trigger
                     :description (or description "")
                     :body body})
        (row->skill))))
