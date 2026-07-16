;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.user-skills
  "The user's own created skills (see app.rpc.commands.profile-skills, US #9).

  Fetched into app-db under `[:user-skills]` and merged into the agent's catalog
  by `agent-skills/full-catalog`, so they render as cards, feed the router +
  `get_design_skills`, and toggle through the US #8 enable/disable machinery — no
  special-casing at the call sites. This ns only fetches and creates."
  (:require
   [app.main.data.workspace.skill-gen :as sg]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- user-skills-fetched
  [skills]
  (ptk/reify ::user-skills-fetched
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :user-skills (vec skills)))))

(defn fetch-user-skills
  "Loads the caller's created skills into app-db. Safe to emit on panel open."
  []
  (ptk/reify ::fetch-user-skills
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :get-skills {})
           (rx/map user-skills-fetched)))))

(defn create-skill
  "Persists an already-built skill map (name/label/category/reactive/trigger/
  description/body) and refetches so it appears in the catalog."
  [params]
  (ptk/reify ::create-skill
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :create-skill params)
           (rx/map (fn [_] (fetch-user-skills)))))))

(defn create-from-answers
  "The creation flow's one action: generate the skill doc from the guided
  answers (through `settings`' provider/model), persist it, and refetch so the
  new card appears. `on-success` gets the created skill (authoritative name/id);
  `on-error` gets a failure (bad generation or create). Needs a connected model."
  [settings answers {:keys [on-success on-error] :or {on-success identity on-error identity}}]
  (ptk/reify ::create-from-answers
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (sg/generate-skill settings answers)
           (rx/mapcat (fn [skill] (rp/cmd! :create-skill skill)))
           ;; Refresh the catalog BEFORE closing the flow, so the list renders
           ;; with the new skill already present rather than empty-then-populate.
           (rx/mapcat (fn [created]
                        (->> (rp/cmd! :get-skills {})
                             (rx/map (fn [skills]
                                       (on-success created)
                                       (user-skills-fetched skills))))))
           (rx/catch (fn [cause]
                       (on-error cause)
                       (rx/empty)))))))
