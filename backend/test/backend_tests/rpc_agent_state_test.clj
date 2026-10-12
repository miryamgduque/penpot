;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-agent-state-test
  "Pins the per-file agent switch RPCs: an override is private to its owner,
  needs read permission on the file, is deleted when cleared, and the count
  of enabled files leaves out files the user can no longer open."
  (:require
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- set-state!
  [profile-id file-id enabled]
  (th/command! {::th/type :set-agent-file-state
                ::rpc/profile-id profile-id
                :file-id file-id
                :enabled enabled}))

(defn- get-state
  [profile-id file-id]
  (th/command! {::th/type :get-agent-file-state
                ::rpc/profile-id profile-id
                :file-id file-id}))

(defn- enabled-count
  [profile-id]
  (let [out (th/command! {::th/type :get-agent-enabled-file-count
                          ::rpc/profile-id profile-id})]
    (t/is (nil? (:error out)))
    (get-in out [:result :count])))

(defn- row-count
  [profile-id]
  (count (db/query th/*pool* :profile-agent-state {:profile-id profile-id})))

(defn- create-file
  [i profile]
  (th/create-file* i {:profile-id (:id profile)
                      :project-id (:default-project-id profile)}))

(t/deftest file-state-round-trip
  (let [owner (th/create-profile* 1 {:is-active true})
        file  (create-file 1 owner)]

    (t/testing "a file without an override follows the account"
      (let [out (get-state (:id owner) (:id file))]
        (t/is (nil? (:error out)))
        (t/is (= {:file-id (:id file) :enabled nil} (:result out)))))

    (t/testing "an override is stored and read back"
      (t/is (nil? (:error (set-state! (:id owner) (:id file) false))))
      (t/is (false? (get-in (get-state (:id owner) (:id file)) [:result :enabled])))
      (t/is (nil? (:error (set-state! (:id owner) (:id file) true))))
      (t/is (true? (get-in (get-state (:id owner) (:id file)) [:result :enabled])))
      (t/is (= 1 (row-count (:id owner)))))

    (t/testing "a nil value deletes the override"
      (let [out (set-state! (:id owner) (:id file) nil)]
        (t/is (nil? (:error out)))
        (t/is (nil? (get-in out [:result :enabled]))))
      (t/is (nil? (get-in (get-state (:id owner) (:id file)) [:result :enabled])))
      (t/is (zero? (row-count (:id owner)))))

    (t/testing "clearing a file without an override is a no-op"
      (t/is (nil? (:error (set-state! (:id owner) (:id file) nil)))))))

(t/deftest file-state-is-private-to-the-user
  (let [owner  (th/create-profile* 1 {:is-active true})
        member (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})
        _      (th/create-team-role* {:team-id (:id team)
                                      :profile-id (:id member)
                                      :role :editor})
        proj   (th/create-project* 1 {:profile-id (:id owner)
                                      :team-id (:id team)})
        file   (th/create-file* 1 {:profile-id (:id owner)
                                   :project-id (:id proj)})]

    (t/is (nil? (:error (set-state! (:id owner) (:id file) false))))

    (t/testing "a collaborator on the same file keeps following their account"
      (let [out (get-state (:id member) (:id file))]
        (t/is (nil? (:error out)))
        (t/is (nil? (get-in out [:result :enabled])))))

    (t/testing "the owner's override is untouched by the collaborator's"
      (t/is (nil? (:error (set-state! (:id member) (:id file) true))))
      (t/is (false? (get-in (get-state (:id owner) (:id file)) [:result :enabled]))))))

(t/deftest file-state-needs-read-permission-on-the-file
  (let [owner    (th/create-profile* 1 {:is-active true})
        stranger (th/create-profile* 2 {:is-active true})
        file     (create-file 1 owner)]

    (t/testing "a profile cannot set the state of a file it cannot open"
      (let [out (set-state! (:id stranger) (:id file) true)]
        (t/is (= :not-found (th/ex-type (:error out)))))
      (t/is (zero? (row-count (:id stranger)))))

    (t/testing "nor clear it"
      (let [out (set-state! (:id stranger) (:id file) nil)]
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "nor read it"
      (let [out (get-state (:id stranger) (:id file))]
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "a viewer of the file can set their own state"
      (let [viewer (th/create-profile* 3 {:is-active true})
            team   (th/create-team* 1 {:profile-id (:id owner)})
            _      (th/create-team-role* {:team-id (:id team)
                                          :profile-id (:id viewer)
                                          :role :viewer})
            proj   (th/create-project* 1 {:profile-id (:id owner)
                                          :team-id (:id team)})
            file   (th/create-file* 2 {:profile-id (:id owner)
                                       :project-id (:id proj)})]
        (t/is (nil? (:error (set-state! (:id viewer) (:id file) true))))))))

(t/deftest enabled-file-count
  (let [owner (th/create-profile* 1 {:is-active true})
        file1 (create-file 1 owner)
        file2 (create-file 2 owner)
        file3 (create-file 3 owner)]

    (t/testing "no overrides, no files"
      (t/is (= 0 (enabled-count (:id owner)))))

    (t/testing "only files where the agent was turned on count"
      (set-state! (:id owner) (:id file1) true)
      (set-state! (:id owner) (:id file2) true)
      (set-state! (:id owner) (:id file3) false)
      (t/is (= 2 (enabled-count (:id owner)))))

    (t/testing "a file in the trash is not counted"
      (th/mark-file-deleted* {:id (:id file2)})
      (t/is (= 1 (enabled-count (:id owner)))))

    (t/testing "a file deleted for good takes its override with it"
      ;; what the garbage collector does once the trash delay is over
      (db/delete! th/*pool* :file-data {:file-id (:id file1)})
      (db/delete! th/*pool* :file {:id (:id file1)})
      (t/is (= 0 (enabled-count (:id owner))))
      (t/is (nil? (db/get* th/*pool* :profile-agent-state
                           {:profile-id (:id owner) :file-id (:id file1)}))))))

(t/deftest enabled-file-count-leaves-out-files-the-user-lost-access-to
  (let [owner  (th/create-profile* 1 {:is-active true})
        member (th/create-profile* 2 {:is-active true})
        team   (th/create-team* 1 {:profile-id (:id owner)})
        _      (th/create-team-role* {:team-id (:id team)
                                      :profile-id (:id member)
                                      :role :editor})
        proj   (th/create-project* 1 {:profile-id (:id owner)
                                      :team-id (:id team)})
        file   (th/create-file* 1 {:profile-id (:id owner)
                                   :project-id (:id proj)})]

    (set-state! (:id member) (:id file) true)
    (t/is (= 1 (enabled-count (:id member))))

    (db/delete! th/*pool* :team-profile-rel {:team-id (:id team)
                                             :profile-id (:id member)})
    (t/is (= 0 (enabled-count (:id member))))))

(t/deftest enabled-file-count-uses-the-full-read-check-for-non-members
  (let [owner    (th/create-profile* 1 {:is-active true})
        outsider (th/create-profile* 2 {:is-active true})
        file     (create-file 1 owner)]
    ;; a row left from a time the outsider could open the file
    (db/insert! th/*pool* :profile-agent-state
                {:profile-id (:id outsider) :file-id (:id file) :enabled true})

    (t/testing "without any read access the file is not counted"
      (t/is (= 0 (enabled-count (:id outsider)))))

    (t/testing "read access that is not a membership still counts"
      (with-redefs [files/has-read-permissions? (fn [_ profile-id file-id]
                                                  (and (= profile-id (:id outsider))
                                                       (= file-id (:id file))))]
        (t/is (= 1 (enabled-count (:id outsider))))))))

(t/deftest disable-agent-in-enabled-files
  (let [owner (th/create-profile* 1 {:is-active true})
        other (th/create-profile* 2 {:is-active true})
        file1 (create-file 1 owner)
        file2 (create-file 2 owner)
        file3 (create-file 3 other)]

    (set-state! (:id owner) (:id file1) true)
    (set-state! (:id owner) (:id file2) false)
    (set-state! (:id other) (:id file3) true)

    (let [out (th/command! {::th/type :disable-agent-in-enabled-files
                            ::rpc/profile-id (:id owner)})]
      (t/is (nil? (:error out)))
      (t/is (= {:count 1} (:result out))))

    (t/testing "the enabled override is gone and the file follows the account"
      (t/is (nil? (get-in (get-state (:id owner) (:id file1)) [:result :enabled])))
      (t/is (= 0 (enabled-count (:id owner)))))

    (t/testing "a disabled override is kept"
      (t/is (false? (get-in (get-state (:id owner) (:id file2)) [:result :enabled]))))

    (t/testing "other users' overrides are untouched"
      (t/is (= 1 (enabled-count (:id other)))))))
