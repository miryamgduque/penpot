;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-user-skills-test
  "US #9: a user's created skills merge into the agent catalog and flow through
  the same resolve/router/body paths as built-ins."
  (:require
   [app.main.data.workspace.agent-skills :as ask]
   [cljs.test :refer [deftest is testing]]))

(def a-user-skill
  {:name "tone-of-voice-checker"
   :label "Tone of voice checker"
   :category "Audits"
   :mode "suggest"
   :trigger "Check the tone of voice on this screen."
   :description "Reviews copy against a described tone."
   :body "# Tone of voice checker\n\nFlag copy that doesn't match."
   :enabled true})

(def state {:user-skills [a-user-skill]})

(deftest merges-into-full-catalog
  (testing "the user skill lands in its category group alongside built-ins"
    (let [audits (some #(when (= "Audits" (:category %)) %) (ask/full-catalog state))
          names  (set (map :name (:skills audits)))]
      (is (contains? names "tone-of-voice-checker"))
      (is (contains? names "penpot-audit-tokens"))))

  (testing "an unknown category becomes its own appended group"
    (let [odd   {:name "x" :label "X" :category "Zzz" :mode "suggest" :body "b" :enabled true}
          cats  (map :category (ask/full-catalog {:user-skills [odd]}))]
      (is (some #{"Zzz"} cats)))))

(deftest flows-through-router-and-resolution
  (testing "appears in enabled-skills + resolved-enabled-map (default on)"
    (is (contains? (set (map :name (ask/enabled-skills state))) "tone-of-voice-checker"))
    (is (true? (get (ask/resolved-enabled-map state) "tone-of-voice-checker"))))

  (testing "appears in the get_design_skills listing"
    (is (contains? (set (map :name (ask/catalog-manifest state))) "tone-of-voice-checker")))

  (testing "a US #8 per-account 'off' override drops it from the router"
    (let [off {:user-skills [a-user-skill]
               :skill-state {:account {"tone-of-voice-checker" false}}}]
      (is (not (contains? (set (map :name (ask/enabled-skills off))) "tone-of-voice-checker"))))))

(deftest body-and-detail
  (testing "skill-body returns the stored body verbatim (no built-in preamble)"
    (is (= (:body a-user-skill) (ask/skill-body state "tone-of-voice-checker"))))

  (testing "a built-in body still gets the reframing preamble"
    (let [b (ask/skill-body state "penpot-audit-tokens")]
      (is (string? b))
      (is (re-find #"written for a different tool surface" b))))

  (testing "named catalog-manifest fetch carries the user skill's body"
    (is (= (:body a-user-skill)
           (:body (ask/catalog-manifest state "tone-of-voice-checker")))))

  (testing "find-skill locates the user skill, category-tagged"
    (let [found (ask/find-skill state "tone-of-voice-checker")]
      (is (= "Audits" (:category found)))
      (is (= "Tone of voice checker" (:label found))))))
