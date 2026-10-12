;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.team-recipes
  "The current team's own recipes for the design agent (see
  app.rpc.commands.team-recipes). Fetched into app-db under `[:team-recipes]`:
  the team page lists and edits them, and the agent's instructions name the
  ones in use."
  (:require
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- team-recipes-fetched
  [recipes]
  (ptk/reify ::team-recipes-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :team-recipes (vec recipes)))))

(defn fetch-team-recipes
  "Loads the team's recipes into app-db. A nil `team-id` is a no-op."
  [team-id]
  (ptk/reify ::fetch-team-recipes
    ptk/WatchEvent
    (watch [_ _ _]
      (if team-id
        (->> (rp/cmd! :get-team-recipes {:team-id team-id})
             (rx/map team-recipes-fetched)
             ;; an instance without the feature, or a team that cannot be
             ;; read: the agent goes on with the built-in recipes
             (rx/catch (fn [_] (rx/of (team-recipes-fetched [])))))
        (rx/empty)))))

(defn fetch-community-recipes
  "Loads what the community index offers into app-db under
  `[:community-recipes]`: `{:configured :available :source :recipes}`."
  [team-id]
  (ptk/reify ::fetch-community-recipes
    ptk/WatchEvent
    (watch [_ _ _]
      (if team-id
        (->> (rp/cmd! :get-community-recipes {:team-id team-id})
             (rx/catch (fn [_] (rx/of {:configured false :available false :recipes []})))
             (rx/map (fn [found]
                       (ptk/reify ::community-recipes-fetched
                         ptk/UpdateEvent
                         (update [_ state]
                           (assoc state :community-recipes found))))))
        (rx/empty)))))

(defn import-community-recipe
  "Copies the community recipe called `name` into the team, where it waits
  disabled; then loads both lists again."
  [team-id name {:keys [on-success on-error] :or {on-success identity on-error identity}}]
  (ptk/reify ::import-community-recipe
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :import-community-recipe {:team-id team-id :name name})
           (rx/mapcat (fn [result]
                        (on-success result)
                        (rx/of (fetch-team-recipes team-id)
                               (fetch-community-recipes team-id))))
           (rx/catch (fn [cause]
                       (on-error (or (:hint (ex-data cause)) (ex-message cause)))
                       (rx/empty)))))))

(defn- then-refresh
  "Runs `command`, loads the list again, and tells the caller how it went:
  `on-error` gets the problem as the server named it."
  [command params team-id {:keys [on-success on-error] :or {on-success identity on-error identity}}]
  (->> (rp/cmd! command params)
       (rx/mapcat (fn [result]
                    (->> (rp/cmd! :get-team-recipes {:team-id team-id})
                         (rx/map (fn [recipes]
                                   (on-success result)
                                   (team-recipes-fetched recipes))))))
       (rx/catch (fn [cause]
                   (on-error (or (:hint (ex-data cause)) (ex-message cause)))
                   (rx/empty)))))

(defn save-team-recipe
  "Adds the recipe `content` (JSON) to the team, or replaces the one of its
  name. `origin` is team, saved or imported; `origin-note` says from where."
  [{:keys [team-id] :as params} callbacks]
  (ptk/reify ::save-team-recipe
    ptk/WatchEvent
    (watch [_ _ _]
      (then-refresh :save-team-recipe params team-id callbacks))))

(defn set-team-recipe-disabled
  [team-id id disabled callbacks]
  (ptk/reify ::set-team-recipe-disabled
    ptk/WatchEvent
    (watch [_ _ _]
      (then-refresh :set-team-recipe-disabled {:id id :disabled disabled} team-id callbacks))))

(defn delete-team-recipe
  [team-id id callbacks]
  (ptk/reify ::delete-team-recipe
    ptk/WatchEvent
    (watch [_ _ _]
      (then-refresh :delete-team-recipe {:id id} team-id callbacks))))
