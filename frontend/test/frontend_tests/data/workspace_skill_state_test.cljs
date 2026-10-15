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
   [app.main.data.workspace.slash-commands :as slash]
   [cljs.test :refer [deftest is testing]]
   [cuerdas.core :as str]))

(def a-skill "penpot-rename-layers")

;; The catalog depends on who is looking: someone who may only view the file
;; is listed only the skills that read (see `ask/viewer-skills`). These tests
;; are about an editor's catalog.
(def ^:private editor {:permissions {:can-edit true}})

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
          enabled  (set (map :name (ask/enabled-skills editor)))]
      (is (= (set all) enabled))))

  (testing "a per-file 'off' override drops the skill from the router set"
    (let [file-id  #uuid "00000000-0000-0000-0000-000000000001"
          state    {:permissions {:can-edit true}
                    :current-file-id file-id
                    :skill-state {:files {file-id {a-skill false}}}}
          enabled  (set (map :name (ask/enabled-skills state)))]
      (is (not (contains? enabled a-skill)))
      (is (contains? enabled "penpot-audit-tokens"))))

  (testing "a per-file 'on' override re-enables a skill turned off at the account level"
    (let [file-id  #uuid "00000000-0000-0000-0000-000000000001"
          state    {:permissions {:can-edit true}
                    :current-file-id file-id
                    :skill-state {:account {a-skill false}
                                  :files {file-id {a-skill true}}}}
          enabled  (set (map :name (ask/enabled-skills state)))]
      (is (contains? enabled a-skill))))

  (testing "an account 'off' with no per-file override drops the skill"
    (let [state   {:permissions {:can-edit true}
                   :current-file-id #uuid "00000000-0000-0000-0000-000000000001"
                   :skill-state {:account {a-skill false}}}
          enabled (set (map :name (ask/enabled-skills state)))]
      (is (not (contains? enabled a-skill))))))

(deftest resolved-enabled-map-covers-catalog
  (testing "the resolved map has an entry for every built-in skill"
    (let [names (set (for [g ask/catalog s (:skills g)] (:name s)))
          m     (ask/resolved-enabled-map editor)]
      (is (= names (set (keys m))))
      (is (every? true? (vals m)))))

  (testing "an override is reflected in the resolved map"
    (let [file-id #uuid "00000000-0000-0000-0000-000000000001"
          m       (ask/resolved-enabled-map
                   {:permissions {:can-edit true}
                    :current-file-id file-id
                    :skill-state {:files {file-id {a-skill false}}}})]
      (is (false? (get m a-skill))))))

(deftest system-prompt-section-reflects-toggles
  (testing "a disabled skill's label is absent from the routing index"
    (let [file-id #uuid "00000000-0000-0000-0000-000000000001"
          section (ask/system-prompt-section
                   {:permissions {:can-edit true}
                    :current-file-id file-id
                    :skill-state {:files {file-id {a-skill false}}}})]
      (is (string? section))
      (is (not (re-find #"Rename layers" section)))
      (is (re-find #"Tokens governance audit" section)))))

;; --- What a viewer is listed (#153)

(def ^:private viewer {:permissions {:can-edit false :can-read true}})

(def ^:private read-only-skills
  #{"penpot-audit-accessibility" "penpot-audit-tokens" "penpot-design-to-code-review"})

(deftest a-viewer-is-listed-only-the-skills-that-read
  (is (= read-only-skills ask/viewer-skills))
  (is (= read-only-skills (set (for [g (ask/full-catalog viewer) s (:skills g)] (:name s)))))
  (is (= read-only-skills (set (map :name (ask/enabled-skills viewer)))))
  (testing "a group left empty is dropped, not shown as a bare heading"
    (is (= ["Audits"] (mapv :category (ask/full-catalog viewer))))))

(deftest a-viewer-is-listed-none-of-their-own-or-the-teams-skills
  ;; nothing says those only read
  (let [state (assoc viewer
                     :user-skills [{:name "my-checker" :label "My checker" :category "Audits"
                                    :body "x" :enabled true}])]
    (is (not (contains? (set (map :name (ask/enabled-skills state))) "my-checker")))))

(deftest every-route-to-a-hidden-skill-is-closed-for-a-viewer
  (testing "the agent's index and the skills handed to the agent service"
    (is (not (re-find #"Build screen|Rename layers" (ask/system-prompt-section viewer))))
    (is (every? #(or (contains? read-only-skills (:name %))
                     (some (fn [s] (str/starts-with? (:name %) (str s "-ref-"))) read-only-skills))
                (ask/harness-skills viewer))))
  (testing "get_design_skills"
    (is (= read-only-skills (set (map :name (ask/catalog-manifest viewer)))))
    (is (nil? (ask/catalog-manifest viewer "penpot-build-screen"))))
  (testing "the skill detail view"
    (is (nil? (ask/find-skill viewer "penpot-rename-layers"))))
  (testing "the / menu"
    (let [commands (set (map :command (slash/menu-model viewer)))]
      (is (= read-only-skills commands) "/vibes goes with the vibes skill")))
  (testing "the observer watches only the rules of the skills that read"
    (is (= #{"token-only-colors"} (ask/watched-rules viewer)))))

(deftest an-editor-is-listed-every-skill
  (let [all (set (for [g ask/catalog s (:skills g)] (:name s)))]
    (is (= all (set (map :name (ask/enabled-skills editor)))))
    (is (contains? (set (map :command (slash/menu-model editor))) "vibes"))))
