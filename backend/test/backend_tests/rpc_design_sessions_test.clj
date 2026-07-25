;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-design-sessions-test
  "Pins the design-session RPC surface (phase 06 of the design-session-recording
  plan), and above all its authorization.

  Santi's direction (2026-07-25) is that a session's audience is a bot —
  \"something admins can export to feed agent sessions\" — which splits the
  surface three ways, and each split is asserted here:

  - WRITE belongs to the recorder and needs EDIT permission on the file
  - READ is for team admins, plus the recorder's own sessions
  - EXPORT (the bot-facing bulk path) is admins ONLY

  A session records identifiable people's activity, and the earlier branch review
  already found an authz hole of exactly this shape (app-scope design skills
  writable by any authenticated user), so the negative cases matter more than the
  happy path."
  (:require
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t])
  (:import
   java.util.UUID))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- reset-sessions!
  "The suite's `database-reset` only truncates the main test database; the
  sessions database is separate (that is the whole point of phase 05), so it
  needs clearing explicitly."
  []
  (let [pool (:app.main/sessions-pool th/*system*)]
    (db/exec-one! pool ["DELETE FROM design_session"])))

(def ^:private events
  [{:kind :create :label "created 1 shape" :who :user :at 1000}
   {:kind :style :label "restyled 1 shape" :who :agent
    :model "claude-opus-4-8" :at 2000}])

(t/deftest design-session-write-read-and-finish
  (reset-sessions!)
  (let [owner      (th/create-profile* 1 {:is-active true})
        profile-id (:id owner)
        file       (th/create-file* 1 {:profile-id profile-id
                                       :project-id (:default-project-id owner)})
        file-id    (:id file)
        rec-id     (UUID/randomUUID)
        sess-id    (UUID/randomUUID)
        upsert!    (fn [& {:as params}]
                     (th/command! (merge {::th/type :upsert-design-session
                                          ::rpc/profile-id profile-id
                                          :id rec-id
                                          :file-id file-id
                                          :session-id sess-id
                                          :events events}
                                         params)))]

    (t/testing "a recorder creates a session"
      (let [out (upsert!)]
        (t/is (nil? (:error out)))
        (t/is (= rec-id (get-in out [:result :id])))))

    (t/testing "the timeline round-trips through transit"
      (let [out (th/command! {::th/type :get-design-session
                              ::rpc/profile-id profile-id
                              :id rec-id})]
        (t/is (nil? (:error out)))
        (t/is (= events (get-in out [:result :events])))
        (t/is (nil? (get-in out [:result :stop-reason]))
              "still recording")))

    (t/testing "re-sending the whole timeline is idempotent — no duplication,
                which is what makes a retried or replayed flush safe"
      (t/is (nil? (:error (upsert! :events (conj events {:kind :move
                                                         :label "moved 1 shape"
                                                         :who :user :at 3000})))))
      (let [out (th/command! {::th/type :get-design-session
                              ::rpc/profile-id profile-id :id rec-id})]
        (t/is (= 3 (count (get-in out [:result :events]))))))

    (t/testing "the list reports metadata, never the timeline"
      (let [rows (:result (th/command! {::th/type :get-design-sessions
                                        ::rpc/profile-id profile-id
                                        :file-id file-id}))]
        (t/is (= 1 (count rows)))
        (t/is (= 3 (:event-count (first rows))))
        (t/is (not (contains? (first rows) :events)))))

    (t/testing "a stop-reason finishes the session"
      (t/is (nil? (:error (upsert! :stop-reason "manual"))))
      (let [row (:result (th/command! {::th/type :get-design-session
                                       ::rpc/profile-id profile-id :id rec-id}))]
        (t/is (= "manual" (:stop-reason row)))
        (t/is (some? (:stopped-at row)))))

    (t/testing "a finished session is immutable — a late flush must not reopen
                and rewrite a closed record"
      (let [out (upsert! :events [])]
        (t/is (= :not-found (th/ex-type (:error out))))))))

(t/deftest design-session-discloses-a-partial-raw-record
  (reset-sessions!)
  (let [owner      (th/create-profile* 1 {:is-active true})
        profile-id (:id owner)
        file       (th/create-file* 1 {:profile-id profile-id
                                       :project-id (:default-project-id owner)})]
    (t/testing "raw-dropped rides the row, so a session whose raw buffer rolled
                says so instead of implying it is complete"
      (th/command! {::th/type :upsert-design-session
                    ::rpc/profile-id profile-id
                    :id (UUID/randomUUID)
                    :file-id (:id file)
                    :session-id (UUID/randomUUID)
                    :events events
                    :raw-dropped 42})
      (let [rows (:result (th/command! {::th/type :get-design-sessions
                                        ::rpc/profile-id profile-id
                                        :file-id (:id file)}))]
        (t/is (= 42 (:raw-dropped (first rows))))))))

;; --- authorization

(t/deftest design-session-write-requires-file-edit-permission
  (reset-sessions!)
  (let [owner     (th/create-profile* 1 {:is-active true})
        outsider  (th/create-profile* 2 {:is-active true})
        file      (th/create-file* 1 {:profile-id (:id owner)
                                      :project-id (:default-project-id owner)})]
    (t/testing "a profile with no access to the file cannot record it"
      (let [out (th/command! {::th/type :upsert-design-session
                              ::rpc/profile-id (:id outsider)
                              :id (UUID/randomUUID)
                              :file-id (:id file)
                              :session-id (UUID/randomUUID)
                              :events events})]
        (t/is (some? (:error out)))))))

(t/deftest design-session-read-is-not-probeable-by-outsiders
  (reset-sessions!)
  (let [owner      (th/create-profile* 1 {:is-active true})
        outsider   (th/create-profile* 2 {:is-active true})
        file       (th/create-file* 1 {:profile-id (:id owner)
                                       :project-id (:default-project-id owner)})
        rec-id     (UUID/randomUUID)]
    (th/command! {::th/type :upsert-design-session
                  ::rpc/profile-id (:id owner)
                  :id rec-id :file-id (:id file)
                  :session-id (UUID/randomUUID) :events events})

    (t/testing "an outsider gets not-found, so a session id is not probeable"
      (let [out (th/command! {::th/type :get-design-session
                              ::rpc/profile-id (:id outsider)
                              :id rec-id})]
        (t/is (= :not-found (th/ex-type (:error out))))))))

(t/deftest design-session-export-is-admin-only
  (reset-sessions!)
  (let [owner      (th/create-profile* 1 {:is-active true})
        outsider   (th/create-profile* 2 {:is-active true})
        file       (th/create-file* 1 {:profile-id (:id owner)
                                       :project-id (:default-project-id owner)})]
    (th/command! {::th/type :upsert-design-session
                  ::rpc/profile-id (:id owner)
                  :id (UUID/randomUUID) :file-id (:id file)
                  :session-id (UUID/randomUUID) :events events})

    (t/testing "the team owner (an admin) exports the bot-facing bundle"
      (let [out (th/command! {::th/type :export-design-sessions
                              ::rpc/profile-id (:id owner)
                              :file-id (:id file)})]
        (t/is (nil? (:error out)))
        (t/is (= 1 (get-in out [:result :session-count])))
        (t/is (= events (-> out :result :sessions first :events))
              "the export carries full timelines, ready to feed an agent")))

    (t/testing "an outsider cannot harvest a file's sessions"
      (let [out (th/command! {::th/type :export-design-sessions
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)})]
        (t/is (some? (:error out)))))))

(t/deftest design-session-export-can-select-ids
  (reset-sessions!)
  (let [owner      (th/create-profile* 1 {:is-active true})
        file       (th/create-file* 1 {:profile-id (:id owner)
                                       :project-id (:default-project-id owner)})
        keep-id    (UUID/randomUUID)]
    (doseq [id [keep-id (UUID/randomUUID)]]
      (th/command! {::th/type :upsert-design-session
                    ::rpc/profile-id (:id owner)
                    :id id :file-id (:id file)
                    :session-id (UUID/randomUUID) :events events}))

    (t/testing "an explicit id list narrows the bundle"
      (let [out (th/command! {::th/type :export-design-sessions
                              ::rpc/profile-id (:id owner)
                              :file-id (:id file)
                              :ids [keep-id]})]
        (t/is (= 1 (get-in out [:result :session-count])))
        (t/is (= keep-id (-> out :result :sessions first :id)))))

    (t/testing "and omitting it takes everything"
      (let [out (th/command! {::th/type :export-design-sessions
                              ::rpc/profile-id (:id owner)
                              :file-id (:id file)})]
        (t/is (= 2 (get-in out [:result :session-count])))))))
