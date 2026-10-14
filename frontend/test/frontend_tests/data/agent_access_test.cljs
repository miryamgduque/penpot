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
   [app.main.data.agent-switch :as agent-switch]
   [app.main.data.workspace.ai-panel :as ai-panel]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private file-id (uuid/next))

(def ^:private user-state
  "A state carrying the context later layers read: profile props, the
  current file, and that file's setting, loaded as following the account."
  {:profile {:id (uuid/next) :props {}}
   :current-file-id file-id
   :agent-file-state {file-id :account}})

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

;; --- Account layer (#86)

(defn- with-account
  "`user-state` with the account switch stored as `disabled?`; `:unset`
  leaves the prop out, as for a profile that never touched the switch."
  [disabled?]
  (if (= :unset disabled?)
    user-state
    (assoc-in user-state [:profile :props :agent-disabled] disabled?)))

(t/deftest account-enabled-reads-the-profile-prop
  (t/is (true? (agent-access/account-enabled? {})))
  (t/is (true? (agent-access/account-enabled? (with-account :unset))))
  (t/is (true? (agent-access/account-enabled? (with-account false))))
  (t/is (false? (agent-access/account-enabled? (with-account true)))))

(t/deftest account-off-hides-the-agent-at-every-scope
  (with-flag true
    (fn []
      (let [state (with-account true)]
        (t/is (false? (agent-access/agent-enabled? state)))
        (t/is (false? (agent-access/agent-enabled? state nil)))
        (t/is (false? (agent-access/agent-enabled? state file-id)))))))

(t/deftest account-default-is-enabled
  (with-flag true
    (fn []
      (t/is (true? (agent-access/agent-enabled? (with-account :unset))))
      (t/is (true? (agent-access/agent-enabled? (with-account :unset) file-id)))
      (t/is (true? (agent-access/agent-enabled? (with-account false) file-id))))))

(t/deftest instance-off-wins-over-account-on
  (with-flag false
    (fn []
      (t/is (false? (agent-access/agent-enabled? (with-account false))))
      (t/is (false? (agent-access/agent-enabled? (with-account false) file-id))))))

(t/deftest agent-routes-are-guarded-by-the-account
  (with-flag true
    (fn []
      (doseq [route agent-access/agent-routes]
        (t/is (true? (agent-access/route-allowed? (with-account false) route)))
        (t/is (false? (agent-access/route-allowed? (with-account true) route))))
      (t/is (= #{:dashboard-skills :dashboard-agent-skills}
               agent-access/agent-routes))
      (t/is (true? (agent-access/route-allowed? (with-account true)
                                                :dashboard-recent))))))

(t/deftest panel-stays-closed-while-the-account-is-off
  (with-flag true
    (fn []
      (let [state (ptk/update (ai-panel/toggle-panel) (with-account true))]
        (t/is (false? (get-in state [:ai-panel file-id :open?])))))))

(t/deftest panel-open-needs-the-agent-on
  (with-flag true
    (fn []
      (let [open (assoc-in (with-account false) [:ai-panel file-id :open?] true)]
        (t/is (true? (agent-access/panel-open? open)))
        ;; a panel left open before switching off never shows again
        (t/is (false? (agent-access/panel-open?
                       (assoc-in open [:profile :props :agent-disabled] true))))
        (t/is (nil? (agent-access/panel-open? (dissoc open :current-file-id))))))))

(t/deftest switching-off-closes-panels-and-keeps-chats
  (let [other-id (uuid/next)
        messages [{:role "user" :text "hi"}]
        state    (-> (with-account false)
                     (assoc-in [:ai-panel file-id] {:open? true :messages messages})
                     (assoc-in [:ai-panel other-id] {:open? true}))
        off      (ptk/update (agent-switch/apply-account-setting true) state)
        on       (ptk/update (agent-switch/apply-account-setting false) off)]
    (t/is (true? (get-in off [:profile :props :agent-disabled])))
    (t/is (false? (get-in off [:ai-panel file-id :open?])))
    (t/is (false? (get-in off [:ai-panel other-id :open?])))
    (t/is (= messages (get-in off [:ai-panel file-id :messages])))
    ;; switching back on does not reopen anything
    (t/is (false? (get-in on [:profile :props :agent-disabled])))
    (t/is (false? (get-in on [:ai-panel file-id :open?])))
    (t/is (= messages (get-in on [:ai-panel file-id :messages])))))

(t/deftest switching-off-without-panels-leaves-state-alone
  (let [off (ptk/update (agent-switch/apply-account-setting true) user-state)]
    (t/is (nil? (:ai-panel off)))))

;; --- Write capability (#150)
;;
;; Whether the person can change the file, asked by every agent surface that
;; offers a change. The permission maps are shaped as the backend sends them
;; with the team (`app.rpc.permissions/assign-role-flags`).

(def ^:private viewer-permissions {:is-owner false :is-admin false :can-edit false :can-read true})
(def ^:private editor-permissions {:is-owner false :is-admin false :can-edit true :can-read true})
(def ^:private admin-permissions  {:is-owner false :is-admin true :can-edit true :can-read true})
(def ^:private owner-permissions  {:is-owner true :is-admin true :can-edit true :can-read true})

(defn- as-member
  [permissions]
  (assoc user-state :permissions permissions))

(t/deftest a-viewer-cannot-write
  (t/is (false? (agent-access/can-write? (as-member viewer-permissions)))))

(t/deftest editors-admins-and-owners-can-write
  (doseq [permissions [editor-permissions admin-permissions owner-permissions]]
    (t/is (true? (agent-access/can-write? (as-member permissions))) (pr-str permissions))))

(t/deftest nothing-loaded-means-no-write
  (t/is (false? (agent-access/can-write? user-state)))
  (t/is (false? (agent-access/can-write? {}))))

(t/deftest write-capability-is-the-capability-not-a-role
  ;; a way in that only lets someone view, whatever else it carries: it
  ;; answers false without the check knowing its name
  (t/is (false? (agent-access/can-write? (as-member {:can-edit false :can-read true}))))
  (t/is (false? (agent-access/can-write? (as-member {:role :share-link :can-edit false :can-read true}))))
  ;; and an unexpected value is not taken for permission
  (t/is (false? (agent-access/can-write? (as-member {:can-edit "yes"})))))

(t/deftest write-capability-and-the-agent-switch-are-independent
  (with-flag true
    (fn []
      (let [viewer (as-member viewer-permissions)]
        (t/is (true? (agent-access/agent-enabled? viewer file-id)) "a viewer still has the agent")
        (t/is (false? (agent-access/can-write? viewer))))))
  (with-flag false
    (fn []
      (let [editor (as-member editor-permissions)]
        (t/is (false? (agent-access/agent-enabled? editor file-id)))
        (t/is (true? (agent-access/can-write? editor)) "the agent being off says nothing about writing")))))

(t/deftest a-preview-does-not-take-write-away
  (t/is (true? (agent-access/can-write?
                (-> (as-member editor-permissions)
                    (assoc :workspace-global {:read-only? true :preview-id (uuid/next)}))))))
