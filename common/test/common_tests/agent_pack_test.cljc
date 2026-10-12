;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-pack-test
  (:require
   [app.common.agent.pack :as cak]
   [app.common.agent.packs :as caks]
   [app.common.agent.recipe :as car]
   [clojure.test :as t]))

(def ^:private pack caks/neutral)

(defn- problem
  [f & args]
  (cak/problem (apply f pack args)))

(t/deftest the-starter-packs-are-valid-and-fill-every-role
  (t/is (= ["neutral" "bold" "soft"] (mapv :name caks/all)))
  (doseq [pack caks/all]
    (t/testing (:name pack)
      (t/is (nil? (cak/problem pack)))
      ;; a light and a dark theme, each with a value for every role
      (t/is (= ["Light" "Dark"] (mapv :name (:themes pack))))
      (doseq [theme (:themes pack)]
        (t/is (= (set (keys car/roles)) (set (keys (cak/theme-values pack theme))))))
      ;; every pair of text and ground can be read, in both
      (t/is (= [] (cak/contrast-problems pack))))))

(t/deftest the-starter-packs-differ-where-it-shows
  (let [light (fn [pack] (cak/theme-values pack (first (:themes pack))))]
    (doseq [role ["color.primary" "radius.md" "font-family.heading" "shadow.md"]]
      (t/is (= 3 (count (into #{} (map #(get (light %) role)) caks/all))) role))))

(t/deftest a-malformed-pack-is-told-what-is-wrong
  (t/is (re-find #"a pack is a map" (cak/problem [])))
  (t/is (re-find #"needs `pack`: 1" (problem dissoc :pack)))
  (t/is (re-find #"needs a `name` in kebab-case" (problem assoc :name "My Pack")))
  (t/is (re-find #"needs a `purpose`" (problem dissoc :purpose)))
  (t/is (re-find #"the pack has \"components\", which is not part of the format" (problem assoc :components [])))
  (t/is (re-find #"needs `sets`" (problem assoc :sets [])))
  (t/is (re-find #"has two sets called \"core\"" (problem update :sets conj (first (:sets pack)))))
  (t/is (re-find #"sets\[0\] \(core\) has no tokens" (problem assoc-in [:sets 0 :tokens] {})))
  (t/testing "values"
    (t/is (re-find #"modes/light.color.primary is not a color"
                   (problem assoc-in [:sets 1 :tokens "color.primary"] "blue")))
    (t/is (re-find #"core.spacing.md is not a number"
                   (problem assoc-in [:sets 0 :tokens "spacing.md"] "wide")))
    (t/is (re-find #"modes/light.shadow.md is not a shadow"
                   (problem assoc-in [:sets 1 :tokens "shadow.md"] "0 4px 12px black")))
    (t/is (re-find #"core.font-family.body is not a font family"
                   (problem assoc-in [:sets 0 :tokens "font-family.body"] 12)))
    (t/is (re-find #"core.color.primary is a role, so it is written as its value alone"
                   (problem assoc-in [:sets 0 :tokens "color.primary"] {:type "color" :value "#000000"}))))
  (t/testing "tokens that are not roles say their type"
    (t/is (re-find #"core.blue.600 is not a role, so it says its type"
                   (problem assoc-in [:sets 0 :tokens "blue.600"] "#2563eb")))
    (t/is (re-find #"core.blue.600 has the type \"hue\""
                   (problem assoc-in [:sets 0 :tokens "blue.600"] {:type "hue" :value "#2563eb"})))
    (t/is (nil? (-> pack
                    (assoc-in [:sets 0 :tokens "blue.600"] {:type "color" :value "#2563eb"})
                    (assoc-in [:sets 1 :tokens "color.primary"] "{blue.600}")
                    (cak/problem)))))
  (t/testing "themes"
    (t/is (re-find #"needs `themes`" (problem assoc :themes [])))
    (t/is (re-find #"theme \"Light\" turns on the set \"modes/sepia\", which the pack does not have"
                   (problem assoc-in [:themes 0 :sets] ["core" "modes/sepia"])))
    (t/is (re-find #"theme \"Dark\" leaves 1 role\(s\) without a value: color.info"
                   (problem update-in [:sets 2 :tokens] dissoc "color.info")))
    (t/is (re-find #"in theme \"Light\", \"color.primary\" refers to \"blue.600\", which it does not hold"
                   (problem assoc-in [:sets 1 :tokens "color.primary"] "{blue.600}")))
    (t/is (re-find #"refers back to itself"
                   (problem update-in [:sets 1 :tokens] assoc "color.primary" "{color.info}" "color.info" "{color.primary}")))
    (t/is (re-find #"has the set \"extra\", which no theme turns on"
                   (problem update :sets conj {:name "extra" :tokens {"spacing.md" 10}})))
    (t/is (re-find #"has the default \"Sepia\", which is not one of its themes: Light, Dark"
                   (problem assoc :default "Sepia")))))

(t/deftest a-pack-is-data-only
  (t/is (re-find #"a pack is data only" (problem assoc :purpose (fn [] "x"))))
  (t/is (re-find #"a pack is data only" (problem assoc-in [:sets 0 :tokens "spacing.md"] #?(:clj (Object.) :cljs (js/Date.)))))
  ;; as it arrives from JSON: keyword keys, names of tokens among them
  (let [json (-> pack
                 (update :sets (fn [sets] (mapv (fn [s] (update s :tokens #(into {} (map (fn [[k v]] [(keyword k) v])) %))) sets))))]
    (t/is (nil? (cak/problem json)))
    (t/is (= "#2563eb" (get (cak/theme-values json (first (:themes json))) "color.primary")))))

(t/deftest contrast-is-the-wcag-ratio
  (t/is (= 21.0 (cak/contrast "#000000" "#ffffff")))
  (t/is (= 1.0 (cak/contrast "#fff" "rgb(255, 255, 255)")))
  (t/is (nil? (cak/contrast "rgba(0, 0, 0, 0.5)" "#ffffff")))
  (let [faint (assoc-in pack [:sets 1 :tokens "color.text-muted"] "#c8c8cc")]
    (t/is (nil? (cak/problem faint)))
    (t/is (= [{:theme "Light" :text "color.text-muted" :ground "color.background"}
              {:theme "Light" :text "color.text-muted" :ground "color.surface"}]
             (mapv #(dissoc % :ratio) (cak/contrast-problems faint))))
    (t/is (< 1 (:ratio (first (cak/contrast-problems faint))) 4.5))))
