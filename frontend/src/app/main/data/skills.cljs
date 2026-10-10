;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.skills
  "Events for the design skills & rules stores (app + team scopes).

  Skills are markdown documents with metadata that agents working on the
  team's files inherit: kind=skill is knowledge/playbooks, kind=rule is a
  checkable constraint. App-scope rows (team-id NULL, seeded from the
  official penpot-ai-kit) apply to every team; teams manage their own rows
  and can switch inherited app skills off. File-scope skills live in the
  design files themselves."
  (:require
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- design-skills-fetched
  [data]
  (ptk/reify ::design-skills-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :design-skills data))))

(defn fetch-design-skills
  []
  (ptk/reify ::fetch-design-skills
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)]
        (->> (rp/cmd! :get-design-skills {:team-id team-id})
             (rx/map design-skills-fetched))))))

(defn create-design-skill
  "Creates a skill/rule. `:scope` is :team or :app; app rows apply to
  every team on the instance."
  [{:keys [scope] :as params}]
  (ptk/reify ::create-design-skill
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            {:keys [on-success on-error]
             :or {on-success rx/empty
                  on-error rx/throw}} (meta params)
            params  (cond-> (dissoc params :scope)
                      (= scope :team) (assoc :team-id team-id))]
        (->> (rp/cmd! :create-design-skill params)
             (rx/mapcat (fn [_]
                          (rx/concat
                           (on-success)
                           (rx/of (fetch-design-skills)))))
             (rx/catch on-error))))))

(defn update-design-skill
  [{:keys [id] :as params}]
  (assert (uuid? id))
  (ptk/reify ::update-design-skill
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success rx/empty
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :update-design-skill params)
             (rx/mapcat (fn [_]
                          (rx/concat
                           (on-success)
                           (rx/of (fetch-design-skills)))))
             (rx/catch on-error))))))

(defn delete-design-skill
  [{:keys [id] :as params}]
  (assert (uuid? id))
  (ptk/reify ::delete-design-skill
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success rx/empty
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :delete-design-skill params)
             (rx/mapcat (fn [_]
                          (rx/concat
                           (on-success)
                           (rx/of (fetch-design-skills)))))
             (rx/catch on-error))))))

(defn set-inherited-skill-enabled
  "Switches an inherited app-scope skill on/off for the current team."
  [{:keys [skill-name enabled] :as params}]
  (assert (string? skill-name))
  (assert (boolean? enabled))
  (ptk/reify ::set-inherited-skill-enabled
    ptk/WatchEvent
    (watch [_ state _]
      (let [team-id (:current-team-id state)
            {:keys [on-success on-error]
             :or {on-success rx/empty
                  on-error rx/throw}} (meta params)]
        (->> (rp/cmd! :set-inherited-skill-enabled
                      {:team-id team-id
                       :skill-name skill-name
                       :enabled enabled})
             (rx/mapcat (fn [_]
                          (rx/concat
                           (on-success)
                           (rx/of (fetch-design-skills)))))
             (rx/catch on-error))))))
