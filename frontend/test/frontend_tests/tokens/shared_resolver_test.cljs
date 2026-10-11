;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.tokens.shared-resolver-test
  "Holds the shared token resolver (`app.common.tokens.resolver`, used where
  there is no workspace) to the workspace's own: the same tokens have to be
  worth the same, and fail to resolve in the same cases."
  (:require
   [app.common.tokens.resolver :as ctr]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.style-dictionary :as sd]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

(def ^:private tokens
  [{:name "color.brand" :type :color :value "#6366F1"}
   {:name "color.alias" :type :color :value "{color.brand}"}
   {:name "color.rgba" :type :color :value "rgba(99, 102, 241, 0.5)"}
   {:name "color.hsl" :type :color :value "hsl(120, 100%, 25%)"}
   {:name "color.named" :type :color :value "rebeccapurple"}
   {:name "color.bad" :type :color :value "notacolor"}
   {:name "color.missing" :type :color :value "{color.nope}"}
   {:name "space.md" :type :spacing :value "16px"}
   {:name "space.double" :type :spacing :value "{space.md} * 2"}
   {:name "space.sum" :type :spacing :value "{space.md} + 4"}
   {:name "space.rem" :type :spacing :value "1.5rem"}
   {:name "space.plain" :type :spacing :value "8"}
   {:name "space.huge" :type :spacing :value "123456789012345"}
   {:name "radius.sm" :type :border-radius :value "4"}
   {:name "size.w" :type :sizing :value "{space.md} * 10"}
   {:name "dim.a" :type :dimensions :value "64px"}
   {:name "stroke.w" :type :stroke-width :value "3"}
   {:name "stroke.neg" :type :stroke-width :value "-1"}
   {:name "fade" :type :opacity :value "0.5"}
   {:name "fade.pct" :type :opacity :value "50%"}
   {:name "fade.bad" :type :opacity :value "3"}
   {:name "turn" :type :rotation :value "45"}
   {:name "count" :type :number :value "3"}
   {:name "count.px" :type :number :value "3px"}
   {:name "font.size" :type :font-size :value "24"}
   {:name "font.size.calc" :type :font-size :value "{font.size} / 2"}
   {:name "font.family" :type :font-family :value ["Inter" "sans-serif"]}
   {:name "font.family.alias" :type :font-family :value "{font.family}"}
   {:name "font.weight" :type :font-weight :value "bold"}
   {:name "font.weight.italic" :type :font-weight :value "600 Italic"}
   {:name "font.weight.bad" :type :font-weight :value "heavyish"}
   {:name "letter" :type :letter-spacing :value "2"}
   {:name "letter.pct" :type :letter-spacing :value "5%"}
   {:name "case.up" :type :text-case :value "Uppercase"}
   {:name "case.bad" :type :text-case :value "shouting"}
   {:name "deco" :type :text-decoration :value "underline"}
   {:name "type.heading" :type :typography
    :value {:font-family ["Inter"] :font-size "{font.size}" :font-weight "bold"
            :line-height "150%" :letter-spacing "1" :text-case "uppercase"}}
   {:name "type.alias" :type :typography :value "{type.heading}"}
   {:name "shadow.card" :type :shadow
    :value [{:offset-x "0" :offset-y "{space.plain}" :blur "12" :spread "0"
             :color "rgba(0,0,0,0.25)" :inset false}]}])

(defn- tokens-map
  []
  (let [set-id (uuid/next)]
    (-> (reduce (fn [lib token] (ctob/add-token lib set-id (ctob/make-token token)))
                (-> (ctob/make-tokens-lib)
                    (ctob/add-set (ctob/make-token-set :id set-id :name "core")))
                tokens)
        (ctob/get-all-tokens-map))))

(defn- close-to?
  "Numbers that differ only by float noise are the same value."
  [a b]
  (cond
    (and (number? a) (number? b)) (< (abs (- a b)) 1e-9)
    (and (map? a) (map? b))       (and (= (set (keys a)) (set (keys b)))
                                       (every? #(close-to? (get a %) (get b %)) (keys a)))
    (and (sequential? a) (sequential? b)) (and (= (count a) (count b))
                                               (every? true? (map close-to? a b)))
    :else (= a b)))

(t/deftest the-shared-resolver-agrees-with-the-workspace
  (t/async
    done
    (let [tokens (tokens-map)]
      (-> (sd/resolve-tokens tokens)
          (rx/sub!
           (fn [workspace]
             (-> (ctr/resolve-tokens tokens)
                 (.then
                  (fn [shared]
                    (t/is (= (set (keys workspace)) (set (keys shared))))
                    (doseq [[name ours] workspace
                            :let [theirs (get shared name)
                                  kind   (:type ours)]]
                      (t/testing name
                        ;; the same tokens fail to resolve
                        (t/is (= (boolean (seq (:errors ours))) (some? (:error theirs))))
                        (when (empty? (:errors ours))
                          (t/is (= (:unit ours) (:unit theirs)))
                          (if (= :shadow kind)
                            ;; the shared resolver hands a shadow's color
                            ;; over already read; the rest is the same
                            (t/is (close-to? (mapv #(dissoc % :color) (:resolved-value ours))
                                             (mapv #(dissoc % :color) (:resolved-value theirs))))
                            (t/is (close-to? (:resolved-value ours) (:resolved-value theirs)))))))
                    (done)))
                 (.catch
                  (fn [cause]
                    (t/is (nil? cause))
                    (done))))))))))

(t/deftest a-color-is-read-as-a-shape-holds-it
  (t/is (= {:color "#6366f1" :opacity 1} (ctr/color "#6366F1")))
  (t/is (= {:color "#6366f1" :opacity 0.5} (ctr/color "rgba(99, 102, 241, 0.5)")))
  (t/is (= {:color "#663399" :opacity 1} (ctr/color "rebeccapurple")))
  ;; a hex has to carry its #, as in the workspace
  (t/is (nil? (ctr/color "6366f1")))
  (t/is (nil? (ctr/color "notacolor"))))
