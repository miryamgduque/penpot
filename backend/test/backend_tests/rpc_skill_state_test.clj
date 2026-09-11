;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-skill-state-test
  (:require
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest skill-state-account-and-file
  (let [prof       (th/create-profile* 1 {:is-active true})
        profile-id (:id prof)
        proj-id    (:default-project-id prof)
        file       (th/create-file* 1 {:profile-id profile-id :project-id proj-id})
        file-id    (:id file)
        get!       (fn [& {:as params}]
                     (:result (th/command! (merge {::th/type :get-skill-states
                                                   ::rpc/profile-id profile-id}
                                                  params))))
        set-enabled!       (fn [& {:as params}]
                     (th/command! (merge {::th/type :set-skill-enabled
                                          ::rpc/profile-id profile-id}
                                         params)))]

    (t/testing "no state initially"
      (t/is (empty? (get!))))

    (t/testing "set an account-level default (file-id nil)"
      (let [out (set-enabled! :skill "penpot-rename-layers" :enabled false)]
        (t/is (nil? (:error out)))
        (t/is (= false (get-in out [:result :enabled])))))

    (t/testing "get returns the account row"
      (let [rows (get!)]
        (t/is (= 1 (count rows)))
        (t/is (= "penpot-rename-layers" (:skill (first rows))))
        (t/is (nil? (:file-id (first rows))))
        (t/is (= false (:enabled (first rows))))))

    (t/testing "upsert overwrites the account row (single slot for NULL file-id)"
      (set-enabled! :skill "penpot-rename-layers" :enabled true)
      (let [rows (get!)]
        (t/is (= 1 (count rows)))
        (t/is (= true (:enabled (first rows))))))

    (t/testing "a per-file override coexists with the account row"
      (set-enabled! :skill "penpot-rename-layers" :enabled false :file-id file-id)
      (let [rows (get! :file-id file-id)]
        (t/is (= 2 (count rows)))
        (t/is (= #{[nil true] [file-id false]}
                 (set (map (juxt :file-id :enabled) rows))))))

    (t/testing "get without a file-id sees only the account row"
      (let [rows (get!)]
        (t/is (= 1 (count rows)))
        (t/is (nil? (:file-id (first rows))))))))
