;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.agent-switch
  "The user's agent switches: the account one (Settings > Integrations) and
  the per-file one (workspace Main menu > Preferences).

  The account switch writes `:agent-disabled` to the profile props. A file
  setting is a row of `profile_agent_state` (see
  `app.rpc.commands.agent-state`), kept in this tab under
  `[:agent-file-state file-id]` as true or false, or `:account` when the
  file follows the account; no entry means not known yet. `app.main.data.agent-access` reads both. Kept apart
  from the resolver because it needs the profile events and the repo, and
  the resolver is required by `refs`."
  (:require
   [app.main.broadcast :as mbc]
   [app.main.data.agent-access :as agent-access]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.repo :as rp]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; --- Shared effects

(defn- close-panels-now-off
  "Mark the agent panel closed in every file where the agent is now off for
  the user. Panels in files where it stays on are left open, and chats and
  the rest of each file's panel state are kept."
  [state]
  (if-let [panels (not-empty (:ai-panel state))]
    (assoc state :ai-panel
           (reduce-kv (fn [acc file-id panel]
                        (assoc acc file-id
                               (cond-> panel
                                 (not (agent-access/agent-enabled? state file-id))
                                 (assoc :open? false))))
                      {}
                      panels))
    state))

(defn- turned-off-here
  "Emit `agent-access/turned-off` when the agent is off where the user is:
  in the current file, or for the account outside a file. It stops the
  panel watcher and any running agent turn, so no more model calls go
  out."
  [state]
  (if (agent-access/agent-enabled? state (:current-file-id state))
    (rx/empty)
    (rx/of (ptk/data-event agent-access/turned-off))))

;; --- Account switch

(defn apply-account-setting
  "Reflect the account switch in this tab without saving it. Panels close
  in every file where the agent ends up off; files the user turned the
  agent on in keep theirs."
  [disabled?]
  (ptk/reify ::apply-account-setting
    ptk/UpdateEvent
    (update [_ state]
      (-> (assoc-in state [:profile :props :agent-disabled] disabled?)
          (close-panels-now-off)))

    ptk/WatchEvent
    (watch [_ state _]
      (turned-off-here state))))

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

;; --- File switch

(defn- store-file-setting
  "Store a setting as the server holds it: nil means the file follows the
  account."
  [state file-id enabled]
  (-> (assoc-in state [:agent-file-state file-id]
                (if (nil? enabled) :account (true? enabled)))
      (close-panels-now-off)))

(defn apply-file-setting
  "Reflect a change of the user's setting for `file-id` in this tab without
  saving it. `enabled` is true, false, or nil when the file follows the
  account."
  [file-id enabled]
  (ptk/reify ::apply-file-setting
    ptk/UpdateEvent
    (update [_ state]
      (store-file-setting state file-id enabled))

    ptk/WatchEvent
    (watch [_ state _]
      (turned-off-here state))))

(defn- file-setting-loaded
  "Store the setting loaded when a file opens. Unlike a change, it stops
  nothing: the agent is off in a file until its setting is known, so no
  agent work has started there, and a turn still running from the
  previous file is not this file's to stop."
  [file-id enabled]
  (ptk/reify ::file-setting-loaded
    ptk/UpdateEvent
    (update [_ state]
      (store-file-setting state file-id enabled))))

(defn fetch-file-setting
  "Load the user's setting for `file-id`. On failure the setting stays
  unknown, so the agent stays off in that file."
  [file-id]
  (ptk/reify ::fetch-file-setting
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-agent-file-state {:file-id file-id})
           (rx/map #(file-setting-loaded file-id (:enabled %)))
           (rx/catch (fn [cause]
                       (.warn js/console "agent file setting not loaded" cause)
                       (rx/empty)))))))

(defn stored-file-value
  "What to store for a file when the user turns the agent on or off there:
  nil when the choice matches the account setting (the file follows the
  account again), else the choice. Only true exceptions are kept."
  [state enabled?]
  (when (not= (boolean enabled?) (agent-access/account-enabled? state))
    (boolean enabled?)))

