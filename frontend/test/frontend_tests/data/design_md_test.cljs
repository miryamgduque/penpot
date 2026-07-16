;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.design-md-test
  "US #38 Phase 01: parse/serialize/validate the DESIGN.md format —
  YAML token frontmatter + markdown body (google-labs-code/design.md)."
  (:require
   [app.main.data.workspace.design-md :as dmd]
   [cljs.test :refer [deftest is]]
   [cuerdas.core :as str]))

(def valid-frontmatter
  {"version" "alpha"
   "name" "CostPulse"
   "description" "Friendly expense tracking for freelancers"
   "colors" {"primary" "#6366f1"
             "accent" "oklch(0.8 0.15 85)"
             "surface" "ivory"}
   "typography" {"heading" {"fontFamily" "Inter"
                            "fontSize" "24px"
                            "fontWeight" 600}
                 "body" {"fontFamily" "Inter"
                         "fontSize" "14px"
                         "lineHeight" 1.5}}
   "rounded" {"sm" "4px" "lg" "12px"}
   "spacing" {"xs" "4px" "md" "16px"}
   "components" {"button" {"background" "{colors.primary}"
                           "radius" "{rounded.sm}"}}})

(def valid-body
  (str/join "\n" ["## Overview" "An expense tracker." ""
                  "## Colors" "Indigo leads; ivory keeps it calm." ""
                  "## Do's and Don'ts" "- Do keep it light"]))

(deftest parse-doc-without-frontmatter-is-legacy
  (let [{:keys [frontmatter body error]} (dmd/parse "# Vibes\n\nWarm & handcrafted.")]
    (is (nil? frontmatter))
    (is (nil? error))
    (is (= "# Vibes\n\nWarm & handcrafted." body))))

(deftest parse-non-string-input
  (is (some? (:error (dmd/parse nil))))
  (is (some? (:error (dmd/parse 42)))))

(deftest parse-splits-frontmatter-and-body
  (let [doc "---\nname: Foo\ncolors:\n  primary: '#ff0000'\n---\n\n## Overview\nHi."
        {:keys [frontmatter body error]} (dmd/parse doc)]
    (is (nil? error))
    (is (= "Foo" (get frontmatter "name")))
    (is (= "#ff0000" (get-in frontmatter ["colors" "primary"])))
    (is (= "## Overview\nHi." body))))

(deftest parse-frontmatter-only-doc
  (let [{:keys [frontmatter body error]} (dmd/parse "---\nname: Bare\n---")]
    (is (nil? error))
    (is (= "Bare" (get frontmatter "name")))
    (is (= "" body))))

(deftest parse-broken-yaml-reports-error-without-throwing
  (let [{:keys [frontmatter body error]} (dmd/parse "---\ncolors: [unclosed\n---\nbody text")]
    (is (nil? frontmatter))
    (is (string? error))
    ;; the raw doc survives so nothing is lost for hand repair
    (is (str/includes? body "body text"))))

(deftest parse-scalar-frontmatter-is-an-error
  (let [{:keys [error]} (dmd/parse "---\njust a string\n---\nbody")]
    (is (string? error))))

(deftest serialize-without-frontmatter-is-body-alone
  (is (= "plain body" (dmd/serialize {:frontmatter nil :body "plain body"})))
  (is (= "plain body" (dmd/serialize {:frontmatter {} :body "plain body"}))))

(deftest round-trip-preserves-frontmatter-and-body
  (let [doc (dmd/serialize {:frontmatter valid-frontmatter :body valid-body})
        {:keys [frontmatter body error]} (dmd/parse doc)]
    (is (nil? error))
    (is (= valid-frontmatter frontmatter))
    (is (= valid-body body))))

(deftest serialize-orders-known-keys-canonically
  (let [doc (dmd/serialize {:frontmatter valid-frontmatter :body ""})
        idx (fn [s] (str/index-of doc s))]
    (is (< (idx "version:") (idx "name:") (idx "description:")
           (idx "colors:") (idx "typography:") (idx "rounded:")
           (idx "spacing:") (idx "components:")))))

(deftest problems-nil-for-a-valid-doc
  (is (empty? (dmd/problems {:frontmatter valid-frontmatter :body valid-body}))))

(deftest problems-nil-for-legacy-doc
  (is (empty? (dmd/problems {:frontmatter nil :body "just prose"}))))

(deftest problems-flags-missing-name
  (let [ps (dmd/problems {:frontmatter (dissoc valid-frontmatter "name") :body ""})]
    (is (some #(str/includes? % "name") ps))))

(deftest problems-flags-non-color-values
  (let [fm (assoc-in valid-frontmatter ["colors" "primary"] ["not" "a" "color"])
        ps (dmd/problems {:frontmatter fm :body ""})]
    (is (some #(str/includes? % "primary") ps))))

(deftest problems-flags-malformed-typography
  (let [fm (assoc-in valid-frontmatter ["typography" "heading"] "Inter 24px")
        ps (dmd/problems {:frontmatter fm :body ""})]
    (is (some #(str/includes? % "heading") ps))))

(deftest problems-flags-dangling-token-reference
  (let [fm (assoc-in valid-frontmatter ["components" "button" "background"]
                     "{colors.missing}")
        ps (dmd/problems {:frontmatter fm :body ""})]
    (is (some #(str/includes? % "colors.missing") ps))))

(deftest problems-accepts-resolving-token-references
  (is (empty? (dmd/problems {:frontmatter valid-frontmatter :body ""}))))

(deftest problems-tolerates-unknown-top-level-keys
  (let [fm (assoc valid-frontmatter "elevation" {"low" "0 1px 2px #0002"})]
    (is (empty? (dmd/problems {:frontmatter fm :body ""})))))

(deftest canonical-sections-are-exposed
  (is (= "Overview" (first dmd/canonical-sections)))
  (is (some #{"Do's and Don'ts"} dmd/canonical-sections)))
