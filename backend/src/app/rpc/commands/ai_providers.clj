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
  returns the provider response verbatim. It serves the panel's side work
  — the watcher, playbook matching, summaries — not the agent's turns, which
  run in the agent service.

  It is a dumb pipe: the payload is forwarded byte-for-byte and the response
  is never decoded, so this namespace stays free of provider dialects. Only
  credential injection lives here.

  Keys are encrypted at rest with a key derived from the instance secret
  (see `encrypt-key`). Rows written before that are plain text; they keep
  working and are re-encrypted the first time they are read. A key that can
  no longer be decrypted (the instance secret changed) reads as
  `:ai-provider-key-unreadable` and the user reconnects the provider."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.http.sse :as sse]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.agent-tools :as agent-tools]
   [app.rpc.commands.ai-endpoints :as endpoints]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.setup :as-alias setup]
   [app.setup.keys :as keys]
   [app.util.events :as events]
   [app.util.services :as sv]
   [app.util.ssrf :as ssrf]
   [buddy.core.codecs :as bc]
   [buddy.sign.jwe :as jwe]
   [clojure.java.io :as io]
   [cuerdas.core :as str])
  (:import
   java.io.BufferedReader
   java.io.InputStream))

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
                :base-uri "https://api.moonshot.ai/v1"}
   ;; The four below are offered only once each has passed the provider
   ;; verification check (`check-ai-model`) on this instance's version of the
   ;; provider layer: until then they are behind the flag named in
   ;; `preview-providers-flag`.
   "google"    {:kind :openai
                :preview true
                ;; Gemini's OpenAI-compatible surface; its ids come as `models/<id>`
                :base-uri "https://generativelanguage.googleapis.com/v1beta/openai"}
   "xai"       {:kind :openai
                :preview true
                :base-uri "https://api.x.ai/v1"}
   "mistral"   {:kind :openai
                :preview true
                :base-uri "https://api.mistral.ai/v1"}
   "deepseek"  {:kind :openai
                :preview true
                :base-uri "https://api.deepseek.com"}})

(def preview-providers-flag
  "The flag that offers the providers not yet verified on this instance."
  :design-agent-preview-providers)

(defn- offered?
  [provider-id]
  (when-let [provider (get providers provider-id)]
    (or (not (:preview provider))
        (contains? cf/flags preview-providers-flag))))

