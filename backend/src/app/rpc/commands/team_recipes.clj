;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.team-recipes
  "A team's own recipes for the design agent.

  A recipe is the structure of a common piece of UI as data; see
  `app.common.agent.recipe`. The built-in ones ship with Penpot and cannot be
  changed. A team adds its own here: written by a member, saved from a file by
  the agent, or brought in as a file. A team recipe of the name of a built-in
  one is used in its place, by every member, until it is disabled or removed.

  A recipe arrives as JSON and is checked before it is kept: what is not in
  the format is refused, and the answer names it. Nothing in a recipe is ever
  run."
  (:require
   [app.common.agent.recipe :as car]
   [app.common.agent.recipes :as cars]
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :refer [check-read-permissions! check-edition-permissions!]]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [cuerdas.core :as str])
  (:import
   java.io.InputStream))

(def ^:private max-content-chars 200000)

(def origins #{"team" "saved" "imported" "community"})

(defn- parse
  "The recipe a JSON text holds, or nil when it is not JSON for a map."
  [content]
  (try
    (let [value (json/decode content :key-fn keyword)]
      (when (map? value) value))
    (catch Throwable _ nil)))

(defn- row->recipe
  [row]
  {:id (:id row)
   :name (:name row)
   :recipe (parse (:content row))
   :origin (:origin row)
   :origin-note (:origin-note row)
   :disabled (boolean (:is-disabled row))
   ;; whether it stands in for a built-in recipe of the same name
   :overrides-built-in (contains? cars/builtin (:name row))
   :created-by-name (or (not-empty (:created-by-fullname row)) (:created-by-email row))
   :updated-at (:updated-at row)})

(def ^:private sql:get-team-recipes
  "SELECT r.id, r.name, r.content, r.origin, r.origin_note, r.is_disabled, r.updated_at,
          p.fullname AS created_by_fullname, p.email AS created_by_email
     FROM team_recipe AS r
     LEFT JOIN profile AS p ON (p.id = r.created_by)
    WHERE r.team_id = ?
    ORDER BY r.name")

(defn enabled-recipes
  "Name → recipe: the team's own recipes that are in use, as the agent's
  tools take them over the built-in ones."
  [conn team-id]
  (into {}
        (comp (remove :is-disabled)
              (keep (fn [row]
                      (when-let [recipe (parse (:content row))]
                        [(:name row) recipe]))))
        (db/exec! conn [sql:get-team-recipes team-id])))

(defn check!
  "The recipe a JSON text holds, checked against the format and against the
  recipes it would live with. Raises a validation error that names what is
  wrong."
  [conn team-id content]
  (let [recipe  (parse content)
        others  (when (some? recipe) (merge cars/builtin (enabled-recipes conn team-id)))
        problem (cond
                  (> (count content) max-content-chars)
                  "the recipe is too large"

                  (nil? recipe)
                  "this is not a recipe: it is not JSON for an object"

                  :else
                  (car/problem recipe (assoc others (:name recipe) recipe)))]
    (when (some? problem)
      (ex/raise :type :validation
                :code :invalid-recipe
                :hint problem))
    recipe))

(defn store!
  "Keeps `recipe` for the team, in place of the one of that name it had.
  → the row."
  [conn team-id profile-id recipe {:keys [origin origin-note disabled]}]
  (let [params (cond-> {:content (json/encode recipe)
                        :origin (if (contains? origins origin) origin "team")
                        :origin-note origin-note
                        :updated-at (ct/now)}
                 (some? disabled) (assoc :is-disabled disabled))]
    (if-let [found (db/get* conn :team-recipe {:team-id team-id :name (:name recipe)})]
      (db/update! conn :team-recipe params {:id (:id found)} {::db/return-keys true})
      (db/insert! conn :team-recipe (assoc params
                                           :team-id team-id
                                           :created-by profile-id
                                           :name (:name recipe))))))

;; --- Query: the recipes of a team

(sv/defmethod ::get-team-recipes
  {::doc/added "2.20"
   ::sm/params [:map {:title "get-team-recipes"}
                [:team-id ::sm/uuid]]}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id team-id]}]
  (check-read-permissions! pool profile-id team-id)
  (mapv row->recipe (db/exec! pool [sql:get-team-recipes team-id])))

;; --- Mutation: add a recipe, or replace the one of its name

(def ^:private schema:save-team-recipe
  [:map {:title "save-team-recipe"}
   [:team-id ::sm/uuid]
   ;; the recipe, as JSON
   [:content [:string {:min 2 :max 400000}]]
   ;; "community" is not said by a caller: it is what import-community-recipe
   ;; writes
   [:origin {:optional true} [:enum "team" "saved" "imported"]]
   [:origin-note {:optional true} [:maybe [:string {:max 300}]]]])

(sv/defmethod ::save-team-recipe
  "Adds a recipe to the team, or replaces the one of the same name. The
  recipe is checked first: a problem is answered as a validation error whose
  hint names it."
  {::doc/added "2.20"
   ::sm/params schema:save-team-recipe
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id team-id content origin origin-note]}]
  (check-edition-permissions! conn profile-id team-id)
  (let [recipe (check! conn team-id content)]
    (row->recipe (store! conn team-id profile-id recipe {:origin origin :origin-note origin-note}))))

;; --- Mutation: take a recipe out of use, or put it back

