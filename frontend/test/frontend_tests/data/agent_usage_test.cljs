;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.agent-usage-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.agent-tools.agentic :as agentic]
   [app.main.data.workspace.agent-usage :as au]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private round
  {:input-tokens 100 :output-tokens 10 :cache-read-tokens 0 :cache-write-tokens 0 :requests 1})

(t/deftest spend-on-each-model-is-kept-apart-however-it-is-added
  (let [main  (au/attributed round "anthropic" "claude-sonnet-5-5")
        scout (au/attributed round "anthropic" "claude-haiku-5-5")
        plain round
        total (reduce au/add-usage nil [main scout plain main])]
    (t/is (= 400 (:input-tokens total)))
    (t/is (map? (:by-model total)))
    (t/is (= 200 (get-in total [:by-model "anthropic:claude-sonnet-5-5" :input-tokens])))
    (t/is (= 100 (get-in total [:by-model "anthropic:claude-haiku-5-5" :input-tokens])))
    (t/is (= "claude-haiku-5-5" (get-in total [:by-model "anthropic:claude-haiku-5-5" :model])))))

(t/deftest the-scout-meter-adds-as-the-panel-adds
  ;; a scout's spend added to a meter that already keeps models apart must
  ;; not turn that record into something the cost estimate cannot read
  (let [file-id (uuid/next)
        state   {:current-file-id file-id
                 :ai-panel {file-id {:usage (au/attributed round "anthropic" "claude-sonnet-5-5")}}}
        after   (ptk/update (#'agentic/meter-scout-usage
                             (au/attributed round "anthropic" "claude-haiku-5-5"))
                            state)
        usage   (get-in after [:ai-panel file-id :usage])]
    (t/is (= 200 (:input-tokens usage)))
    (t/is (map? (:by-model usage)))
    (t/is (= #{"anthropic:claude-sonnet-5-5" "anthropic:claude-haiku-5-5"} (set (keys (:by-model usage)))))))
