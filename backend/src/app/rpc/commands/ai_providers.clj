;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.ai-providers
  "Account-level AI provider connections for the embedded design agent.

  Several providers can be connected in parallel (one row per
  profile+provider): each holds an API key and the subset of that
  provider's models the user enabled for the chat pool. Keys are stored
  server-side and never returned to the client — reads expose only a
  last-4 hint.

  `connect-ai-provider` validates the key on submit by live-fetching the
  provider's model list (which doubles as the checklist shown on the
  card) and only stores the key when that succeeds. `ai-agent-round` is
  the buffered chat proxy: the panel names a provider and sends a
  provider-shaped JSON payload; the backend attaches the stored key and
  returns the provider response verbatim.

  NOTE(prototype): keys are stored in plain text; encrypt with the
  instance secret before this leaves the prototype stage."
  (:require
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.http.client :as http]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

;; Fixed, operator-known endpoints (hence skip-ssrf-check).
(def ^:private providers
  {"anthropic" {:kind :anthropic
                :base-uri "https://api.anthropic.com"}
   "openai"    {:kind :openai
                :base-uri "https://api.openai.com/v1"}
   "zhipu"     {:kind :openai
                :base-uri "https://api.z.ai/api/paas/v4"
                ;; /models is not uniformly available on the paas endpoint:
                ;; validate with a 1-token completion and fall back to a
                ;; curated list when listing fails
                :ping-model "glm-4.5-air"
                :fallback-models ["glm-4.6" "glm-4.5" "glm-4.5-air"]}
   "moonshot"  {:kind :openai
                :base-uri "https://api.moonshot.ai/v1"}})

