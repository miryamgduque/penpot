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
          (t/is (= {:name "notes" :url "https://mcp.example.com/mcp" :has-token true :is-enabled true :disabled-tools []}
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

(t/deftest a-profile-sees-a-servers-tools-and-switches-them-off
  (let [asked (atom [])]
    (with-redefs [cf/flags (conj cf/flags :design-agent-mcp)
                  ssrf/validate-uri identity
                  ;; the agent service, which is who talks to the server
                  ai-providers/agent-service-req!
                  (fn [_ path headers body _]
                    (swap! asked conj {:path path :headers headers :body (json/decode body :key-fn keyword)})
                    {:status 200
                     :body (json/encode {:tools [{:name "echo" :description "Says it back"}
                                                 {:name "delete_everything"}]})})]
      (let [profile (th/create-profile* 1 {:is-active true})
            other   (th/create-profile* 2 {:is-active true})]
        (mcp! profile :set-agent-mcp-server :name "notes" :url "https://mcp.example.com/mcp" :token "secret-token")

        (t/testing "the tools are asked for with the server's token, which stays on the server"
          (let [out (mcp! profile :get-agent-mcp-server-tools :name "notes")]
            (t/is (nil? (:error out)))
            (t/is (= [{:name "echo" :description "Says it back" :is-enabled true}
                      {:name "delete_everything" :description "" :is-enabled true}]
                     (-> out :result :tools)))
            (t/is (= [{:path "/v1/mcp/tools"
                       :headers {"x-penpot-profile-id" (str (:id profile))}
                       :body {:server {:name "notes"
                                       :url "https://mcp.example.com/mcp"
                                       :headers {:Authorization "Bearer secret-token"}}}}]
                     @asked))
            (t/is (not (str/includes? (pr-str (:result out)) "secret-token")))))

        (t/testing "a tool switched off is said so, and the turn is told to leave it out"
          (let [out (mcp! profile :set-agent-mcp-server :name "notes" :url "https://mcp.example.com/mcp"
                          :disabled-tools ["delete_everything"])]
            (t/is (= ["delete_everything"] (-> out :result :disabled-tools))))
          (t/is (= [true false]
                   (->> (mcp! profile :get-agent-mcp-server-tools :name "notes") :result :tools (mapv :is-enabled))))
          (t/is (= ["delete_everything"] (-> (turn-servers profile) first :disabledTools))))

        (t/testing "it stays off through other changes, until the server is another one"
          (mcp! profile :set-agent-mcp-server :name "notes" :url "https://mcp.example.com/mcp" :is-enabled true)
          (t/is (= ["delete_everything"] (-> (turn-servers profile) first :disabledTools)))
          (mcp! profile :set-agent-mcp-server :name "notes" :url "https://mcp.example.com/mcp" :disabled-tools [])
          (t/is (nil? (-> (turn-servers profile) first :disabledTools)))
          (mcp! profile :set-agent-mcp-server :name "notes" :url "https://mcp.example.com/mcp" :disabled-tools ["echo"])
          (mcp! profile :set-agent-mcp-server :name "notes" :url "https://other.example.com/mcp")
          (t/is (nil? (-> (turn-servers profile) first :disabledTools))))

        (t/testing "someone else's server is no server"
          (t/is (= :mcp-server-not-found
                   (-> (mcp! other :get-agent-mcp-server-tools :name "notes") :error ex-data :code))))))))

;; --- The helper model and what each model can do

(defn- connect!
  "Stores a key for `provider` and enables `models`, with no call to the provider."
  [profile provider models]
  (th/command! {::th/type :set-ai-provider-key ::rpc/profile-id (:id profile)
                :provider provider :api-key (str "key-of-" provider)})
  (db/update! th/*pool* :profile-ai-provider
              {:enabled-models (db/json models)}
              {:profile-id (:id profile) :provider provider}))

(t/deftest side-jobs-run-on-the-helper-and-on-the-main-model-when-there-is-none
  (let [asked (atom [])]
    (with-redefs [ai-providers/agent-service-req!
                  (fn [_ path headers body _]
                    (swap! asked conj {:path path :headers headers :body (some-> body (json/decode :key-fn keyword))})
                    {:status 200 :body (json/encode {:text "penpot-build-screen" :usage {:input 12 :output 3 :requests 1}})})]
      (let [profile (th/create-profile* 1 {:is-active true})
            round!  (fn [& {:as params}]
                      (th/command! (merge {::th/type :agent-helper-round ::rpc/profile-id (:id profile)
                                           :system "You route tasks." :text "Task: a landing page"}
                                          params)))
            helper! (fn [& {:as params}]
                      (th/command! (merge {::th/type :set-ai-helper-model ::rpc/profile-id (:id profile)} params)))
            status  (fn [] (->> (:result (th/command! {::th/type :get-ai-providers ::rpc/profile-id (:id profile)}))
                                (map (juxt :provider :helper-model))
                                (into {})))]
        ;; a user with no Anthropic key at all
        (connect! profile "moonshot" ["kimi-k3" "kimi-k2.6"])
        (connect! profile "zhipu" ["glm-5.2"])

        (t/testing "with no helper chosen, the side job runs on the main model, whoever its provider is"
          (let [out (round! :provider "moonshot" :model "kimi-k3")]
            (t/is (nil? (:error out)))
            (t/is (= {:provider "moonshot" :model "kimi-k3" :text "penpot-build-screen"}
                     (select-keys (:result out) [:provider :model :text])))
            (t/is (= {:path "/v1/side"
                      :headers {"x-penpot-profile-id" (str (:id profile))
                                "x-penpot-provider" "moonshot"
                                "x-penpot-api-key" "key-of-moonshot"}
                      :body {:model "kimi-k3" :system "You route tasks." :text "Task: a landing page"}}
                     (last @asked)))))

        (t/testing "with neither a helper nor a main model named, it says so"
          (t/is (th/ex-of-code? (:error (round!)) :ai-helper-unavailable)))

        (t/testing "a helper has to be a model the user enabled"
          (t/is (th/ex-of-code? (:error (helper! :provider "zhipu" :model "glm-4.7")) :ai-model-not-enabled))
          (t/is (th/ex-of-code? (:error (helper! :provider "openai" :model "gpt-5")) :ai-provider-not-connected))
          (t/is (= {"moonshot" nil "zhipu" nil} (status))))

        (t/testing "the helper chosen does the side jobs, under its own provider's key"
          (t/is (nil? (:error (helper! :provider "zhipu" :model "glm-5.2"))))
          (t/is (= {"moonshot" nil "zhipu" "glm-5.2"} (status)))
          (let [out (round! :provider "moonshot" :model "kimi-k3")]
            (t/is (= {:provider "zhipu" :model "glm-5.2"} (select-keys (:result out) [:provider :model])))
            (t/is (= "key-of-zhipu" (get-in (last @asked) [:headers "x-penpot-api-key"])))))

        (t/testing "one helper at a time: choosing another moves it"
          (helper! :provider "moonshot" :model "kimi-k2.6")
          (t/is (= {"moonshot" "kimi-k2.6" "zhipu" nil} (status))))

        (t/testing "a helper that is no longer enabled is no helper"
          (db/update! th/*pool* :profile-ai-provider {:enabled-models (db/json ["kimi-k3"])}
                      {:profile-id (:id profile) :provider "moonshot"})
          (t/is (= {"moonshot" nil "zhipu" nil} (status)))
          (t/is (= "kimi-k3" (-> (round! :provider "moonshot" :model "kimi-k3") :result :model))))

        (t/testing "and none can be chosen again"
          (helper! :provider "zhipu" :model "glm-5.2")
          (helper!)
          (t/is (= {"moonshot" nil "zhipu" nil} (status))))))))

(t/deftest the-catalog-of-what-models-can-do-is-the-services
  (let [asked (atom 0)]
    (with-redefs [ai-providers/agent-service-req!
                  (fn [_ path _ body _]
                    (swap! asked inc)
                    (t/is (= "/v1/models" path))
                    (t/is (nil? body))
                    {:status 200
                     :body (json/encode {:schema 1
                                         :providers {:zhipu [{:provider "zhipu" :id "glm-5.2" :name "GLM-5.2"
                                                              :vision false :tools true :reasoning true
                                                              :promptCaching nil :contextLimit 1000000
                                                              :outputLimit 128000 :price nil}]}})})]
      (reset! @#'ai-providers/catalog-cache nil)
      (let [profile (th/create-profile* 1 {:is-active true})
            out     (th/command! {::th/type :get-ai-model-catalog ::rpc/profile-id (:id profile)})
            model   (-> out :result :providers :zhipu first)]
        (t/is (nil? (:error out)))
        (t/is (= 1 (-> out :result :schema)))
        (t/is (false? (:vision model)))
        ;; what the source does not say stays unknown all the way here
        (t/is (contains? model :promptCaching))
        (t/is (nil? (:promptCaching model)))
        (t/is (nil? (:price model)))
        ;; one answer is kept for a while: the service is not asked again
        (th/command! {::th/type :get-ai-model-catalog ::rpc/profile-id (:id profile)})
        (t/is (= 1 @asked)))
      (reset! @#'ai-providers/catalog-cache nil))))

;; --- Endpoints an instance admin registered

(defn- endpoint!
  [profile type & {:as params}]
  (th/command! (merge {::th/type type ::rpc/profile-id (:id profile)} params)))

(def ^:private lab
  {:name "Lab"
   :base-uri "http://models.internal:11434/v1/"
   :models [{:id "qwen-local" :name "Qwen (local)" :tools true :vision false :context-limit 32000}
            {:id "tiny-chat" :tools false}]})

(t/deftest only-an-instance-admin-registers-an-endpoint-and-everyone-may-use-it
  (let [asked (atom [])
        admin (th/create-profile* 1 {:is-active true})
        user  (th/create-profile* 2 {:is-active true})]
    (with-redefs [cf/config (assoc cf/config :admins #{(:email admin)})
                  ai-providers/agent-service-req!
                  (fn [_ path headers body _]
                    (swap! asked conj {:path path :headers headers})
                    {:status 200 :body (json/encode (if (= "/v1/models" path)
                                                      {:schema 1 :providers {}}
                                                      {:text "ok" :usage {:requests 1}}))})]
      (reset! @#'ai-providers/catalog-cache nil)

      (t/testing "a user cannot add an address"
        (let [out (apply endpoint! user :set-ai-endpoint (mapcat identity lab))]
          (t/is (th/ex-of-code? (:error out) :instance-admin-required)))
        (t/is (= [] (-> (endpoint! user :get-ai-endpoints) :result :endpoints))))

      (t/testing "what is not an address is refused, whoever asks"
        (doseq [address ["file:///etc/passwd" "models.internal:11434" "ftp://models.internal/v1"]]
          (let [out (apply endpoint! admin :set-ai-endpoint (mapcat identity (assoc lab :base-uri address)))]
            (t/is (th/ex-of-code? (:error out) :ai-endpoint-address-invalid) address))))

      (let [made     (apply endpoint! admin :set-ai-endpoint (mapcat identity lab))
            id       (-> made :result :id)
            provider (str "endpoint:" id)]
        (t/testing "an admin registers one, with its models and what each can do"
          (t/is (nil? (:error made)))
          (t/is (= "http://models.internal:11434/v1" (-> made :result :base-uri)))
          (t/is (= [{:id "qwen-local" :name "Qwen (local)" :tools true :vision false :contextLimit 32000}
                    ;; what the admin left out is unknown, not assumed
                    {:id "tiny-chat" :name "tiny-chat" :tools false :vision nil :contextLimit nil}]
                   (mapv #(select-keys % [:id :name :tools :vision :contextLimit]) (-> made :result :models)))))

        (t/testing "a user sees it by name and picks it, and is not shown its address"
          (let [out (:result (endpoint! user :get-ai-endpoints))]
            (t/is (false? (:can-manage out)))
            (t/is (= [{:provider provider :name "Lab" :requires-key false}]
                     (mapv #(select-keys % [:provider :name :requires-key]) (:endpoints out))))
            (t/is (not-any? #(contains? % :base-uri) (:endpoints out))))
          (t/is (true? (-> (endpoint! admin :get-ai-endpoints) :result :can-manage)))
          (let [out (endpoint! user :set-ai-provider-key :provider provider :api-key "not-needed")]
            (t/is (nil? (:error out)))
            ;; every model it serves is on from the start
            (t/is (= ["qwen-local" "tiny-chat"] (-> out :result :enabled-models)))))

        (t/testing "its models are in the catalog, as the admin declared them"
          (let [records (-> (endpoint! user :get-ai-model-catalog) :result :providers (get (keyword provider)))]
            (t/is (= ["qwen-local" "tiny-chat"] (mapv :id records)))
            (t/is (= [true false] (mapv :tools records)))))

        (t/testing "a job on it is handed to the service together with the endpoint"
          (let [out    (endpoint! user :agent-helper-round :provider provider :model "qwen-local"
                                  :system "You route tasks." :text "Task")
                sent   (last @asked)
                handed (-> (get-in sent [:headers "x-penpot-endpoints"])
                           (#(String. (.decode (java.util.Base64/getDecoder) ^String %) "UTF-8"))
                           (json/decode :key-fn keyword))]
            (t/is (nil? (:error out)))
            (t/is (= provider (get-in sent [:headers "x-penpot-provider"])))
            (t/is (= [{:provider provider :name "Lab" :baseUrl "http://models.internal:11434/v1"}]
                     (mapv #(select-keys % [:provider :name :baseUrl]) handed)))
            (t/is (= ["qwen-local" "tiny-chat"] (mapv :id (:models (first handed)))))))

        (t/testing "an endpoint nobody registered is not called"
          (let [unknown (str "endpoint:" (random-uuid))
                before  (count @asked)]
            (t/is (th/ex-of-code? (:error (endpoint! user :set-ai-provider-key :provider unknown :api-key "not-needed"))
                                  :ai-provider-not-registered))
            (t/is (some? (:error (endpoint! user :agent-helper-round :provider unknown :model "m" :system "s" :text "t"))))
            (t/is (= before (count @asked)))
            ;; and an id of no known form is not a provider at all
            (t/is (some? (:error (endpoint! user :set-ai-provider-key :provider "http://evil.example/v1" :api-key "not-needed"))))))

        (t/testing "removed by an admin, it is gone for everyone and called no more"
          (t/is (th/ex-of-code? (:error (endpoint! user :delete-ai-endpoint :id id)) :instance-admin-required))
          (t/is (nil? (:error (endpoint! admin :delete-ai-endpoint :id id))))
          (t/is (= [] (-> (endpoint! user :get-ai-endpoints) :result :endpoints)))
          (t/is (= [] (:result (endpoint! user :get-ai-providers))))
          (let [before (count @asked)]
            (t/is (some? (:error (endpoint! user :agent-helper-round :provider provider :model "qwen-local" :system "s" :text "t"))))
            (t/is (= before (count @asked))))))
      (reset! @#'ai-providers/catalog-cache nil))))
