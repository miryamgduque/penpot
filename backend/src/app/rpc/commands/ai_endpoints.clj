;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.ai-endpoints
  "Model endpoints registered on this instance.

  Beside the built-in cloud providers, an organisation may run models of its
  own: a local runtime, a gateway. Such an endpoint is an address that speaks
  the OpenAI-compatible protocol, and the list of models it serves with what
  each can do, as its admin declares it.

  Only an instance admin registers one. Every user sees the registered ones
  by name and may use them; nobody else can make the server call an address.
  That is the whole point: the agent service reaches whatever it is handed,
  so what it is handed is decided here, by someone who answers for it.

  A registered endpoint is a provider like the others, with the id
  `endpoint:<uuid>`."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def provider-prefix "endpoint:")

(def max-endpoints 20)

(def max-models 50)

(defn endpoint-provider?
  "Whether `provider` is the id of a registered endpoint, by its form."
  [provider]
  (and (string? provider) (str/starts-with? provider provider-prefix)))

(defn provider-id
  [id]
  (str provider-prefix id))

(defn- endpoint-id
  "The uuid in a provider id, or nil."
  [provider]
  (when (endpoint-provider? provider)
    (uuid/parse* (subs provider (count provider-prefix)))))

(defn- decode-row
  [row]
  (some-> row (update :models db/decode-json-pgobject)))

(defn get-endpoint
  "The endpoint registered under `provider`, or nil: a provider id that names
  none is not an endpoint, whatever it looks like."
  [{:keys [::db/pool]} provider]
  (when-let [id (endpoint-id provider)]
    (decode-row (db/get* pool :ai-endpoint {:id id}))))

(defn get-endpoints
  [{:keys [::db/pool]}]
  (->> (db/exec! pool ["SELECT * FROM ai_endpoint"])
       (map decode-row)
       (sort-by (comp str/lower :name))
       (vec)))

(defn descriptor
  "An endpoint as the agent service takes it."
  [{:keys [id name base-uri models]}]
  {:provider (provider-id id)
   :name name
   :baseUrl base-uri
   :models (vec models)})

(defn records
  "The capability records of an endpoint's models: what its admin declared,
  and null for what they left out."
  [{:keys [id models]}]
  (mapv (fn [model]
          (merge {:provider (provider-id id)
                  :name (:id model)
                  :vision nil :tools nil :reasoning nil :promptCaching nil
                  :contextLimit nil :outputLimit nil :price nil}
                 model))
        models))

;; --- Who may register

