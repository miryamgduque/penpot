;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.team-skills
  "The current team's promoted skills (see app.rpc.commands.team-skills, US #12).

  Fetched into app-db under `[:team-skills]` and merged into the agent's catalog
  by `agent-skills/full-catalog` (by name, default on), so a promoted skill shows
  as a card in every team member's Skills tab with no special-casing. This ns
  only fetches and promotes."
  (:require
   [app.main.data.workspace.user-skills :as user-skills]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- team-skills-fetched
  [skills]
  (ptk/reify ::team-skills-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :team-skills (vec skills)))))

(defn fetch-team-skills
  "Loads the team's promoted skills into app-db. Safe to emit on panel open; a
  nil `team-id` is a no-op."
  [team-id]
  (ptk/reify ::fetch-team-skills
    ptk/WatchEvent
    (watch [_ _ _]
      (if team-id
        (->> (rp/cmd! :get-team-skills {:team-id team-id})
             (rx/map team-skills-fetched))
        (rx/empty)))))

(defn promote-skill
  "Promotes the caller's personal skill (`source-id`) to `team-id` under the
  reviewed `name`/`description`, then refreshes both the team list (gains the new
  skill) and the personal list (the source is now promoted/inactive). `on-success`
  gets the created team skill; `on-error` gets a failure."
  [{:keys [team-id] :as params} {:keys [on-success on-error] :or {on-success identity on-error identity}}]
  (ptk/reify ::promote-skill
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :promote-skill params)
           (rx/mapcat
            (fn [created]
              ;; refresh the team list BEFORE closing the flow, so the new card
              ;; is already present, then refresh the personal list too
              (->> (rp/cmd! :get-team-skills {:team-id team-id})
                   (rx/mapcat (fn [skills]
                                (on-success created)
                                (rx/of (team-skills-fetched skills)
                                       (user-skills/fetch-user-skills)))))))
           (rx/catch (fn [cause]
                       (on-error cause)
                       (rx/empty)))))))
