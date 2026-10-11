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
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.ai-providers :as ai-providers]
   [app.setup :as-alias setup]
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
