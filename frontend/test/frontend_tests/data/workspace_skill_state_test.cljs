;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-skill-state-test
  "Resolution of the per-user skill on/off state (US #8): built-in default →
  account default → per-file override, and the enabled-skills filter that feeds
  the agent's router."
  (:require
   [app.main.data.workspace.agent-skills :as ask]
   [cljs.test :refer [deftest is testing]]))

(def a-skill "penpot-rename-layers")

(deftest resolve-enabled-precedence
  (testing "no overrides → the built-in default is used"
    (is (true? (ask/resolve-enabled true {} {} a-skill)))
    (is (false? (ask/resolve-enabled false {} {} a-skill))))

  (testing "account override wins over the default"
    (is (false? (ask/resolve-enabled true {a-skill false} {} a-skill)))
    (is (true? (ask/resolve-enabled false {a-skill true} {} a-skill))))

  (testing "per-file override wins over the account default"
    (is (true? (ask/resolve-enabled false {a-skill false} {a-skill true} a-skill)))
    (is (false? (ask/resolve-enabled true {a-skill true} {a-skill false} a-skill))))

  (testing "a false override is honored (contains?, not truthiness)"
    (is (false? (ask/resolve-enabled true {} {a-skill false} a-skill)))
    (is (false? (ask/resolve-enabled true {a-skill false} {} a-skill))))

  (testing "an unrelated override does not leak onto this skill"
    (is (true? (ask/resolve-enabled true {"other" false} {"other" false} a-skill)))))

(deftest enabled-skills-uses-resolved-state
  (testing "with no overrides every built-in skill is enabled (all-on baseline)"
    (let [all      (for [g ask/catalog s (:skills g)] (:name s))
          enabled  (set (map :name (ask/enabled-skills {})))]
      (is (= (set all) enabled))))

  (testing "a per-file 'off' override drops the skill from the router set"
    (let [file-id  #uuid "00000000-0000-0000-0000-000000000001"
          state    {:current-file-id file-id
                    :skill-state {:files {file-id {a-skill false}}}}
          enabled  (set (map :name (ask/enabled-skills state)))]
      (is (not (contains? enabled a-skill)))
      (is (contains? enabled "penpot-audit-tokens"))))

  (testing "a per-file 'on' override re-enables a skill turned off at the account level"
    (let [file-id  #uuid "00000000-0000-0000-0000-000000000001"
          state    {:current-file-id file-id
                    :skill-state {:account {a-skill false}
                                  :files {file-id {a-skill true}}}}
          enabled  (set (map :name (ask/enabled-skills state)))]
      (is (contains? enabled a-skill))))

  (testing "an account 'off' with no per-file override drops the skill"
    (let [state   {:current-file-id #uuid "00000000-0000-0000-0000-000000000001"
                   :skill-state {:account {a-skill false}}}
          enabled (set (map :name (ask/enabled-skills state)))]
      (is (not (contains? enabled a-skill))))))

(deftest resolved-enabled-map-covers-catalog
  (testing "the resolved map has an entry for every built-in skill"
    (let [names (set (for [g ask/catalog s (:skills g)] (:name s)))
          m     (ask/resolved-enabled-map {})]
      (is (= names (set (keys m))))
      (is (every? true? (vals m)))))

  (testing "an override is reflected in the resolved map"
    (let [file-id #uuid "00000000-0000-0000-0000-000000000001"
          m       (ask/resolved-enabled-map
                   {:current-file-id file-id
                    :skill-state {:files {file-id {a-skill false}}}})]
      (is (false? (get m a-skill))))))

(deftest system-prompt-section-reflects-toggles
  (testing "a disabled skill's label is absent from the routing index"
    (let [file-id #uuid "00000000-0000-0000-0000-000000000001"
          section (ask/system-prompt-section
                   {:current-file-id file-id
                    :skill-state {:files {file-id {a-skill false}}}})]
      (is (string? section))
      (is (not (re-find #"Rename layers" section)))
      (is (re-find #"Tokens governance audit" section)))))
