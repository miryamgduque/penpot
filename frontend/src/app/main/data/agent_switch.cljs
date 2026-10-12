;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.agent-switch
  "The account-wide agent switch (Settings > Integrations).

  It writes `:agent-disabled` to the profile props, which
  `app.main.data.agent-access` reads. Kept apart from the resolver because
  it needs the profile events, and the resolver is required by `refs`."
  (:require
   [app.main.broadcast :as mbc]
   [app.main.data.agent-access :as agent-access]
   [app.main.data.profile :as du]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- close-all-panels
  "Mark the agent panel closed in every file. Chats and the rest of each
  file's panel state stay as they are."
  [state]
  (if-let [panels (not-empty (:ai-panel state))]
    (assoc state :ai-panel
           (reduce-kv (fn [acc file-id panel]
                        (assoc acc file-id (assoc panel :open? false)))
                      {}
                      panels))
    state))

(defn apply-account-setting
  "Reflect the account switch in this tab without saving it. Switching off
  also closes every open agent panel and emits `agent-access/turned-off`,
  which stops the panel watcher."
  [disabled?]
  (ptk/reify ::apply-account-setting
    ptk/UpdateEvent
    (update [_ state]
      (cond-> (assoc-in state [:profile :props :agent-disabled] disabled?)
        disabled? (close-all-panels)))

    ptk/WatchEvent
    (watch [_ _ _]
      (if disabled?
        (rx/of (ptk/data-event agent-access/turned-off))
        (rx/empty)))))

(defn set-account-enabled
  "Save the account switch on the profile, apply it here and tell the
  user's other tabs. Provider keys, models, chats and skills are not
  touched."
  [enabled?]
  (let [disabled? (not enabled?)]
    (ptk/reify ::set-account-enabled
      ptk/WatchEvent
      (watch [_ _ _]
        (rx/of (apply-account-setting disabled?)
               (du/update-profile-props {:agent-disabled disabled?})
               (mbc/event ::account-switched {:disabled disabled?}))))))

(defn watch-account-switch
  "Apply the account switch when the user flips it in another tab."
  []
  (ptk/reify ::watch-account-switch
    ptk/WatchEvent
    (watch [_ state _]
      (let [session-id (get state :session-id)]
        (->> mbc/stream
             (rx/filter (mbc/type? ::account-switched))
             (rx/filter #(not= session-id (:id %)))
             (rx/map deref)
             (rx/map #(apply-account-setting (true? (:disabled %)))))))))
