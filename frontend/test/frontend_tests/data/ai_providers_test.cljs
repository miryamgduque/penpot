;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.ai-providers-test
  "What the frontend makes of the model catalog.

  The catalog itself is not kept in the frontend: it is the provider layer's,
  read from the agent service (see `agent/test/catalog.test.js`). What is
  held here is how its answers are read, above all that a capability nobody
  stated is not taken for a yes."
  (:require
   [app.main.data.ai-providers :as dai]
   [cljs.test :as t :include-macros true]))

(def ^:private catalog
  {:zhipu [{:provider "zhipu" :id "glm-5.2" :name "GLM-5.2" :vision false :tools true
            :reasoning true :promptCaching nil :contextLimit 1000000 :outputLimit 128000 :price nil}
           {:provider "zhipu" :id "glm-5v-turbo" :name "GLM-5V Turbo" :vision true :tools true
            :reasoning true :promptCaching true :contextLimit 200000 :outputLimit 64000
            :price {:input 1 :output 4 :cacheRead 0.2 :cacheWrite 0}}]
   :local [{:provider "local" :id "some-model" :name "some-model" :vision nil :tools nil
            :reasoning nil :promptCaching nil :contextLimit nil :outputLimit nil :price nil}]})

(t/use-fixtures :each
  {:before #(dai/set-catalog! catalog)
   :after  #(dai/set-catalog! {})})

(t/deftest vision-is-a-fact-of-the-model-not-of-the-provider
  (t/is (true? (dai/vision? "zhipu" "glm-5v-turbo")))
  (t/is (false? (dai/vision? "zhipu" "glm-5.2"))))

(t/deftest what-is-unknown-is-not-taken-for-a-yes
  ;; the catalog does not say whether this one reads images
  (t/is (nil? (:vision (dai/model "local" "some-model"))))
  (t/is (false? (dai/vision? "local" "some-model")))
  ;; nor is a model the catalog does not have, or a catalog that has not arrived
  (t/is (false? (dai/vision? "zhipu" "glm-9")))
  (t/is (false? (dai/vision? "nobody" "anything")))
  (dai/set-catalog! {})
  (t/is (false? (dai/vision? "zhipu" "glm-5v-turbo"))))

(t/deftest a-model-known-not-to-call-tools-cannot-be-chosen
  (dai/set-catalog! (assoc-in catalog [:local 0 :tools] false))
  (t/is (false? (dai/selectable? "local" "some-model")))
  ;; one that does, and one nobody stated it for, can
  (t/is (true? (dai/selectable? "zhipu" "glm-5.2")))
  (dai/set-catalog! catalog)
  (t/is (true? (dai/selectable? "local" "some-model")))
  (t/is (true? (dai/selectable? "nobody" "anything"))))

(t/deftest the-records-of-a-provider-are-read-by-its-id
  (t/is (= ["glm-5.2" "glm-5v-turbo"] (mapv :id (dai/models "zhipu"))))
  (t/is (= [] (dai/models "nobody")))
  (t/is (= 200000 (:contextLimit (dai/model "zhipu" "glm-5v-turbo"))))
  (t/is (nil? (dai/model "zhipu" "glm-9"))))
