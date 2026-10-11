;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-tokens-test
  (:require
   [app.common.agent.tokens :as cato]
   [app.common.files.changes :as cfc]
   [app.common.types.file :as ctf]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private data (ctf/make-file-data (uuid/next) (uuid/next)))

(defn- run
  [data tool input]
  (tool {:data data} input nil))

(defn- commit
  [data out]
  (cfc/process-changes data (:changes out)))

(defn- token-names
  [data]
  (->> (ctob/get-tokens-in-active-sets (:tokens-lib data)) (vals) (map :name) (set)))

(t/deftest create-token-names-what-is-wrong
  (t/is (re-find #"name is required" (:problem (run data cato/create-token {:type "color" :value "#fff"}))))
  (t/is (re-find #"value is required" (:problem (run data cato/create-token {:type "color" :name "c"}))))
  (t/is (re-find #"\"hue\" is not a token type" (:problem (run data cato/create-token {:type "hue" :name "c" :value "1"}))))
  (t/is (re-find #"set \"modes/dark\" does not exist — this file has no token sets yet"
                 (:problem (run data cato/create-token {:type "color" :name "c" :value "#fff" :set "modes / dark"})))))

(t/deftest the-first-token-makes-a-live-set
  (let [out   (run data cato/create-token {:type "color" :name "color.brand" :value "#6366f1"})
        after (commit data out)
        lib   (:tokens-lib after)]
    (t/is (= ["Global"] (mapv ctob/get-name (ctob/get-sets lib))))
    ;; the token is in an ACTIVE set: it resolves, and counts for token-only-colors
    (t/is (= #{"color.brand"} (token-names after)))
    (t/is (= "token created — bind it to shapes with apply_tokens" (get-in out [:result :note])))))

(t/deftest a-later-token-joins-the-existing-set
  (let [first-data (commit data (run data cato/create-token {:type "color" :name "a" :value "#111111"}))
        after      (commit first-data (run first-data cato/create-token {:type "spacing" :name "space.md" :value "16"}))]
    (t/is (= 1 (count (ctob/get-sets (:tokens-lib after)))))
    (t/is (= #{"a" "space.md"} (token-names after)))))

(t/deftest create-tokens-is-all-or-nothing-and-makes-one-set
  (let [out   (run data cato/create-tokens {:tokens [{:type "color" :name "a" :value "#111111"}
                                                     {:type "color" :name "b" :value "#222222"}
                                                     {:type "spacing" :name "s" :value "8"}]})
        after (commit data out)]
    (t/is (= 3 (get-in out [:result :created])))
    ;; one "Global" set for all of them, not one per entry
    (t/is (= 1 (count (ctob/get-sets (:tokens-lib after)))))
    (t/is (= #{"a" "b" "s"} (token-names after))))
  (let [out (run data cato/create-tokens {:tokens [{:type "color" :name "a" :value "#111111"}
                                                   {:type "color" :value "#222222"}]})]
    (t/is (re-find #"^tokens\[1\]: create_token: name is required" (:problem out)))
    (t/is (nil? (:changes out))))
  (t/is (re-find #"pass tokens" (:problem (run data cato/create-tokens {})))))

(t/deftest sets-and-themes
  (let [with-set (commit data (run data cato/create-token-set {:name "modes / dark"}))
        lib      (:tokens-lib with-set)]
    (t/is (= ["modes/dark"] (mapv ctob/get-name (ctob/get-sets lib))))
    (t/is (re-find #"already exists" (:problem (run with-set cato/create-token-set {:name "modes/dark"}))))
    (t/testing "a token aimed at a set by name lands in it"
      (let [after (commit with-set (run with-set cato/create-token
                                        {:type "color" :name "bg" :value "#000000" :set "modes/dark"}))
            dark  (first (ctob/get-sets (:tokens-lib after)))]
        (t/is (= ["bg"] (map :name (vals (ctob/get-tokens (:tokens-lib after) (ctob/get-id dark))))))))
    (t/testing "a theme enables sets that exist"
      (t/is (re-find #"no set named \"nope\"" (:problem (run with-set cato/create-token-theme {:name "Dark" :sets ["nope"]}))))
      (let [out   (run with-set cato/create-token-theme {:name "Dark" :sets ["modes/dark"]})
            after (commit with-set out)]
        (t/is (= "Dark" (get-in out [:result :theme])))
        (t/is (some #(= "Dark" (ctob/get-name %)) (ctob/get-themes (:tokens-lib after))))))))