(def ^:private valid-provider-ids
  (into #{} (keys providers)))

(defn- key-hint
  [api-key]
  (let [n (count api-key)]
    (subs api-key (max 0 (- n 4)) n)))

;; --- Provider HTTP

(defn- auth-headers
  [{:keys [kind]} api-key]
  (case kind
    :anthropic {"x-api-key" api-key
                "anthropic-version" "2023-06-01"}
    :openai    {"authorization" (str "Bearer " api-key)}))

(defn- provider-req!
  [cfg request provider-id]
  (try
    (http/req cfg request {:skip-ssrf-check? true})
    (catch Throwable cause
      (ex/raise :type :validation
                :code :ai-provider-unreachable
                :hint "could not reach the provider"
                :provider provider-id
                :cause cause))))

(defn- check-auth-status!
  [provider-id status]
  (when (contains? #{401 403} status)
    (ex/raise :type :validation
              :code :ai-provider-invalid-key
              :hint "the provider rejected the API key"
              :provider provider-id))
  (when-not (<= 200 status 299)
    (ex/raise :type :validation
              :code :ai-provider-error
              :hint (str "unexpected provider response (status " status ")")
              :provider provider-id
              :status status)))

;; OpenAI's /models mixes chat models with embeddings/audio/image models;
;; keep the checklist to chat-capable families. Other providers list only
;; chat models, pass everything through.
(defn- chat-model?
  [provider-id model-id]
  (if (= provider-id "openai")
    (and (or (str/starts-with? model-id "gpt-")
             (str/starts-with? model-id "o")
             (str/starts-with? model-id "chatgpt-"))
         (not (re-find #"embed|audio|tts|whisper|image|dall-e|moderation|transcribe|realtime|search" model-id)))
    true))

(defn- fetch-models!
  "Live model list for one provider, validating the key on the way. For
  providers without a listing endpoint, validates with a minimal completion
  and returns the curated fallback list."
  [cfg provider-id api-key]
  (let [{:keys [kind base-uri ping-model fallback-models] :as provider} (get providers provider-id)]
    (if (some? fallback-models)
      (let [response (provider-req! cfg
                                    {:method :post
                                     :uri (str base-uri "/chat/completions")
                                     :headers (merge (auth-headers provider api-key)
                                                     {"content-type" "application/json"})
                                     :body (json/encode {:model ping-model
                                                         :messages [{:role "user" :content "ping"}]
                                                         :max_tokens 1})
                                     :timeout (ct/duration "20s")}
                                    provider-id)]
        (check-auth-status! provider-id (:status response))
        fallback-models)

      (let [uri      (case kind
                       :anthropic (str base-uri "/v1/models?limit=100")
                       :openai    (str base-uri "/models"))
            response (provider-req! cfg
                                    {:method :get
                                     :uri uri
                                     :headers (auth-headers provider api-key)
                                     :timeout (ct/duration "10s")}
                                    provider-id)]
        (check-auth-status! provider-id (:status response))
        (let [parsed (json/decode (:body response) :key-fn keyword)]
          (->> (:data parsed)
               (map :id)
               (filter #(chat-model? provider-id %))
               (sort)
               (vec)))))))

;; --- Row helpers

(defn- decode-row
  [row]
  (some-> row
          (update :enabled-models db/decode-json-pgobject)))

(defn- get-provider-row
  [pool profile-id provider-id]
  (decode-row (db/get* pool :profile-ai-provider {:profile-id profile-id
                                                  :provider provider-id})))

(defn- row->status
  [row]
  {:provider (:provider row)
   :connected true
   :key-hint (key-hint (:api-key row))
   :enabled-models (vec (:enabled-models row))
   :connected-at (:updated-at row)})

;; --- Query: connected providers (status only, never the keys)

(def ^:private schema:get-ai-providers
  [:map {:title "get-ai-providers"}])

(sv/defmethod ::get-ai-providers
  {::doc/added "2.13"
   ::sm/params schema:get-ai-providers}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id]}]
  (->> (db/query pool :profile-ai-provider {:profile-id profile-id})
       (map (comp row->status decode-row))
       (sort-by :provider)
       (vec)))

;; --- Query: live model list of a connected provider

(def ^:private schema:get-ai-provider-models
  [:map {:title "get-ai-provider-models"}
   [:provider [::sm/one-of {:format "string"} valid-provider-ids]]])

(sv/defmethod ::get-ai-provider-models
  {::doc/added "2.13"
   ::sm/params schema:get-ai-provider-models}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id provider]}]
  (let [row (get-provider-row pool profile-id provider)]
    (when-not row
      (ex/raise :type :validation
                :code :ai-provider-not-connected
                :hint "provider not connected"
                :provider provider))
    {:provider provider
     :models (fetch-models! cfg provider (:api-key row))}))

;; --- Mutation: store / rotate key (no validation — see note below)
;;
;; The key is stored as-is, WITHOUT a live check against the provider: an
;; invalid key, a revoked key, or an account without funds surfaces only
;; when the user actually sends a message (see `ai-agent-round`). This
;; keeps the settings page instant and avoids a provider round-trip on
;; every keystroke/blur. On conflict the enabled-model selection is kept.

(def ^:private schema:set-ai-provider-key
  [:map {:title "set-ai-provider-key"}
   [:provider [::sm/one-of {:format "string"} valid-provider-ids]]
   [:api-key [:string {:min 8 :max 500}]]])

;; No ORM/upsert helper in app.db — a parameterized ON CONFLICT statement
;; is the house pattern for insert-or-update (cf. teams_invitations,
;; files_thumbnails). enabled_models is set only on insert; a key rotation
;; keeps the existing selection.
(def ^:private sql:upsert-provider-key
  "INSERT INTO profile_ai_provider (profile_id, provider, api_key, enabled_models)
   VALUES (?, ?, ?, ?)
   ON CONFLICT (profile_id, provider)
   DO UPDATE SET api_key = ?, updated_at = now()")

(sv/defmethod ::set-ai-provider-key
  {::doc/added "2.13"
   ::sm/params schema:set-ai-provider-key}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id provider api-key]}]
  (let [row  (get-provider-row pool profile-id provider)
        kept (vec (:enabled-models row []))]
    (db/exec-one! pool [sql:upsert-provider-key
                        profile-id provider api-key (db/json kept)
                        api-key])
    {:provider provider
     :connected true
     :key-hint (key-hint api-key)
     :enabled-models kept}))

;; --- Mutation: connect / rotate key (validate on submit, store on success)
;;
;; DEPRECATED by ::set-ai-provider-key — kept for external/API callers that
;; still want an upfront validation. The settings UI no longer calls it.

