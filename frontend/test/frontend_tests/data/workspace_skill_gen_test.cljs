;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-skill-gen-test
  "US #9 Phase 03: parse/normalize the model's generation reply into a skill map."
  (:require
   [app.main.data.workspace.skill-gen :as sg]
   [cljs.test :refer [deftest is testing]]
   [cuerdas.core :as str]))

(def answers
  {:what "Check all copy against a tone"
   :trigger "Check the tone on this screen."
   :mode "suggest"})

(deftest extract-json-tolerates-fences-and-prose
  (is (= "{\"a\":1}" (sg/extract-json "```json\n{\"a\":1}\n```")))
  (is (= "{\"a\":1}" (sg/extract-json "Here you go: {\"a\":1} — thanks")))
  (is (= "{\"a\":1}" (sg/extract-json "{\"a\":1}")))
  (is (nil? (sg/extract-json "no json here")))
  (is (nil? (sg/extract-json nil))))

(deftest clamp-category-maps-to-an-existing-one
  (is (= "Audits" (sg/clamp-category "Audits")))
  (is (= "Audits" (sg/clamp-category "audit")))
  (is (= "Build" (sg/clamp-category "build")))
  (is (= "Auto-fix" (sg/clamp-category "auto")))
  (is (= "Audits" (sg/clamp-category "Something else"))))

(deftest parse-generation-builds-a-skill
  (testing "model supplies name/label/category/body; user's mode/trigger/what win"
    (let [text  "```json\n{\"name\":\"Tone Checker!\",\"label\":\"Tone checker\",\"category\":\"audits\",\"body\":\"# Playbook\\nDo things.\"}\n```"
          skill (sg/parse-generation answers text)]
      (is (= "tone-checker" (:name skill)))
      (is (= "Tone checker" (:label skill)))
      (is (= "Audits" (:category skill)))
      (is (= "suggest" (:mode skill)))
      (is (= "Check the tone on this screen." (:trigger skill)))
      (is (= "Check all copy against a tone" (:description skill)))
      (is (str/includes? (:body skill) "Playbook")))))

(deftest parse-generation-nil-when-unusable
  (testing "missing body → nil (retryable)"
    (is (nil? (sg/parse-generation answers "{\"name\":\"x\",\"label\":\"X\",\"category\":\"Build\"}"))))
  (testing "no JSON at all → nil"
    (is (nil? (sg/parse-generation answers "sorry, I can't")))))

(deftest parse-generation-name-fallbacks
  (testing "no name → slug of label"
    (is (= "just-a-label"
           (:name (sg/parse-generation answers "{\"label\":\"Just A Label\",\"body\":\"b\"}")))))
  (testing "no name/label → slug of the 'what'"
    (is (= "check-all-copy-against-a-tone"
           (:name (sg/parse-generation answers "{\"body\":\"b\"}"))))))
