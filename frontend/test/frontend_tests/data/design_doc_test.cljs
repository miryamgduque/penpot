;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.design-doc-test
  "US #38 Phase 05: per-file foundations — the vibes doc generalized to a
  named, open-ended set of standing-context docs in the file's plugin-data."
  (:require
   [app.main.data.workspace.design-doc :as dd]
   [cljs.test :refer [deftest is]]
   [cuerdas.core :as str]))

(def ^:private file-id :test-file)

(defn- state-with
  [plugin-data]
  {:current-file-id file-id
   :files {file-id {:data {:plugin-data {:penpot-vibes plugin-data}}}}})

(deftest slugify-normalizes-names
  (is (= "vibes" (dd/slugify "Vibes")))
  (is (= "tone-of-voice" (dd/slugify "Tone of Voice!")))
  (is (= "a11y-priorities" (dd/slugify "  A11y — priorities  ")))
  (is (= "" (dd/slugify "™!!"))))

(deftest display-name-inverts-a-slug
  (is (= "Vibes" (dd/display-name "vibes")))
  (is (= "Tone of voice" (dd/display-name "tone-of-voice"))))

(deftest legacy-key-is-the-vibes-foundation
  (let [s (state-with {"design-md" "# The vibes"})]
    (is (= "# The vibes" (dd/get-doc s)))
    (is (= "# The vibes" (dd/get-foundation s "vibes")))
    (is (= [{:slug "vibes" :doc "# The vibes"}] (dd/list-foundations s)))))

(deftest foundations-list-vibes-first-then-sorted
  (let [s (state-with {"design-md" "v"
                       "foundation/tone-of-voice" "t"
                       "foundation/a11y" "a"})]
    (is (= ["vibes" "a11y" "tone-of-voice"]
           (mapv :slug (dd/list-foundations s))))))

(deftest foundations-without-vibes-still-list
  (let [s (state-with {"foundation/tone-of-voice" "t"})]
    (is (nil? (dd/get-doc s)))
    (is (= [{:slug "tone-of-voice" :doc "t"}] (dd/list-foundations s)))))

(deftest blank-docs-are-not-foundations
  (let [s (state-with {"design-md" "   " "foundation/tone" ""})]
    (is (nil? (dd/get-doc s)))
    (is (empty? (dd/list-foundations s)))))

(deftest prompt-section-includes-every-foundation
  (let [s   (state-with {"design-md" "the vibes body"
                         "foundation/tone-of-voice" "the tone body"})
        sec (dd/system-prompt-section s)]
    (is (str/includes? sec "## Foundations"))
    (is (str/includes? sec "### Vibes"))
    (is (str/includes? sec "### Tone of voice"))
    (is (str/includes? sec "the vibes body"))
    (is (str/includes? sec "the tone body"))))

(deftest prompt-section-nil-when-no-foundations
  (is (nil? (dd/system-prompt-section (state-with {}))))
  (is (nil? (dd/system-prompt-section {}))))
