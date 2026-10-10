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
   [app.migrations :as mg]
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
              would fail to init instead of degrading"
    (t/is (nil? (ig/assert-key ::main/sessions-pool {})))
    (t/is (nil? (ig/assert-key ::main/sessions-pool {::db/name :sessions})))))

(t/deftest assert-key-validation-is-decorative-in-this-build
  (t/testing "Penpot runs with *assert* false, so every `ig/assert-key` body in
              the backend — including the pre-existing `::db/pool` one — is
              compiled out and validates NOTHING at runtime.

              Recorded because it is easy to assume otherwise and then rely on
              it: a typo'd sessions URI is NOT caught by the schema. What
              actually catches it is pool creation failing to connect, which
              surfaces as a boot error rather than a silently disabled recorder.
              The session-recorder keys follow the same convention as the rest of
              the backend on purpose — being the one namespace that validated
              differently would be worse than being consistently decorative."
    (t/is (false? *assert*)
          "if this ever fails, assertions were enabled and the assert-key
           methods became real — revisit the claim above")
    (t/is (nil? (ig/assert-key ::main/sessions-pool {::db/uri "not-a-uri"
                                                     ::db/max-size "eight"}))
          "malformed options pass, because the assert is not compiled in")))

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
              state, not a misconfiguration. (The malformed-pool case is not
              asserted here: see
              `assert-key-validation-is-decorative-in-this-build`.)"
    (t/is (nil? (ig/assert-key ::mg/session-migrations {::db/pool nil})))))

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
