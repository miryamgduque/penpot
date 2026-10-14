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
   [app.main.data.workspace.agent :as agent]
   [cljs.test :as t :include-macros true]))

(def ^:private catalog
  {:zhipu [{:provider "zhipu" :id "glm-5.2" :name "GLM-5.2" :vision false :tools true
            :reasoning true :promptCaching nil :contextLimit 1000000 :outputLimit 128000 :price nil}
           {:provider "zhipu" :id "glm-5v-turbo" :name "GLM-5V Turbo" :vision true :tools true
            :reasoning true :promptCaching true :contextLimit 200000 :outputLimit 64000
            :price {:input 1 :output 4 :cacheRead 0.2 :cacheWrite 0}}]
   :local [{:provider "local" :id "some-model" :name "some-model" :vision nil :tools nil
            :reasoning nil :promptCaching nil :contextLimit nil :outputLimit nil :price nil}]
   ;; the same id at another provider, at another price
   :gateway [{:provider "gateway" :id "glm-5v-turbo" :name "GLM-5V Turbo" :vision true :tools true
              :reasoning nil :promptCaching nil :contextLimit nil :outputLimit nil
              :price {:input 10 :output 40 :cacheRead 2 :cacheWrite 0}}
             {:provider "gateway" :id "cheap" :name "Cheap" :vision false :tools true
              :reasoning nil :promptCaching nil :contextLimit nil :outputLimit nil
              :price {:input 0.1 :output 0.4 :cacheRead 0 :cacheWrite 0}}]})

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

(t/deftest a-models-price-is-the-catalogs-or-nothing
  (t/is (= {:input 1 :output 4 :cacheRead 0.2 :cacheWrite 0} (dai/price "zhipu" "glm-5v-turbo")))
  ;; a model whose price nobody stated, and one the catalog does not have
  (t/is (nil? (dai/price "zhipu" "glm-5.2")))
  (t/is (nil? (dai/price "zhipu" "glm-9"))))

(t/deftest a-price-is-read-at-the-provider-that-serves-the-model
  ;; the same id, two providers, two prices
  (t/is (= 1 (:input (dai/price "zhipu" "glm-5v-turbo"))))
  (t/is (= 10 (:input (dai/price "gateway" "glm-5v-turbo")))))

(def ^:private million
  {:input-tokens 1000000 :output-tokens 0 :cache-read-tokens 0 :cache-write-tokens 0 :requests 1})

(t/deftest each-models-spend-is-priced-at-that-models-price
  (let [main   (agent/attributed million "zhipu" "glm-5v-turbo")
        helper (agent/attributed million "gateway" "cheap")
        both   (agent/add-usage main helper)]
    ;; totals still add up
    (t/is (= 2000000 (:input-tokens both)))
    (t/is (= 2 (:requests both)))
    ;; $1 for the main model's million, $0.10 for the helper's
    (t/is (= 1.1 (agent/estimate-cost-usd "zhipu" "glm-5v-turbo" both)))
    ;; not $2, as when everything was priced as the main model
    (t/is (not= 2 (agent/estimate-cost-usd "zhipu" "glm-5v-turbo" both)))))

(t/deftest spend-with-no-model-named-is-priced-as-the-sessions-model
  ;; a conversation saved before spend was attributed
  (t/is (= 1 (agent/estimate-cost-usd "zhipu" "glm-5v-turbo" million)))
  (t/is (= 2 (agent/estimate-cost-usd "zhipu" "glm-5v-turbo"
                                      (agent/add-usage million (agent/attributed million "zhipu" "glm-5v-turbo")))))
  ;; a helper with no stated price adds nothing
  (t/is (= 1 (agent/estimate-cost-usd "zhipu" "glm-5v-turbo"
                                      (agent/add-usage (agent/attributed million "zhipu" "glm-5v-turbo")
                                                       (agent/attributed million "local" "some-model")))))
  ;; and with no price for the session's model there is no figure
  (t/is (nil? (agent/estimate-cost-usd "zhipu" "glm-5.2" million))))

(t/deftest the-records-of-a-provider-are-read-by-its-id
  (t/is (= ["glm-5.2" "glm-5v-turbo"] (mapv :id (dai/models "zhipu"))))
  (t/is (= [] (dai/models "nobody")))
  (t/is (= 200000 (:contextLimit (dai/model "zhipu" "glm-5v-turbo"))))
  (t/is (nil? (dai/model "zhipu" "glm-9"))))
