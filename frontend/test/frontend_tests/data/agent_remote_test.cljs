;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.agent-remote-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.agent-remote :as ar]
   [app.main.data.workspace.agent-tools :as at]
   [cljs.test :as t :include-macros true]))

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