(sv/defmethod ::set-team-recipe-disabled
  {::doc/added "2.20"
   ::sm/params [:map {:title "set-team-recipe-disabled"}
                [:id ::sm/uuid]
                [:disabled ::sm/boolean]]
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id disabled]}]
  (let [found (db/get conn :team-recipe {:id id})]
    (check-edition-permissions! conn profile-id (:team-id found))
    (row->recipe (db/update! conn :team-recipe
                             {:is-disabled disabled :updated-at (ct/now)}
                             {:id id}
                             {::db/return-keys true}))))

;; --- Mutation: remove a recipe

(sv/defmethod ::delete-team-recipe
  {::doc/added "2.20"
   ::sm/params [:map {:title "delete-team-recipe"}
                [:id ::sm/uuid]]
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id]}]
  (let [found (db/get conn :team-recipe {:id id})]
    (check-edition-permissions! conn profile-id (:team-id found))
    (db/delete! conn :team-recipe {:id id})
    {:id id}))

;; --- The community source
;;
;; A public index of recipe files, curated by review where it is kept: a JSON
;; file `{"recipes": [{"name" "title" "purpose" "category" "author" "url"}]}`,
;; at the address the instance is configured with. With none configured there
;; is no community source.
;;
;; Taking a recipe from it copies the file into the team: fetched by the
;; server, checked like any other, labelled with where it came from, and
;; disabled until a member who can edit the team enables it. A recipe is data
;; and is never run, wherever it comes from.

(def ^:private max-index-chars 400000)

(def ^:private max-entries 500)

(defn- fetch-text!
  "GETs `uri` as text, with the client's checks on where it may reach, on
  every hop → the text, or nil when it could not be had or is too large."
  [cfg uri limit]
  (try
    (let [{:keys [status ^InputStream body]}
          (http/req-with-redirects cfg
                                   {:method :get
                                    :uri (str uri)
                                    :headers {"accept" "application/json,text/plain;q=0.9,*/*;q=0.1"}
                                    :timeout (ct/duration "10s")}
                                   {:response-type :input-stream :max-redirects 3})]
      (with-open [input body]
        (when (<= 200 status 299)
          (let [bytes (.readNBytes input (int (inc (* 4 limit))))
                text  (String. ^bytes bytes "UTF-8")]
            (when (<= (count text) limit)
              text)))))
    (catch java.io.IOException _ nil)))

(defn- index-uri
  []
  (cf/get :recipe-community-index-uri))

(defn- entry
  "One line of the index as it is shown, or nil when it is not one."
  [index-uri {:keys [name title purpose category author url]}]
  (when (and (string? name) (string? url) (not (str/blank? name)))
    {:name name
     :title (if (string? title) title name)
     :purpose (if (string? purpose) purpose "")
     :category (when (string? category) category)
     :author (when (string? author) author)
     ;; a file may be named next to the index
     :url (str (u/join (u/uri (str index-uri)) url))}))

(defn- community-index
  "The entries of the community index, or nil when there is no index or it
  cannot be read."
  [cfg]
  (when-let [uri (index-uri)]
    (when-let [text (fetch-text! cfg uri max-index-chars)]
      (let [parsed (parse text)]
        (when (sequential? (:recipes parsed))
          (into [] (comp (filter map?) (keep #(entry uri %)) (take max-entries)) (:recipes parsed)))))))

(sv/defmethod ::get-community-recipes
  "What the community index offers. `:configured` says whether this instance
  has a community source at all; `:available` whether it could be read."
  {::doc/added "2.20"
   ::sm/params [:map {:title "get-community-recipes"}
                [:team-id ::sm/uuid]]}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id]}]
  (check-read-permissions! pool profile-id team-id)
  (let [entries (community-index cfg)
        mine    (into #{} (map :name) (db/exec! pool [sql:get-team-recipes team-id]))]
    {:configured (some? (index-uri))
     :available (some? entries)
     :source (some-> (index-uri) str u/uri :host)
     :recipes (mapv #(-> (dissoc % :url) (assoc :in-team (contains? mine (:name %)))) entries)}))

(sv/defmethod ::import-community-recipe
  "Copies a recipe of the community index into the team: checked, labelled
  with where it came from, and disabled until someone enables it."
  {::doc/added "2.20"
   ::sm/params [:map {:title "import-community-recipe"}
                [:team-id ::sm/uuid]
                [:name [:string {:min 1 :max 200}]]]}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id name]}]
  (check-edition-permissions! pool profile-id team-id)
  (let [found   (some #(when (= name (:name %)) %) (community-index cfg))
        content (when found (fetch-text! cfg (:url found) max-content-chars))]
    (when (nil? found)
      (ex/raise :type :not-found
                :code :community-recipe-not-found
                :hint "the community index has no recipe of that name"))
    (when (nil? content)
      (ex/raise :type :validation
                :code :invalid-recipe
                :hint "the recipe's file could not be fetched, or is too large"))
    (db/tx-run! cfg
                (fn [{:keys [::db/conn]}]
                  (let [recipe (check! conn team-id content)]
                    (when (not= name (:name recipe))
                      (ex/raise :type :validation
                                :code :invalid-recipe
                                :hint (str "the index calls it \"" name "\" and the file \""
                                           (:name recipe) "\"")))
                    (row->recipe
                     (store! conn team-id profile-id recipe
                             {:origin "community"
                              :origin-note (str (some-> (index-uri) str u/uri :host)
                                                (when (:author found) (str " · " (:author found))))
                              :disabled true})))))))
