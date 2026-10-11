;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-ai-providers-test
  "Pins how AI provider keys are stored: encrypted at rest, never returned to
  the client, with plain-text rows from before the encryption upgraded on
  first read."
  (:require
   [app.common.json :as json]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.ai-providers :as ai-providers]
   [app.setup :as-alias setup]
   [app.util.ssrf :as ssrf]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private api-key "sk-test-0123456789abcdef-WXYZ")

(defn- stored-key
  [profile-id]
  (:api-key (db/get* th/*pool* :profile-ai-provider {:profile-id profile-id
                                                     :provider "anthropic"})))

(defn- system-cfg
  []
  {::setup/props (::setup/props th/*system*)})

(t/deftest provider-key-is-encrypted-at-rest
  (let [profile (th/create-profile* 1 {:is-active true})
        out     (th/command! {::th/type :set-ai-provider-key
                              ::rpc/profile-id (:id profile)
                              :provider "anthropic"
                              :api-key api-key})]

    (t/testing "the command answers with a hint, never the key"
      (t/is (nil? (:error out)))
      (t/is (= "WXYZ" (get-in out [:result :key-hint])))
      (t/is (not (contains? (:result out) :api-key))))

    (t/testing "the stored value is not the key and does not contain it"
      (let [stored (stored-key (:id profile))]
        (t/is (str/starts-with? stored "enc:v1:"))
        (t/is (not (str/includes? stored api-key)))))

    (t/testing "the stored value decrypts back to the key"
      (t/is (= api-key (#'ai-providers/decrypt-key (system-cfg) "anthropic"
                                                   (stored-key (:id profile))))))

    (t/testing "the list exposes only the hint"
      (let [out (th/command! {::th/type :get-ai-providers
                              ::rpc/profile-id (:id profile)})]
        (t/is (nil? (:error out)))
        (t/is (= ["WXYZ"] (mapv :key-hint (:result out))))
        (t/is (not-any? #(contains? % :api-key) (:result out)))))))

(t/deftest legacy-plain-key-is-upgraded-on-read
  (let [profile (th/create-profile* 1 {:is-active true})]
    (db/insert! th/*pool* :profile-ai-provider
                {:profile-id (:id profile)
                 :provider "anthropic"
                 :api-key api-key
                 :enabled-models (db/json [])})

    (t/testing "a plain-text row still lists, with its hint"
      (let [out (th/command! {::th/type :get-ai-providers
                              ::rpc/profile-id (:id profile)})]
        (t/is (= ["WXYZ"] (mapv :key-hint (:result out))))))

    (t/testing "using the row encrypts it in place"
      (let [out (th/command! {::th/type :set-ai-provider-models
                              ::rpc/profile-id (:id profile)
                              :provider "anthropic"
                              :models ["claude-sonnet-5"]})]
        (t/is (nil? (:error out)))
        (t/is (str/starts-with? (stored-key (:id profile)) "enc:v1:"))
        (t/is (= api-key (#'ai-providers/decrypt-key (system-cfg) "anthropic"
                                                     (stored-key (:id profile)))))))))

(t/deftest unreadable-key-asks-to-reconnect
  (let [profile (th/create-profile* 1 {:is-active true})]
    (db/insert! th/*pool* :profile-ai-provider
                {:profile-id (:id profile)
                 :provider "anthropic"
                 :api-key "enc:v1:not-a-valid-token"
                 :enabled-models (db/json [])})

    (t/testing "the provider still lists as connected, with no hint"
      (let [out (th/command! {::th/type :get-ai-providers
                              ::rpc/profile-id (:id profile)})]
        (t/is (nil? (:error out)))
        (t/is (= [""] (mapv :key-hint (:result out))))))

    (t/testing "using it fails with a clear, stable code"
      (let [out (th/command! {::th/type :set-ai-provider-models
                              ::rpc/profile-id (:id profile)
                              :provider "anthropic"
                              :models []})]
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :ai-provider-key-unreadable (th/ex-code (:error out))))))))

(t/deftest a-turn-says-so-when-the-agent-service-is-not-configured
  ;; the panel shows the hint as it is: it has to name the cause, and must not
  ;; be reported as a service that is down
  (binding [cf/config (dissoc cf/config :agent-service-uri)]
    (let [cause (try
                  (#'ai-providers/agent-service-req! (system-cfg) "/v1/turns" {} "{}" {})
                  (catch clojure.lang.ExceptionInfo cause cause))]
      (t/is (= :agent-service-not-configured (:code (ex-data cause))))
      (t/is (re-find #"not configured on this Penpot instance" (:hint (ex-data cause)))))))

;; --- External MCP servers

(defn- mcp!
  [profile type & {:as params}]
  (th/command! (merge {::th/type type ::rpc/profile-id (:id profile)} params)))

(defn- turn-servers
  "What the agent service would be handed for this profile's next turn."
  [profile]
  (some-> (#'ai-providers/turn-mcp-servers (assoc (system-cfg) ::db/pool th/*pool*) (:id profile))
          (->> (.decode (java.util.Base64/getDecoder)))
          (String. "UTF-8")
          (json/decode :key-fn keyword)))

(t/deftest mcp-servers-are-off-unless-the-instance-opts-in
  (let [profile (th/create-profile* 1 {:is-active true})
        out     (mcp! profile :set-agent-mcp-server :name "notes" :url "https://mcp.example.com/mcp")]
    (t/is (= :mcp-servers-not-enabled (-> out :error ex-data :code)))
    (t/is (nil? (turn-servers profile)))))

(t/deftest a-profile-connects-mcp-servers-and-their-tokens-stay-on-the-server
  (with-redefs [cf/flags (conj cf/flags :design-agent-mcp)
                ;; the address check is the backend's own, pinned elsewhere;
                ;; here a public host is one that is not the literal intranet
                ssrf/validate-uri (fn [uri]
                                    (when (re-find #"intranet" (str uri))
                                      (throw (ex-info "blocked" {:type :validation :code :ssrf-blocked-target})))
                                    uri)]
    (let [profile (th/create-profile* 1 {:is-active true})
          other   (th/create-profile* 2 {:is-active true})]

      (t/testing "what is refused"
        (t/is (= :mcp-server-name-invalid
                 (-> (mcp! profile :set-agent-mcp-server :name "bad name!" :url "https://mcp.example.com/") :error ex-data :code)))
        (t/is (= :mcp-server-url-not-https
                 (-> (mcp! profile :set-agent-mcp-server :name "plain" :url "http://mcp.example.com/") :error ex-data :code)))
        (t/is (= :ssrf-blocked-target
                 (-> (mcp! profile :set-agent-mcp-server :name "inside" :url "https://intranet.example.com/") :error ex-data :code))))

      (t/testing "a server with a token: stored encrypted, never returned"
        (let [out (mcp! profile :set-agent-mcp-server :name "notes" :url "https://mcp.example.com/mcp" :token "secret-token")
              row (db/get* th/*pool* :profile-mcp-server {:profile-id (:id profile) :name "notes"})]
          (t/is (nil? (:error out)))
          (t/is (= {:name "notes" :url "https://mcp.example.com/mcp" :has-token true :is-enabled true}
                   (dissoc (:result out) :id)))
          (t/is (str/starts-with? (:token row) "enc:v1:"))
          (t/is (not (str/includes? (:token row) "secret-token")))
          (t/is (not (str/includes? (pr-str (:result (mcp! profile :get-agent-mcp-servers))) "secret-token")))))

      (t/testing "the agent service is handed the enabled ones, with the token as a bearer"
        (mcp! profile :set-agent-mcp-server :name "open" :url "https://open.example.com/mcp")
        (t/is (= [{:name "notes" :url "https://mcp.example.com/mcp" :headers {:Authorization "Bearer secret-token"}}
                  {:name "open" :url "https://open.example.com/mcp"}]
                 (turn-servers profile)))
        ;; another profile's turn carries none of them
        (t/is (nil? (turn-servers other))))

      (t/testing "changing one keeps its token unless a new one is given"
        (mcp! profile :set-agent-mcp-server :name "notes" :url "https://mcp.example.com/v2" :is-enabled false)
        (t/is (= ["open"] (mapv :name (turn-servers profile))))
        (mcp! profile :set-agent-mcp-server :name "notes" :url "https://mcp.example.com/v2" :is-enabled true)
        (t/is (= "Bearer secret-token" (-> (turn-servers profile) first :headers :Authorization)))
        (mcp! profile :set-agent-mcp-server :name "notes" :url "https://mcp.example.com/v2" :token "")
        (t/is (nil? (-> (turn-servers profile) first :headers))))

      (t/testing "a server whose address stopped being public is left out of the turn"
        (db/update! th/*pool* :profile-mcp-server {:url "https://intranet.example.com/mcp"}
                    {:profile-id (:id profile) :name "open"})
        (t/is (= ["notes"] (mapv :name (turn-servers profile)))))

      (t/testing "removing one, and the limit"
        (mcp! profile :delete-agent-mcp-server :name "open")
        (t/is (= ["notes"] (mapv :name (:result (mcp! profile :get-agent-mcp-servers)))))
        (doseq [i (range 7)]
          (mcp! profile :set-agent-mcp-server :name (str "s" i) :url "https://mcp.example.com/"))
        (t/is (= :mcp-servers-limit-reached
                 (-> (mcp! profile :set-agent-mcp-server :name "one-too-many" :url "https://mcp.example.com/")
                     :error ex-data :code)))))))
