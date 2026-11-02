;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.design-sessions-db-test
  "Pins the separate design-sessions database as OPTIONAL (phase 05 of the
  design-session-recording plan).

  The whole point of a second database is that it can be dropped, moved or
  scaled without touching Penpot. That is only true if Penpot also runs fine
  without it, so \"absent config must not break boot\" is a hard requirement
  rather than a nicety — and it is what this namespace guards.

  No database needed: these exercise the integrant lifecycle methods directly."
  (:require
   [app.db :as db]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.migrations :as mg]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [integrant.core :as ig]))

;; --- the pool is optional

(t/deftest sessions-pool-is-nil-when-unconfigured
  (t/testing "no uri = no pool, which is how Penpot boots without the sessions
              database at all"
    (t/is (nil? (ig/init-key ::main/sessions-pool {})))
    (t/is (nil? (ig/init-key ::main/sessions-pool {::db/name :sessions})))))

(t/deftest sessions-pool-accepts-an-unconfigured-options-map
  (t/testing "assert-key must not reject the absent-config case, or the system
              would fail to init instead of degrading. The system map always
              passes the metrics, which `::db/pool` requires."
    (let [metrics (th/metrics-stub)]
      (t/is (nil? (ig/assert-key ::main/sessions-pool
                                 {::mtx/metrics metrics})))
      (t/is (nil? (ig/assert-key ::main/sessions-pool
                                 {::db/name     :sessions
                                  ::mtx/metrics metrics}))))))

(t/deftest sessions-pool-rejects-malformed-options
  (t/testing "with backend asserts on, `::db/pool`'s schema checks the sessions
              pool options too, so a malformed size stops boot instead of
              passing unseen"
    (t/is (true? *assert*)
          "run the suite with PENPOT_FLAGS=\"enable-backend-asserts\"")
    (t/is (thrown? Exception
                   (ig/assert-key ::main/sessions-pool
                                  {::db/max-size  "eight"
                                   ::mtx/metrics (th/metrics-stub)})))))

(t/deftest halting-an-absent-pool-is-safe
  (t/testing "shutdown runs on the degraded path too"
    (t/is (nil? (ig/halt-key! ::main/sessions-pool nil)))))

;; --- migrations are optional in the same way

(t/deftest session-migrations-skip-without-a-pool
  (t/testing "a nil pool is the normal supported state, not an error"
    (t/is (nil? (ig/init-key ::mg/session-migrations {::db/pool nil})))
    (t/is (nil? (ig/init-key ::mg/session-migrations {})))))

(t/deftest session-migrations-assert-accepts-nil-pool
  (t/testing "a nil pool must pass the assertion — it is the supported degraded
              state, not a misconfiguration"
    (t/is (nil? (ig/assert-key ::mg/session-migrations {::db/pool nil})))))

(t/deftest session-migrations-assert-rejects-a-non-pool
  (t/testing "anything other than nil or a pool is a misconfiguration"
    (t/is (thrown? AssertionError
                   (ig/assert-key ::mg/session-migrations
                                  {::db/pool :not-a-pool})))))

;; --- the migration set itself

(t/deftest session-migrations-are-well-formed
  (t/testing "each step has a name and a runnable fn"
    (t/is (seq mg/session-migrations))
    (doseq [{:keys [name fn]} mg/session-migrations]
      (t/is (string? name))
      (t/is (fn? fn)))))

(t/deftest session-migrations-are-numbered-independently
  (t/testing "this database has its own bookkeeping (module 'sessions' in its own
              `migrations` table), so numbering restarts at 0001 and cannot
              collide with Penpot's main sequence — which also sidesteps two
              branches claiming the same migration number"
    (t/is (= "0001-add-design-session-tables"
             (:name (first mg/session-migrations))))
    (t/testing "and the names are unique within the set"
      (let [names (map :name mg/session-migrations)]
        (t/is (= (count names) (count (distinct names))))))))