(def ^:private valid-provider-ids
  (into #{} (keys providers)))

;; A provider is one of the built-in ones, or an endpoint an instance admin
;; registered (`endpoint:<uuid>`, see `app.rpc.commands.ai-endpoints`). The
;; form of the id is checked here; that an endpoint of that id IS registered
;; is checked where the provider is used.
(def ^:private schema:provider-id
  [:and
   [:string {:max 80}]
   [:fn {:error/message "not a provider id"}
    (fn [provider]
      (or (contains? valid-provider-ids provider)
          (endpoints/endpoint-provider? provider)))]])

(defn- registered-endpoint
  "The endpoint registered under `provider`; raises when it names none. The
  server calls no address that is neither built in nor registered."
  [cfg provider]
  (or (endpoints/get-endpoint cfg provider)
      (ex/raise :type :validation
                :code :ai-provider-not-registered
                :hint (str "this is neither a built-in provider nor an endpoint registered on this "
                           "Penpot instance, so it is not called")
                :provider provider)))

(defn- check-provider!
  "Raises unless `provider` is built in and offered here, or a registered
  endpoint."
  [cfg provider]
  (cond
    (offered? provider)
    nil

    (contains? valid-provider-ids provider)
    (ex/raise :type :validation
              :code :ai-provider-not-offered
              :hint "this provider is not offered on this Penpot instance yet"
              :provider provider)

    :else
    (do (registered-endpoint cfg provider) nil)))

(defn- endpoints-header
  "The header that hands the agent service the endpoints `provider-ids`
  name, or nil when none of them is an endpoint."
  [cfg provider-ids]
  (let [found (into []
                    (comp (filter endpoints/endpoint-provider?)
                          (distinct)
                          (map #(endpoints/descriptor (registered-endpoint cfg %))))
                    provider-ids)]
    (when (seq found)
      {"x-penpot-endpoints" (bc/bytes->b64-str (.getBytes ^String (json/encode found) "UTF-8"))})))

(defn- key-hint
  [api-key]
  (let [n (count api-key)]
    (subs api-key (max 0 (- n 4)) n)))

;; --- Key encryption at rest
;;
;; A stored key is `enc:v1:<jwe>`: the API key encrypted with a 256-bit key
;; derived from the instance secret under its own salt, so it shares nothing
;; with the tokens key. The prefix is what tells an encrypted value from a
;; legacy plain-text one, and leaves room to rotate the scheme.

(def ^:private encrypted-prefix "enc:v1:")

(def ^:private jwe-options
  {:alg :a256kw :enc :a256gcm})

(defn- storage-key
  [{:keys [::setup/props]}]
  (let [secret (get props :secret-key)]
    (assert (string? secret) "expect props to have secret-key")
    (keys/derive secret :salt "ai-providers")))

(defn- encrypted?
  [stored]
  (str/starts-with? stored encrypted-prefix))

(defn- encrypt-key
  [cfg api-key]
  (str encrypted-prefix (jwe/encrypt api-key (storage-key cfg) jwe-options)))

(defn- decrypt-key
  "The API key behind a stored value. Legacy plain-text values are returned
  as they are; an encrypted value that does not decrypt raises
  `:ai-provider-key-unreadable`."
  [cfg provider-id stored]
  (if (encrypted? stored)
    (try
      (-> (subs stored (count encrypted-prefix))
          (jwe/decrypt (storage-key cfg) jwe-options)
          (bc/bytes->str))
      (catch Throwable cause
        (ex/raise :type :validation
                  :code :ai-provider-key-unreadable
                  :hint "the stored API key cannot be read; connect the provider again"
                  :provider provider-id
                  :cause cause)))
    stored))

;; --- Provider HTTP

(defn- auth-headers
  [{:keys [kind]} api-key]
  (case kind
    :anthropic {"x-api-key" api-key
                "anthropic-version" "2023-06-01"}
    :openai    {"authorization" (str "Bearer " api-key)}))

(defn- provider-req!
  ([cfg request provider-id]
   (provider-req! cfg request provider-id {}))
  ([cfg request provider-id options]
   (try
     (http/req cfg request (merge {:skip-ssrf-check? true} options))
     (catch Throwable cause
       (ex/raise :type :validation
                 :code :ai-provider-unreachable
                 :hint "could not reach the provider"
                 :provider provider-id
                 :cause cause)))))

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
  (case provider-id
    "openai"
    (and (or (str/starts-with? model-id "gpt-")
             (str/starts-with? model-id "o")
             (str/starts-with? model-id "chatgpt-"))
         (not (re-find #"embed|audio|tts|whisper|image|dall-e|moderation|transcribe|realtime|search" model-id)))

    "google"
    (and (str/includes? model-id "gemini")
         (not (re-find #"embed|tts|image|live|audio" model-id)))

    "mistral"
    (not (re-find #"embed|moderation|ocr|transcribe|voxtral" model-id))

    true))

(defn- fetch-models!
  "Live model list for one provider, validating the key on the way. For
  providers without a listing endpoint, validates with a minimal completion
  and returns the curated fallback list."
  [cfg provider-id api-key]
  (let [{:keys [kind base-uri ping-model fallback-models] :as provider} (get providers provider-id)]
    (cond
      ;; a registered endpoint serves the models its admin listed: it is not
      ;; asked, and a key is not tried against it
      (endpoints/endpoint-provider? provider-id)
      (mapv :id (:models (registered-endpoint cfg provider-id)))

      (some? fallback-models)
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

      :else
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
               ;; Gemini lists its models as `models/<id>`
               (map #(str/replace (str (:id %)) #"^models/" ""))
               (filter #(chat-model? provider-id %))
               (sort)
               (vec)))))))

;; --- Row helpers

(defn- decode-row
  [row]
  (some-> row
          (update :enabled-models db/decode-json-pgobject)))

(defn- get-provider-row
  "The profile's row for one provider, with `:api-key` decrypted. A legacy
  plain-text key is encrypted in place on the way."
  [{:keys [::db/pool] :as cfg} profile-id provider-id]
  (when-let [row (decode-row (db/get* pool :profile-ai-provider {:profile-id profile-id
                                                                 :provider provider-id}))]
    (let [stored (:api-key row)]
      (when-not (encrypted? stored)
        (db/update! pool :profile-ai-provider
                    {:api-key (encrypt-key cfg stored)}
                    {:profile-id profile-id :provider provider-id}))
      (assoc row :api-key (decrypt-key cfg provider-id stored)))))

(defn- row->status
  "Status of a stored row, never the key. An unreadable key still lists as
  connected, with an empty hint: the user finds out and reconnects on use."
  [cfg row]
  {:provider (:provider row)
   :connected true
   :key-hint (or (ex/ignoring (key-hint (decrypt-key cfg (:provider row) (:api-key row))))
                 "")
   :enabled-models (vec (:enabled-models row))
   ;; the model of this provider chosen for side jobs, when one is
   :helper-model (let [helper (:helper-model row)]
                   (when (some #{helper} (:enabled-models row)) helper))
   :connected-at (:updated-at row)})

;; --- Query: connected providers (status only, never the keys)

(def ^:private schema:get-ai-providers
  [:map {:title "get-ai-providers"}])

(sv/defmethod ::get-ai-providers
  {::doc/added "2.20"
   ::sm/params schema:get-ai-providers}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id]}]
  (->> (db/query pool :profile-ai-provider {:profile-id profile-id})
       (map (comp (partial row->status cfg) decode-row))
       (sort-by :provider)
       (vec)))

;; --- Query: live model list of a connected provider

(def ^:private schema:get-ai-provider-models
  [:map {:title "get-ai-provider-models"}
   [:provider schema:provider-id]])

(sv/defmethod ::get-ai-provider-models
  {::doc/added "2.20"
   ::sm/params schema:get-ai-provider-models}
  [cfg {:keys [::rpc/profile-id provider]}]
  (let [row (get-provider-row cfg profile-id provider)]
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
   [:provider schema:provider-id]
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
  {::doc/added "2.20"
   ::sm/params schema:set-ai-provider-key}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id provider api-key]}]
  (check-provider! cfg provider)
  (let [row  (get-provider-row cfg profile-id provider)
        ;; a registered endpoint has few models and each was put there on
        ;; purpose: all are on from the start
        kept (if (and (nil? row) (endpoints/endpoint-provider? provider))
               (fetch-models! cfg provider api-key)
               (vec (:enabled-models row [])))]
    (let [stored (encrypt-key cfg api-key)]
      (db/exec-one! pool [sql:upsert-provider-key
                          profile-id provider stored (db/json kept)
                          stored]))
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
   [:provider schema:provider-id]
   [:api-key [:string {:min 8 :max 500}]]])

;; Like sql:upsert-provider-key but also rewrites enabled_models — the
;; validated model list may have dropped some of the previous selection.
(def ^:private sql:upsert-provider-connection
  "INSERT INTO profile_ai_provider (profile_id, provider, api_key, enabled_models)
   VALUES (?, ?, ?, ?)
   ON CONFLICT (profile_id, provider)
   DO UPDATE SET api_key = ?, enabled_models = ?, updated_at = now()")

(sv/defmethod ::connect-ai-provider
  {::doc/added "2.20"
   ::sm/params schema:connect-ai-provider}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id provider api-key]}]
  ;; validation doubles as the model fetch: an invalid key stores nothing
  (let [models (fetch-models! cfg provider api-key)
        row    (get-provider-row cfg profile-id provider)
        ;; on key rotation keep the enabled selection, dropping models the
        ;; provider no longer offers
        kept   (vec (filter (set models) (:enabled-models row [])))]
    (let [stored (encrypt-key cfg api-key)]
      (db/exec-one! pool [sql:upsert-provider-connection
                          profile-id provider stored (db/json kept)
                          stored (db/json kept)]))
    {:provider provider
     :connected true
     :key-hint (key-hint api-key)
     :models models
     :enabled-models kept}))

;; --- Mutation: choose which models feed the chat pool

(def ^:private schema:set-ai-provider-models
  [:map {:title "set-ai-provider-models"}
   [:provider schema:provider-id]
   [:models [:vector [:string {:max 200}]]]])

(sv/defmethod ::set-ai-provider-models
  {::doc/added "2.20"
   ::sm/params schema:set-ai-provider-models}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id provider models]}]
  (let [row (get-provider-row cfg profile-id provider)]
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
   [:provider schema:provider-id]])

(sv/defmethod ::disconnect-ai-provider
  {::doc/added "2.20"
   ::sm/params schema:disconnect-ai-provider}
  [{:keys [::db/pool]} {:keys [::rpc/profile-id provider]}]
  (db/delete! pool :profile-ai-provider {:profile-id profile-id
                                         :provider provider})
  nil)

;; --- Mutation: one buffered agent round through a connected provider

(def ^:private schema:ai-agent-round
  [:map {:title "ai-agent-round"}
   [:provider schema:provider-id]
   ;; provider-shaped JSON, built by the panel, forwarded verbatim so no
   ;; transit/JSON key mangling can corrupt it
   [:payload [:string {:max 4000000}]]])

(defn- round-request
  "The provider HTTP request for one agent round. Shared by the buffered and
  streaming commands so the URI/auth knowledge lives in one place."
  [{:keys [kind base-uri] :as pconf} api-key payload]
  {:method :post
   :uri (case kind
          :anthropic (str base-uri "/v1/messages")
          :openai    (str base-uri "/chat/completions"))
   :headers (merge (auth-headers pconf api-key)
                   {"content-type" "application/json"})
   :body payload
   :timeout (ct/duration "240s")})

(defn- connected-provider-row
  [cfg profile-id provider]
  (or (get-provider-row cfg profile-id provider)
      (ex/raise :type :validation
                :code :ai-provider-not-connected
                :hint "provider not connected"
                :provider provider)))

(sv/defmethod ::ai-agent-round
  {::doc/added "2.20"
   ::sm/params schema:ai-agent-round}
  [cfg {:keys [::rpc/profile-id provider payload]}]
  (when-not (contains? valid-provider-ids provider)
    (ex/raise :type :validation
              :code :ai-provider-not-supported
              :hint "this command reaches the built-in providers only"
              :provider provider))
  (let [row      (connected-provider-row cfg profile-id provider)
        request  (round-request (get providers provider) (:api-key row) payload)
        response (provider-req! cfg request provider)]
    {:provider provider
     :status (:status response)
     :body (:body response)}))

;; --- The helper model
;;
;; A user may choose one model for the agent's side jobs — summaries,
;; scouting, playbook matching — apart from the main model they build with.
;; It is one enabled model of one connected provider. With none chosen, or
;; when the chosen one is no longer enabled, the helper is the main model.

(defn- helper-row
  "The profile's row that holds its helper model, with the key decrypted, or
  nil: none chosen, or the chosen model is no longer enabled."
  [{:keys [::db/pool] :as cfg} profile-id]
  (when-let [row (->> (db/query pool :profile-ai-provider {:profile-id profile-id})
                      (map decode-row)
                      (filter (fn [{:keys [helper-model enabled-models]}]
                                (and (string? helper-model) (some #{helper-model} enabled-models))))
                      (first))]
    (assoc row :api-key (decrypt-key cfg (:provider row) (:api-key row)))))

(defn- helper-of
  "→ `{:provider :model :api-key}` for the profile's side jobs: its helper
  when it chose one, else the main model `provider`/`model`."
  [cfg profile-id provider model]
  (if-let [row (helper-row cfg profile-id)]
    {:provider (:provider row) :model (:helper-model row) :api-key (:api-key row)}
    (when (and (string? provider) (string? model))
      {:provider provider :model model
       :api-key (:api-key (connected-provider-row cfg profile-id provider))})))

(def ^:private schema:set-ai-helper-model
  [:map {:title "set-ai-helper-model"}
   ;; both absent: no helper, the main model does the side jobs
   [:provider {:optional true} [:maybe schema:provider-id]]
   [:model {:optional true} [:maybe [:string {:max 200}]]]])

(sv/defmethod ::set-ai-helper-model
  "Chooses the model for the agent's side jobs, or none."
  {::doc/added "2.20"
   ::sm/params schema:set-ai-helper-model
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id provider model]}]
  (when (and (some? provider) (some? model))
    (let [row (connected-provider-row (assoc cfg ::db/pool conn) profile-id provider)]
      (when-not (some #{model} (:enabled-models row))
        (ex/raise :type :validation
                  :code :ai-model-not-enabled
                  :hint "the helper has to be one of the models you enabled for that provider"
                  :provider provider
                  :model model))))
  (db/update! conn :profile-ai-provider {:helper-model nil} {:profile-id profile-id}
              {::db/return-keys false})
  (when (and (some? provider) (some? model))
    (db/update! conn :profile-ai-provider {:helper-model model}
                {:profile-id profile-id :provider provider}
                {::db/return-keys false}))
  {:provider (when (some? model) provider)
   :model (when (some? provider) model)})

;; --- External MCP servers
;;
;; A profile can connect MCP servers of other services to the agent: their
;; tools become the agent's for that user's turns. The server is called by the
;; agent service, never by the browser, so its address is held to the same
;; rule as any address a user gives the backend to fetch, and its token is
;; kept like a provider key: encrypted, and never returned.

(def ^:private max-mcp-servers 8)

(def ^:private mcp-server-name-re #"^[A-Za-z0-9_-]{1,32}$")

(defn- check-mcp-enabled!
  []
  (when-not (contains? cf/flags :design-agent-mcp)
    (ex/raise :type :restriction
              :code :mcp-servers-not-enabled
              :hint "connecting external MCP servers is not enabled on this Penpot instance")))

(defn- check-mcp-url!
  "A server has to be reached over https, at a public address."
  [url]
  (let [uri (try (java.net.URI. url) (catch Throwable _ nil))]
    (when-not (= "https" (some-> uri .getScheme str/lower))
      (ex/raise :type :validation
                :code :mcp-server-url-not-https
                :hint "the server address must start with https://"))
    (ssrf/validate-uri uri)))

(defn- disabled-tools
  "The tools of a server its owner switched off, by the server's own names."
  [row]
  (or (try
        (some-> (:disabled-tools row) json/decode vec)
        (catch Throwable _ nil))
      []))

(defn- mcp-server-row->entry
  "What a client may know of a registered server."
  [row]
  {:id (:id row)
   :name (:name row)
   :url (:url row)
   :has-token (some? (:token row))
   :is-enabled (:is-enabled row)
   :disabled-tools (disabled-tools row)})

(defn- get-mcp-server-rows
  [cfg profile-id]
  (db/query cfg :profile-mcp-server {:profile-id profile-id} {:order-by [[:name :asc]]}))

(def ^:private schema:get-agent-mcp-servers
  [:map {:title "get-agent-mcp-servers"}])

(sv/defmethod ::get-agent-mcp-servers
  "The MCP servers this profile connected to the agent, without their tokens."
  {::doc/added "2.20"
   ::sm/params schema:get-agent-mcp-servers}
  [cfg {:keys [::rpc/profile-id]}]
  (mapv mcp-server-row->entry (get-mcp-server-rows cfg profile-id)))

(def ^:private schema:set-agent-mcp-server
  [:map {:title "set-agent-mcp-server"}
   [:name [:string {:min 1 :max 32}]]
   [:url [:string {:min 1 :max 2048}]]
   ;; left out: the stored token is kept; empty: it is removed
   [:token {:optional true} [:string {:max 4096}]]
   [:is-enabled {:optional true} ::sm/boolean]
   ;; left out: what was switched off stays switched off
   [:disabled-tools {:optional true}
    [:vector {:max 256} [:string {:min 1 :max 128}]]]])

(sv/defmethod ::set-agent-mcp-server
  "Connects an MCP server under `name`, or changes the one that has it."
  {::doc/added "2.20"
   ::sm/params schema:set-agent-mcp-server
   ::db/transaction true}
  [cfg {:keys [::rpc/profile-id name url token is-enabled disabled-tools] :as params}]
  (check-mcp-enabled!)
  (when-not (re-matches mcp-server-name-re name)
    (ex/raise :type :validation
              :code :mcp-server-name-invalid
              :hint "the name can have letters, digits, - and _, up to 32 of them"))
  (check-mcp-url! url)
  (let [rows     (get-mcp-server-rows cfg profile-id)
        existing (d/seek #(= name (:name %)) rows)
        token    (cond
                   (not (contains? params :token)) (:token existing)
                   (str/blank? token)              nil
                   :else                           (encrypt-key cfg token))
        off      (cond
                   (some? disabled-tools)        (json/encode (vec (distinct disabled-tools)))
                   ;; another address is another server: its tools are not
                   ;; the ones that were switched off
                   (= url (:url existing))       (:disabled-tools existing)
                   :else                         nil)]
    (when (and (nil? existing) (>= (count rows) max-mcp-servers))
      (ex/raise :type :restriction
                :code :mcp-servers-limit-reached
                :hint (str "at most " max-mcp-servers " MCP servers can be connected")))
    (-> (if existing
          (db/update! cfg :profile-mcp-server
                      {:url url
                       :token token
                       :disabled-tools off
                       :is-enabled (if (some? is-enabled) is-enabled (:is-enabled existing))
                       :updated-at (ct/now)}
                      {:id (:id existing)}
                      {::db/return-keys true})
          (db/insert! cfg :profile-mcp-server
                      {:profile-id profile-id
                       :name name
                       :url url
                       :token token
                       :disabled-tools off
                       :is-enabled (if (some? is-enabled) is-enabled true)}))
        (mcp-server-row->entry))))

(def ^:private schema:delete-agent-mcp-server
  [:map {:title "delete-agent-mcp-server"}
   [:name [:string {:min 1 :max 32}]]])

(sv/defmethod ::delete-agent-mcp-server
  {::doc/added "2.20"
   ::sm/params schema:delete-agent-mcp-server}
  [cfg {:keys [::rpc/profile-id name]}]
  (db/delete! cfg :profile-mcp-server {:profile-id profile-id :name name})
  nil)

(defn- service-mcp-server
  "A server as the agent service takes it, or nil when its address is no
  longer one the backend lets a user reach: what was public when it was
  registered may not be now."
  [cfg {:keys [name url token] :as row}]
  (when (try (check-mcp-url! url) true
             (catch Throwable _ false))
    (let [off (disabled-tools row)]
      (cond-> {:name name :url url}
        (seq off)
        (assoc :disabledTools off)

        (some? token)
        (assoc :headers {"Authorization"
                         (str "Bearer " (decrypt-key cfg name token))})))))

(defn- turn-mcp-servers
  "The profile's enabled servers as the agent service takes them, in one
  header value, or nil when there are none or the feature is off."
  [cfg profile-id]
  (when (contains? cf/flags :design-agent-mcp)
    (let [servers (into []
                        (comp (filter :is-enabled)
                              (keep (partial service-mcp-server cfg)))
                        (get-mcp-server-rows cfg profile-id))]
      (when (seq servers)
        (.encodeToString (java.util.Base64/getEncoder)
                         (.getBytes ^String (json/encode servers) "UTF-8"))))))

;; --- Agent service
;;
;; The agent's loop runs in a separate service built on DeepSeek Harness. The
;; backend is the place where the credential is attached: the panel sends a
;; turn, the backend adds the profile's decrypted key and a turn token, and
;; forwards it; the service's event stream is re-emitted over ours.
;;
;; What the service asks of a connected workspace travels the same way: it
;; emits a `tool-call` event, the panel answers through
;; `agent-service-tool-result`. The service never sees a session cookie and
;; the browser never sees the service.

(defn- agent-service-uri
  []
  (or (some-> (cf/get :agent-service-uri) str (str/rtrim "/"))
      (ex/raise :type :validation
                :code :agent-service-not-configured
                :hint (str "The design agent's service is not configured on this Penpot "
                           "instance. An administrator has to set PENPOT_AGENT_SERVICE_URI."))))

(defn- agent-service-req!
  [cfg path headers body options]
  ;; resolved out here: "not configured" must not be reported as "unreachable"
  (let [uri (str (agent-service-uri) path)]
    (try
      (http/req cfg
                {:method (if (nil? body) :get :post)
                 :uri uri
                 :headers (cond-> (merge {"content-type" "application/json"} headers)
                            (cf/get :agent-service-shared-key)
                            (assoc "x-penpot-shared-key" (cf/get :agent-service-shared-key)))
                 :body body
                 :timeout (ct/duration "900s")}
                (merge {:skip-ssrf-check? true} options))
      (catch Throwable cause
        (ex/raise :type :validation
                  :code :agent-service-unreachable
                  :hint "The design agent's service could not be reached. Try again in a moment."
                  :cause cause)))))

(defn- check-agent-service-status!
  [{:keys [status body]}]
  (when-not (<= 200 status 299)
    (let [message (try
                    (some-> (if (string? body) body (slurp body))
                            (json/decode :key-fn keyword)
                            :error)
                    (catch Throwable _ nil))]
      (ex/raise :type :validation
                :code :agent-service-error
                :hint (or message (str "unexpected agent service response (status " status ")"))
                :status status))))

(defn- pump-agent-service-stream!
  "Re-emits the service's newline-delimited JSON events, one `event` per line,
  payload verbatim. Closing the reader on the way out is what tells the
  service the client is gone, which aborts the turn there."
  [^InputStream input]
  (with-open [reader (io/reader input :encoding "UTF-8")]
    (loop [events 0]
      (let [line (.readLine ^BufferedReader reader)]
        (cond
          (nil? line)
          {:events events}

          (str/blank? line)
          (recur events)

          :else
          (do
            (events/tap :event line)
            (if (events/closed?)
              (do
                (l/inf :hint "agent service stream aborted, client gone" :events events)
                {:events events :aborted true})
              (recur (inc events)))))))))

(def ^:private schema:get-agent-mcp-server-tools
  [:map {:title "get-agent-mcp-server-tools"}
   [:name [:string {:min 1 :max 32}]]])

(sv/defmethod ::get-agent-mcp-server-tools
  "The tools a connected server offers right now, asked through the agent
  service: the same path, with the same token, a turn would reach it by."
  {::doc/added "2.20"
   ::sm/params schema:get-agent-mcp-server-tools}
  [cfg {:keys [::rpc/profile-id name]}]
  (check-mcp-enabled!)
  (let [row    (d/seek #(= name (:name %)) (get-mcp-server-rows cfg profile-id))
        server (some->> row (service-mcp-server cfg))]
    (when (nil? server)
      (ex/raise :type :not-found
                :code :mcp-server-not-found
                :hint "no such MCP server, or its address can no longer be reached"))
    (let [response (agent-service-req! cfg "/v1/mcp/tools"
                                       {"x-penpot-profile-id" (str profile-id)}
                                       (json/encode {:server (dissoc server :disabledTools)})
                                       {})
          _        (check-agent-service-status! response)
          answer   (json/decode (:body response) :key-fn keyword)]
      (when-let [error (:error answer)]
        (ex/raise :type :validation
                  :code :mcp-server-unreachable
                  :hint (str error)))
      (let [off (set (disabled-tools row))]
        {:tools (mapv (fn [{tool :name :keys [description]}]
                        {:name tool
                         :description (or description "")
                         :is-enabled (not (contains? off tool))})
                      (:tools answer))}))))

;; --- Query: what each model can do
;;
;; The catalog is the provider layer's, read from the agent service: nothing
;; here keeps a list of models. It changes when the service is updated, so
;; one answer is kept for a while.

(def ^:private catalog-ttl (ct/duration "10m"))

(defonce ^:private catalog-cache (atom nil))

(defn- fetch-model-catalog!
  [cfg]
  (let [response (agent-service-req! cfg "/v1/models" {} nil {})]
    (check-agent-service-status! response)
    (json/decode (:body response) :key-fn keyword)))

(defn model-catalog
  "Provider id → the capability records of its models, as the agent service
  describes them; see `agent/src/catalog.js` for the schema."
  [cfg]
  (let [{:keys [at catalog]} @catalog-cache
        now (ct/now)]
    (if (and (some? catalog) (ct/is-before? now (ct/plus at catalog-ttl)))
      catalog
      (let [catalog (fetch-model-catalog! cfg)]
        (reset! catalog-cache {:at now :catalog catalog})
        catalog))))

(def ^:private schema:get-ai-model-catalog
  [:map {:title "get-ai-model-catalog"}])

(sv/defmethod ::get-ai-model-catalog
  "What each model of each provider can do: vision, tool use, reasoning,
  prompt caching, context and output limits, and price. A value that is not
  known is null, never a guess."
  {::doc/added "2.20"
   ::sm/params schema:get-ai-model-catalog}
  [cfg _]
  (update (model-catalog cfg) :providers
          (fn [catalogued]
            ;; of the providers offered here; and of a registered endpoint's
            ;; models, what its admin declared
            (into (into {} (filter (fn [[provider _]] (offered? (name provider)))) catalogued)
                  (map (fn [endpoint]
                         [(keyword (endpoints/provider-id (:id endpoint))) (endpoints/records endpoint)]))
                  (endpoints/get-endpoints cfg)))))

(defn- conversation-of
  "The conversation a turn payload names, or nil."
  [payload]
  (try
    (let [id (:sessionId (json/decode payload :key-fn keyword))]
      (when (and (string? id) (<= 1 (count id) 100))
        id))
    (catch Throwable _ nil)))

(def ^:private schema:agent-service-turn
  [:map {:title "agent-service-turn"}
   [:provider schema:provider-id]
   [:file-id ::sm/uuid]
   ;; JSON built by the panel and forwarded verbatim: session id, model,
   ;; system prompt, user message and the tool specs
   [:payload [:string {:max 4000000}]]])

(sv/defmethod ::agent-service-turn
  {::doc/added "2.20"
   ::sse/stream? true
   ::sm/params schema:agent-service-turn}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id provider file-id payload]}]
  (files/check-read-permissions! pool profile-id file-id)
  (check-provider! cfg provider)
  (let [row      (connected-provider-row cfg profile-id provider)
        helper   (helper-row cfg profile-id)
        mcp-servers (turn-mcp-servers cfg profile-id)
        ;; what this turn changes is recorded under these two
        turn     {:turn-id (uuid/next)
                  :conversation-id (conversation-of payload)}
        response (agent-service-req! cfg "/v1/turns"
                                     (cond-> (merge {"x-penpot-profile-id" (str profile-id)
                                                     "x-penpot-file-id" (str file-id)
                                                     "x-penpot-provider" provider
                                                     "x-penpot-api-key" (:api-key row)}
                                                    ;; the model for the turn's side jobs,
                                                    ;; when the user chose one
                                                    (when (some? helper)
                                                      {"x-penpot-helper-provider" (:provider helper)
                                                       "x-penpot-helper-model" (:helper-model helper)
                                                       "x-penpot-helper-api-key" (:api-key helper)})
                                                    ;; an endpoint is handed over with the turn
                                                    ;; that uses it: the service calls no other
                                                    (endpoints-header cfg [provider (:provider helper)]))
                                       ;; with server tools on, the service
                                       ;; runs these itself, as this profile
                                       ;; on this file and nothing else
                                       ;; the MCP servers this profile connected
                                       (some? mcp-servers)
                                       (assoc "x-penpot-mcp-servers" mcp-servers)

                                       (contains? cf/flags :design-agent-server-tools)
                                       (assoc "x-penpot-turn-token" (agent-tools/turn-token cfg profile-id file-id turn)
                                              "x-penpot-server-tools" (str/join "," (sort agent-tools/server-tools))
                                              "x-penpot-engine-tools" (str/join "," (sort agent-tools/engine-tools))))
                                     payload
                                     {:response-type :input-stream})]
    (check-agent-service-status! response)
    (sse/response (fn []
                    ;; tells the panel which turn this is, so it can ask later
                    ;; what the turn changed
                    (events/tap :event (json/encode {:type "turn-ref" :turnRef (str (:turn-id turn))}))
                    (pump-agent-service-stream! (:body response))))))

;; --- One side job
;;
;; The panel's side work — matching a playbook to a task, summing up a
;; conversation, the watcher's verdicts — is one round with no tools. It runs
;; in the agent service like everything else, on the user's helper model, so
;; it works with whatever provider they connected.

(def ^:private schema:agent-helper-round
  [:map {:title "agent-helper-round"}
   ;; the main model, which does the job when no helper is chosen
   [:provider {:optional true} [:maybe schema:provider-id]]
   [:model {:optional true} [:maybe [:string {:max 200}]]]
   [:system [:string {:max 20000}]]
   [:text [:string {:max 400000}]]])

(sv/defmethod ::agent-helper-round
  "Runs one tool-less round on the profile's helper model — the main model
  given, when it chose none — and answers its words and what they cost."
  {::doc/added "2.20"
   ::sm/params schema:agent-helper-round}
  [cfg {:keys [::rpc/profile-id provider model system text]}]
  (let [helper (or (helper-of cfg profile-id provider model)
                   (ex/raise :type :validation
                             :code :ai-helper-unavailable
                             :hint "no helper model is chosen and no main model was named"))
        response (agent-service-req! cfg "/v1/side"
                                     (merge {"x-penpot-profile-id" (str profile-id)
                                             "x-penpot-provider" (:provider helper)
                                             "x-penpot-api-key" (:api-key helper)}
                                            (endpoints-header cfg [(:provider helper)]))
                                     (json/encode {:model (:model helper) :system system :text text})
                                     {})
        _        (check-agent-service-status! response)
        answer   (json/decode (:body response) :key-fn keyword)]
    {:provider (:provider helper)
     :model (:model helper)
     :text (:text answer)
     :usage (:usage answer)}))

;; --- The provider verification check
;;
;; The same three questions put to any model before it is relied on: does it
;; answer, does it call a tool as asked, does it read an image when it claims
;; to. It runs in the agent service, on the path the agent uses, with the key
;; the profile stored for that provider.

(def ^:private schema:check-ai-model
  [:map {:title "check-ai-model"}
   [:provider schema:provider-id]
   [:model [:string {:max 200}]]])

(sv/defmethod ::check-ai-model
  "Runs the provider verification check on one model of a provider the
  profile connected: text, tool use, and vision where the model claims it."
  {::doc/added "2.20"
   ::sm/params schema:check-ai-model}
  [cfg {:keys [::rpc/profile-id provider model]}]
  (check-provider! cfg provider)
  (let [row      (connected-provider-row cfg profile-id provider)
        response (agent-service-req! cfg "/v1/check"
                                     (merge {"x-penpot-profile-id" (str profile-id)
                                             "x-penpot-provider" provider
                                             "x-penpot-api-key" (:api-key row)}
                                            (endpoints-header cfg [provider]))
                                     (json/encode {:model model})
                                     {})]
    (check-agent-service-status! response)
    (json/decode (:body response) :key-fn keyword)))

(def ^:private schema:agent-service-tool-result
  [:map {:title "agent-service-tool-result"}
   [:turn-id [:string {:max 100}]]
   ;; JSON built by the panel: call id, content, images, error flag
   [:payload [:string {:max 4000000}]]])

(sv/defmethod ::agent-service-tool-result
  {::doc/added "2.20"
   ::sm/params schema:agent-service-tool-result}
  [cfg {:keys [::rpc/profile-id turn-id payload]}]
  (when-not (re-matches #"[A-Za-z0-9_-]+" turn-id)
    (ex/raise :type :validation :code :invalid-turn-id :hint "invalid turn id"))
  (let [response (agent-service-req! cfg (str "/v1/turns/" turn-id "/tool-results")
                                     {"x-penpot-profile-id" (str profile-id)}
                                     payload
                                     {})]
    (check-agent-service-status! response)
    nil))
