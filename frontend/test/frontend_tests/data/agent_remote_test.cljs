;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.agent-remote-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.workspace.agent-remote :as ar]
   [app.main.data.workspace.agent-tools :as at]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.agent-opt-out :as opt-out]
   [frontend-tests.helpers.mock :as mock]))

(defn- payload
  [settings user-msg system]
  (js->clj (js/JSON.parse (ar/turn-payload settings user-msg system)) :keywordize-keys true))

(t/deftest turn-payload-carries-the-conversation-and-the-message
  (let [session (uuid/next)
        p       (payload {:provider "anthropic"
                          :model "claude-sonnet-5"
                          :service {:session-id session :file-id (uuid/next)}}
                         {:role :user :text "make a card" :context {:page "Home"}}
                         "SYSTEM")]
    (t/is (= (str session) (:sessionId p)))
    (t/is (= "claude-sonnet-5" (:model p)))
    (t/is (= "SYSTEM" (:system p)))
    ;; the design context rides with the text, as in the browser loop
    (t/is (re-find #"Current design context" (:text p)))
    (t/is (re-find #"make a card$" (:text p)))
    (t/is (= [] (:images p)))))

(t/deftest turn-payload-declares-every-tool-with-its-own-name-and-schema
  (let [p     (payload {:service {:session-id (uuid/next)}} {:role :user :text "hi"} "S")
        tools (:tools p)]
    ;; all but the skills tool: the harness serves the skills itself
    (t/is (= (remove #{"get_design_skills"} (map :name at/tool-specs)) (map :name tools)))
    (t/is (every? #(= "object" (get-in % [:inputSchema :type])) tools))
    (t/is (= (get-in (first at/tool-specs) [:input-schema :required])
             (get-in (first tools) [:inputSchema :required])))))

(t/deftest turn-payload-carries-attached-images-as-raw-base64
  (let [p (payload {:service {:session-id (uuid/next)}}
                   {:role :user :text "look" :images [{:mtype "image/png" :data "AAAA"}]}
                   "S")]
    (t/is (= [{:mediaType "image/png" :data "AAAA"}] (:images p)))))

(t/deftest turn-payload-carries-the-enabled-skills
  (let [skills [{:name "penpot-build-screen" :description "Build a screen" :body "STEP ONE"}]
        p      (payload {:service {:session-id (uuid/next) :skills skills}} {:role :user :text "hi"} "S")]
    (t/is (= skills (:skills p)))
    (t/is (= [] (:skills (payload {:service {:session-id (uuid/next)}} {:role :user :text "hi"} "S"))))))

(t/deftest a-connected-service-tool-is-shown-with-the-service-name
  (t/is (= "notes: echo" (ar/tool-display-name "mcp__notes__echo")))
  (t/is (= "tracker: create__issue" (ar/tool-display-name "mcp__tracker__create__issue")))
  (t/is (= "create_shape" (ar/tool-display-name "create_shape"))))

(t/deftest a-service-left-out-of-the-turn-is-said-once
  (let [ev     {:type "mcp"
                :servers ["notes"]
                :refused [{:name "inside" :reason "the address is private or blocked"}]}
        events (ar/refused-service-events ev)]
    (t/is (= [{:kind :tool
               :name "inside"
               :status :error
               :detail "This connected service was left out: the address is private or blocked"}]
             (mapv #(select-keys % [:kind :name :status :detail]) events)))
    ;; the next turn carries the same refusal: it is not repeated
    (t/is (= [] (ar/refused-service-events ev)))
    (t/is (= [] (ar/refused-service-events {:type "mcp" :servers ["notes"] :refused []})))))

;; --- What the service says about how a turn went

(def ^:private file-id (uuid/next))

(defn- turn-events
  "The panel events a turn yields when the service streams `events`."
  [events]
  (let [out (atom [])]
    (with-redefs [cf/flags (conj cf/flags :design-agent)
                  rp/cmd!  (mock/stub (fn [_ _]
                                        (rx/from (mapv (fn [ev]
                                                         #js {:type "event" :data (js/JSON.stringify (clj->js ev))})
                                                       events))))]
      (opt-out/with-store-state
        {:profile {:id (uuid/next) :props {}} :current-file-id file-id
         :agent-file-state {file-id true}}
        #(rx/sub! (ar/run-turn {:provider "anthropic" :model "claude-sonnet-5"
                                :service {:session-id (uuid/next) :file-id file-id}}
                               [{:role :user :text "build it"}]
                               "SYSTEM")
                  (fn [ev] (swap! out conj ev)))))
    @out))

(t/deftest a-turn-the-model-could-not-finish-says-so
  (let [events (turn-events [{:type "text-delta" :text "Building…"}
                             {:type "done" :reason "max-tokens"}])
        kinds  (mapv :kind events)]
    ;; the note comes before the turn ends, and says what happened
    (t/is (= [:assistant-delta :note :done] kinds))
    (t/is (re-find #"ran out of room" (:text (nth events 1))))
    (t/is (= "max-tokens" (:reason (last events))))))

(t/deftest a-turn-that-finished-says-nothing-more
  (t/is (= [:assistant-delta :done]
           (mapv :kind (turn-events [{:type "text-delta" :text "Done."}
                                     {:type "done" :reason "completed"}])))))

(t/deftest the-services-warning-before-a-turn-is-shown
  (let [events (turn-events [{:type "warning" :message "the context is tight"}
                             {:type "done" :reason "completed"}])]
    (t/is (= [:note :done] (mapv :kind events)))
    (t/is (re-find #"the context is tight" (:text (first events))))))

(t/deftest a-side-jobs-usage-names-the-model-it-ran-on
  (let [[main side] (filterv #(= :usage (:kind %))
                             (turn-events [{:type "usage" :usage {:input 10}}
                                           {:type "usage" :usage {:input 5} :side "helper"
                                            :provider "anthropic" :model "claude-haiku-5-5"}
                                           {:type "done" :reason "completed"}]))]
    (t/is (= #{"anthropic:claude-sonnet-5"} (set (keys (:by-model (:usage main))))))
    (t/is (= #{"anthropic:claude-haiku-5-5"} (set (keys (:by-model (:usage side))))))))
