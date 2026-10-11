;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-agent-chats-test
  "Pins the agent chat RPC surface: a conversation is private to its owner,
  can only be attached to a file the owner can read, and is bounded in size
  and in number per file."
  (:require
   [app.rpc :as-alias rpc]
   [app.rpc.commands.agent-chats :as agent-chats]
   [backend-tests.helpers :as th]
   [clojure.test :as t])
  (:import
   java.util.UUID))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private data
  {:messages [{:role :user :text "hello"}]
   :history [{:role "user" :content "hello"}]
   :usage {:input 10 :output 2}})

(defn- upsert!
  [profile-id file-id & {:as params}]
  (th/command! (merge {::th/type :upsert-agent-chat
                       ::rpc/profile-id profile-id
                       :id (UUID/randomUUID)
                       :file-id file-id
                       :title "hello"
                       :data data}
                      params)))

(t/deftest agent-chat-save-list-and-read
  (let [owner   (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id (:default-project-id owner)})
        chat-id (UUID/randomUUID)]

    (t/testing "the owner saves a conversation on a file they can read"
      (let [out (upsert! (:id owner) (:id file) :id chat-id)]
        (t/is (nil? (:error out)))
        (t/is (= chat-id (get-in out [:result :id])))))

    (t/testing "it is listed for the file, without the payload"
      (let [out (th/command! {::th/type :get-agent-chats
                              ::rpc/profile-id (:id owner)
                              :file-id (:id file)})]
        (t/is (nil? (:error out)))
        (t/is (= [chat-id] (mapv :id (:result out))))
        (t/is (not (contains? (first (:result out)) :data)))))

    (t/testing "the payload round-trips through transit"
      (let [out (th/command! {::th/type :get-agent-chat
                              ::rpc/profile-id (:id owner)
                              :id chat-id})]
        (t/is (nil? (:error out)))
        (t/is (= data (get-in out [:result :data])))))))

(t/deftest agent-chat-needs-read-permission-on-the-file
  (let [owner    (th/create-profile* 1 {:is-active true})
        stranger (th/create-profile* 2 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id (:default-project-id owner)})]

    (t/testing "a profile cannot attach a conversation to a file it cannot open"
      (let [out (upsert! (:id stranger) (:id file))]
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "nor list conversations for it"
      (let [out (th/command! {::th/type :get-agent-chats
                              ::rpc/profile-id (:id stranger)
                              :file-id (:id file)})]
        (t/is (= :not-found (th/ex-type (:error out))))))))

(t/deftest agent-chat-payload-is-bounded
  (let [owner (th/create-profile* 1 {:is-active true})
        file  (th/create-file* 1 {:profile-id (:id owner)
                                  :project-id (:default-project-id owner)})]
    (with-redefs [agent-chats/max-data-size 20]
      (let [out (upsert! (:id owner) (:id file))]
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :agent-chat-too-large (th/ex-code (:error out))))))))

(t/deftest agent-chat-count-per-file-is-bounded
  (let [owner   (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id (:default-project-id owner)})
        chat-id (UUID/randomUUID)]
    (with-redefs [agent-chats/max-chats-per-file 1]
      (t/testing "the first conversation is saved"
        (t/is (nil? (:error (upsert! (:id owner) (:id file) :id chat-id)))))

      (t/testing "a second one is refused"
        (let [out (upsert! (:id owner) (:id file))]
          (t/is (= :validation (th/ex-type (:error out))))
          (t/is (= :agent-chat-limit-reached (th/ex-code (:error out))))))

      (t/testing "saving the existing one again is still allowed"
        (t/is (nil? (:error (upsert! (:id owner) (:id file) :id chat-id))))))))