(defn- restore-file-setting
  "Put back the setting `file-id` had before a change that failed to save.
  `previous` is the raw entry: true, false, `:account` or nil (unknown)."
  [file-id previous]
  (ptk/reify ::restore-file-setting
    ptk/UpdateEvent
    (update [_ state]
      (-> (if (nil? previous)
            (update state :agent-file-state dissoc file-id)
            (assoc-in state [:agent-file-state file-id] previous))
          (close-panels-now-off)))

    ptk/WatchEvent
    (watch [_ state _]
      (turned-off-here state))))

(defn set-file-enabled
  "Turn the agent on or off for the user in `file-id`, save it and tell
  the user's other tabs. Applied at once; a failed save puts the previous
  setting back."
  [file-id enabled?]
  (ptk/reify ::set-file-enabled
    ptk/WatchEvent
    (watch [_ state _]
      (let [value    (stored-file-value state enabled?)
            previous (agent-access/file-override state file-id)]
        (rx/concat
         (rx/of (apply-file-setting file-id value))
         (->> (rp/cmd! :set-agent-file-state {:file-id file-id :enabled value})
              (rx/map (fn [_]
                        (mbc/event ::file-switched {:file-id file-id
                                                    :enabled value})))
              (rx/catch (fn [_]
                          (rx/of (ntf/error (tr "errors.generic"))
                                 (restore-file-setting file-id previous))))))))))

;; --- Files the user turned the agent on in (Settings > Integrations)

(defn fetch-enabled-file-count
  "Load how many files, still open to the user, they turned the agent on
  in. Settings shows it while the account switch is off. A count from an
  earlier visit is dropped first so it never shows stale."
  []
  (ptk/reify ::fetch-enabled-file-count
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :agent-enabled-file-count))

    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-agent-enabled-file-count {})
           (rx/map (fn [{:keys [count]}]
                     (ptk/reify ::enabled-file-count-fetched
                       ptk/UpdateEvent
                       (update [_ state]
                         (assoc state :agent-enabled-file-count count)))))
           (rx/catch (fn [_] (rx/empty)))))))

(defn apply-enabled-files-cleared
  "Reflect in this tab that the user turned the agent off in every file
  they had turned it on in: those files follow the account again."
  []
  (ptk/reify ::apply-enabled-files-cleared
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc :agent-enabled-file-count 0)
          (update :agent-file-state
                  (fn [files]
                    (into {}
                          (map (fn [[file-id value]]
                                 [file-id (if (true? value) :account value)]))
                          files)))
          (close-panels-now-off)))

    ptk/WatchEvent
    (watch [_ state _]
      (turned-off-here state))))

(defn disable-in-enabled-files
  "Turn the agent off in every file the user turned it on in, and tell the
  user's other tabs."
  []
  (ptk/reify ::disable-in-enabled-files
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :disable-agent-in-enabled-files {})
           (rx/mapcat (fn [_]
                        (rx/of (apply-enabled-files-cleared)
                               (mbc/event ::enabled-files-cleared {}))))
           (rx/catch (fn [_]
                       (rx/of (ntf/error (tr "errors.generic"))
                              (fetch-enabled-file-count))))))))

;; --- Other tabs

(defn watch-agent-switches
  "Apply the agent switches when the user flips them in another tab."
  []
  (ptk/reify ::watch-agent-switches
    ptk/WatchEvent
    (watch [_ state _]
      (let [session-id (get state :session-id)
            others     (->> mbc/stream
                            (rx/filter #(not= session-id (:id %))))]
        (rx/merge
         (->> others
              (rx/filter (mbc/type? ::account-switched))
              (rx/map deref)
              (rx/map #(apply-account-setting (true? (:disabled %)))))
         (->> others
              (rx/filter (mbc/type? ::file-switched))
              (rx/map deref)
              (rx/map #(apply-file-setting (:file-id %) (:enabled %))))
         (->> others
              (rx/filter (mbc/type? ::enabled-files-cleared))
              (rx/map (fn [_] (apply-enabled-files-cleared)))))))))
