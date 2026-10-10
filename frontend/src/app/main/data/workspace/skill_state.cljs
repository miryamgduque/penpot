;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.skill-state
  "Per-user on/off state for the agent's built-in skills (see
  app.rpc.commands.skill-state).

  Two private scopes per user: an account default (a skill's row with a NULL
  file) and a per-file override. Both are private to the user — a file override
  is never shared with other collaborators (contrast the team-level
  design_skill_override). State lands under

    [:skill-state {:account {skill-name enabled}
                   :files   {file-id {skill-name enabled}}}]

  and the effective on/off is resolved in `agent-skills/resolve-enabled`
  (built-in default → account → per-file, per-file wins)."
  (:require
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; --- Read accessors (consumed by agent-skills resolution)

(defn account-states
  "Account-default overrides (skill-name → enabled) for the current user, or nil
  when nothing has been fetched/stored yet."
  [state]
  (get-in state [:skill-state :account]))

(defn file-states
  "This file's per-user overrides (skill-name → enabled), or nil."
  [state file-id]
  (get-in state [:skill-state :files file-id]))

;; --- Fetch

(defn- skill-states-fetched
  "Splits the backend rows (account rows have a NULL file-id, the rest belong to
  `file-id`) into the two stored maps. The account map is replaced wholesale;
  this file's map is set — even when empty — so we know it is loaded."
  [file-id rows]
  (ptk/reify ::skill-states-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [account (into {} (keep (fn [{:keys [skill file-id enabled]}]
                                     (when (nil? file-id) [skill enabled])))
                          rows)
            fmap    (into {} (keep (fn [{:keys [skill file-id enabled]}]
                                     (when (some? file-id) [skill enabled])))
                          rows)]
        (cond-> (assoc-in state [:skill-state :account] account)
          file-id (assoc-in [:skill-state :files file-id] fmap))))))

(defn fetch-skill-states
  "Loads the user's account defaults + the current file's overrides into app
  state. Safe to emit on panel open — the router reads the resolved state."
  []
  (ptk/reify ::fetch-skill-states
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :get-skill-states {:file-id file-id})
             (rx/map #(skill-states-fetched file-id %)))))))

;; --- Toggle

(defn set-skill-enabled
  "Turns a skill on/off for the current file (a per-user, per-file override) and
  persists it. Optimistic: app state flips immediately so the router reflects
  the toggle without waiting on the round-trip, then the authoritative state is
  re-fetched (also on error, to revert an optimistic flip that failed)."
  [skill enabled]
  (ptk/reify ::set-skill-enabled
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (assoc-in state [:skill-state :files file-id skill] enabled)
        state))

    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :set-skill-enabled {:skill skill :enabled enabled :file-id file-id})
             (rx/map (fn [_] (fetch-skill-states)))
             (rx/catch (fn [_] (rx/of (fetch-skill-states)))))))))