(defn instance-admin?
  "Whether the profile is one of the instance's admins (PENPOT_ADMINS)."
  [{:keys [::db/pool]} profile-id]
  (let [email (some-> (db/get* pool :profile {:id profile-id}) :email str/lower)]
    ;; an address is one whatever its case: PENPOT_ADMINS is typed by hand
    (and (some? email)
         (contains? (into #{} (map str/lower) (cf/get :admins)) email))))

(defn- check-admin!
  [cfg profile-id]
  (when-not (instance-admin? cfg profile-id)
    (ex/raise :type :not-authorized
              :code :instance-admin-required
              :hint "only an administrator of this Penpot instance can register model endpoints")))

;; --- Query: the registered endpoints

(defn- shown
  "An endpoint as a user sees it: its name and models, and its address only
  for who may change it."
  [endpoint admin?]
  (cond-> {:id (:id endpoint)
           :provider (provider-id (:id endpoint))
           :name (:name endpoint)
           :requires-key (:requires-key endpoint)
           :models (records endpoint)}
    admin? (assoc :base-uri (:base-uri endpoint))))

(sv/defmethod ::get-ai-endpoints
  "The model endpoints registered on this instance, and whether the caller
  may register more."
  {::doc/added "2.20"
   ::sm/params [:map {:title "get-ai-endpoints"}]}
  [cfg {:keys [::rpc/profile-id]}]
  (let [admin? (instance-admin? cfg profile-id)]
    {:can-manage admin?
     :endpoints (mapv #(shown % admin?) (get-endpoints cfg))}))

;; --- Mutation: register or change an endpoint

(def ^:private schema:capability
  [:maybe ::sm/boolean])

(def ^:private schema:model
  [:map {:title "ai-endpoint-model"}
   [:id [:string {:min 1 :max 200}]]
   [:name {:optional true} [:maybe [:string {:max 200}]]]
   ;; a capability left out, or null, is unknown
   [:vision {:optional true} schema:capability]
   [:tools {:optional true} schema:capability]
   [:reasoning {:optional true} schema:capability]
   [:prompt-caching {:optional true} schema:capability]
   [:context-limit {:optional true} [:maybe [::sm/int {:min 1}]]]
   [:output-limit {:optional true} [:maybe [::sm/int {:min 1}]]]])

(def ^:private schema:set-ai-endpoint
  [:map {:title "set-ai-endpoint"}
   [:id {:optional true} ::sm/uuid]
   [:name [:string {:min 1 :max 80}]]
   [:base-uri [:string {:min 8 :max 500}]]
   [:requires-key {:optional true} ::sm/boolean]
   [:models [:vector {:min 1 :max max-models} schema:model]]])

(defn- check-address!
  "An endpoint's address is an http or https one with a host. It may well be
  a private address — a model on the organisation's own network is what this
  is for — which is why only an admin gets to say it."
  [base-uri]
  (let [uri (ex/ignoring (u/uri base-uri))]
    (when-not (and (some? uri)
                   (contains? #{"http" "https"} (:scheme uri))
                   (not (str/blank? (:host uri))))
      (ex/raise :type :validation
                :code :ai-endpoint-address-invalid
                :hint "an endpoint's address is an http or https URL, like http://models.internal:11434/v1"))))

(defn- stored-model
  "A model as it is kept: the capability record's own field names."
  [{:keys [id name vision tools reasoning prompt-caching context-limit output-limit]}]
  (d/without-nils
   {:id (str/trim id)
    :name (when-not (str/blank? name) (str/trim name))
    :vision vision
    :tools tools
    :reasoning reasoning
    :promptCaching prompt-caching
    :contextLimit context-limit
    :outputLimit output-limit}))

(sv/defmethod ::set-ai-endpoint
  "Registers a model endpoint, or changes one. Instance admins only."
  {::doc/added "2.20"
   ::sm/params schema:set-ai-endpoint
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id name base-uri requires-key models]}]
  (check-admin! (assoc cfg ::db/pool conn) profile-id)
  (check-address! base-uri)
  (let [models (mapv stored-model models)
        name   (str/trim name)
        others (->> (db/exec! conn ["SELECT id, name FROM ai_endpoint"])
                    (remove #(= id (:id %))))]
    (when (not= (count models) (count (distinct (map :id models))))
      (ex/raise :type :validation
                :code :ai-endpoint-models-repeated
                :hint "two models of an endpoint cannot have the same id"))
    (when (some #(= (str/lower name) (str/lower (:name %))) others)
      (ex/raise :type :validation
                :code :ai-endpoint-name-taken
                :hint "another endpoint has that name"))
    (when (and (nil? id) (<= max-endpoints (count others)))
      (ex/raise :type :validation
                :code :ai-endpoints-limit-reached
                :hint (str "an instance holds " max-endpoints " endpoints at most")))
    (let [params {:name name
                  :base-uri (str/rtrim (str/trim base-uri) "/")
                  :requires-key (boolean requires-key)
                  :models (db/json models)
                  :modified-at (ct/now)}
          row    (if (some? id)
                   (do (db/get conn :ai-endpoint {:id id})
                       (db/update! conn :ai-endpoint params {:id id} {::db/return-keys true}))
                   (db/insert! conn :ai-endpoint (assoc params :id (uuid/next) :created-by profile-id)
                               {::db/return-keys true}))]
      (shown (decode-row row) true))))

;; --- Mutation: remove an endpoint

(sv/defmethod ::delete-ai-endpoint
  "Removes a registered endpoint, and with it every user's connection to it:
  the server calls it no more. Instance admins only."
  {::doc/added "2.20"
   ::sm/params [:map {:title "delete-ai-endpoint"}
                [:id ::sm/uuid]]
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id id]}]
  (check-admin! (assoc cfg ::db/pool conn) profile-id)
  (db/delete! conn :profile-ai-provider {:provider (provider-id id)})
  (db/delete! conn :ai-endpoint {:id id})
  {:id id})
