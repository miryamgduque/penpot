;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.agent-access-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.agent-access :as agent-access]
   [app.main.data.workspace.ai-panel :as ai-panel]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private file-id (uuid/next))

(def ^:private user-state
  "A state carrying the context later layers read: profile props and the
  current file."
  {:profile {:id (uuid/next) :props {}}
   :current-file-id file-id})

(defn- with-flag
  [on? f]
  (with-redefs [cf/flags (if on?
                           (conj cf/flags :design-agent)
                           (disj cf/flags :design-agent))]
    (f)))

(t/deftest instance-enabled-follows-the-flag
  (with-flag true #(t/is (true? (agent-access/instance-enabled?))))
  (with-flag false #(t/is (false? (agent-access/instance-enabled?)))))

(t/deftest agent-enabled-when-flag-is-on
  (with-flag true
    (fn []
      (t/is (true? (agent-access/agent-enabled? {})))
      (t/is (true? (agent-access/agent-enabled? user-state)))
      (t/is (true? (agent-access/agent-enabled? user-state file-id)))
      (t/is (true? (agent-access/agent-enabled? user-state nil))))))

(t/deftest agent-disabled-when-flag-is-off
  (with-flag false
    (fn []
      (t/is (false? (agent-access/agent-enabled? {})))
      (t/is (false? (agent-access/agent-enabled? user-state)))
      (t/is (false? (agent-access/agent-enabled? user-state file-id)))
      (t/is (false? (agent-access/agent-enabled? user-state nil))))))

(t/deftest panel-opens-only-while-the-agent-is-on
  (let [open? #(get-in % [:ai-panel file-id :open?])]
    (with-flag true
      #(t/is (true? (open? (ptk/update (ai-panel/toggle-panel) user-state)))))
    (with-flag false
      #(t/is (false? (open? (ptk/update (ai-panel/toggle-panel) user-state)))))))
