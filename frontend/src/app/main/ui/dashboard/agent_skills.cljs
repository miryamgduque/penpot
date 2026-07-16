;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.dashboard.agent-skills
  "Team Dashboard → Sources → Agent Skills (US #12): a read-only list of the
  team's promoted skills — the destination a personal skill lands in when its
  owner promotes it. Managing them (edit / remove) is story #13."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.team-skills :as dwts]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:team-skills
  (l/derived :team-skills st/state))

(mf/defc agent-skills-page*
  [{:keys [team]}]
  (let [team-id (:id team)
        skills  (mf/deref ref:team-skills)]

    (mf/with-effect [team]
      (dom/set-html-title (tr "title.agent-skills")))

    (mf/with-effect [team-id]
      (when team-id
        (st/emit! (dwts/fetch-team-skills team-id))))

    [:section {:class (stl/css :agent-skills-page)}
     [:div {:class (stl/css :hero)}
      [:h2 {:class (stl/css :hero-title)} (tr "labels.agent-skills")]
      [:p {:class (stl/css :hero-desc)} (tr "dashboard.agent-skills.description")]]

     (if (seq skills)
       [:ul {:class (stl/css :skill-list)}
        (for [s skills]
          [:li {:key (:id s) :class (stl/css :skill-card)}
           [:div {:class (stl/css :skill-head)}
            [:span {:class (stl/css :skill-name)} (:label s)]
            [:span {:class (stl/css :skill-tag)} (:category s)]
            [:span {:class (stl/css :skill-tag)} (:reactive s)]]
           (when (seq (:description s))
             [:p {:class (stl/css :skill-desc)} (:description s)])])]

       [:div {:class (stl/css :empty)}
        (tr "dashboard.agent-skills.empty")])]))