(def ^:private schema:connect-ai-provider
  [:map {:title "connect-ai-provider"}
   [:provider [::sm/one-of {:format "string"} valid-provider-ids]]
   [:api-key [:string {:min 8 :max 500}]]])

;; Like sql:upsert-provider-key but also rewrites enabled_models — the
;; validated model list may have dropped some of the previous selection.
(def ^:private sql:upsert-provider-connection
  "INSERT INTO profile_ai_provider (profile_id, provider, api_key, enabled_models)
   VALUES (?, ?, ?, ?)
   ON CONFLICT (profile_id, provider)
   DO UPDATE SET api_key = ?, enabled_models = ?, updated_at = now()")

(sv/defmethod ::connect-ai-provider
  {::doc/added "2.13"
   ::sm/params schema:connect-ai-provider}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id provider api-key]}]
  ;; validation doubles as the model fetch: an invalid key stores nothing
  (let [models (fetch-models! cfg provider api-key)
        row    (get-provider-row pool profile-id provider)
        ;; on key rotation keep the enabled selection, dropping models the
        ;; provider no longer offers
        kept   (vec (filter (set models) (:enabled-models row [])))]
    (db/exec-one! pool [sql:upsert-provider-connection
                        profile-id provider api-key (db/json kept)
                        api-key (db/json kept)])
    {:provider provider
     :connected true
     :key-hint (key-hint api-key)
     :models models
     :enabled-models kept}))

;; --- Mutation: choose which models feed the chat pool

(def ^:private schema:set-ai-provider-models
  [:map {:title "set-ai-provider-models"}
   [:provider [::sm/one-of {:format "string"} valid-provider-ids]]
   [:models [:vector [:string {:max 200}]]]])

(sv/defmethod ::set-ai-provider-models
  {::doc/added "2.13"
   ::sm/params schema:set-ai-provider-models}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id provider models]}]
  (let [row (get-provider-row pool profile-id provider)]
    (when-not row
      (ex/raise :type :validation
                :code :ai-provider-not-connected
                :hint "provider not connected"
                :provider provider))
    (db/update! pool :profile-ai-provider
                {:enabled-models (db/json (vec (distinct models)))
                 :updated-at (ct/now)}
                {:profile-id profile-id :provider provider})
    {:provider provider
     :enabled-models (vec (distinct models))}))

;; --- Mutation: disconnect one provider

(def ^:private schema:disconnect-ai-provider
  [:map {:title "disconnect-ai-provider"}
   [:provider [::sm/one-of {:format "string"} valid-provider-ids]]])

(sv/defmethod ::disconnect-ai-provider
  {::doc/added "2.13"
   ::sm/params schema:disconnect-ai-provider}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id provider]}]
  (db/delete! pool :profile-ai-provider {:profile-id profile-id
                                         :provider provider})
  nil)

;; --- Mutation: one buffered agent round through a connected provider

(def ^:private schema:ai-agent-round
  [:map {:title "ai-agent-round"}
   [:provider [::sm/one-of {:format "string"} valid-provider-ids]]
   ;; provider-shaped JSON, built by the panel, forwarded verbatim so no
   ;; transit/JSON key mangling can corrupt it
   [:payload [:string {:max 4000000}]]])

(sv/defmethod ::ai-agent-round
  {::doc/added "2.13"
   ::sm/params schema:ai-agent-round}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id provider payload]}]
  (let [row (get-provider-row pool profile-id provider)]
    (when-not row
      (ex/raise :type :validation
                :code :ai-provider-not-connected
                :hint "provider not connected"
                :provider provider))
    (let [{:keys [kind base-uri] :as pconf} (get providers provider)
          request  {:method :post
                    :uri (case kind
                           :anthropic (str base-uri "/v1/messages")
                           :openai    (str base-uri "/chat/completions"))
                    :headers (merge (auth-headers pconf (:api-key row))
                                    {"content-type" "application/json"})
                    :body payload
                    :timeout (ct/duration "240s")}
          response (provider-req! cfg request provider)]
      {:provider provider
       :status (:status response)
       :body (:body response)})))
