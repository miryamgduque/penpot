;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.dashboard.agent-skills
  "Team Dashboard → Sources → Agent Skills (US #13): consult the skills promoted
  to this team. A list of team-promoted skills (name, description, category,
  reactive behavior, who promoted it) that opens into a read-only detail. The
  dashboard has no open file, so the detail carries no file-dependent actions
  (no test/toggle). Promoting is US #12; editing is its own later story."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.team-skills :as dwts]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:team-skills
  (l/derived :team-skills st/state))

(def ^:private reactive-label
  {"on-demand" "On-demand" "observer" "Observer"})

(mf/defc skill-tags*
  {::mf/private true}
  [{:keys [category reactive promoted-by-name]}]
  [:div {:class (stl/css :skill-tags)}
   [:span {:class (stl/css :skill-tag)} category]
   [:span {:class (stl/css :skill-tag)} (get reactive-label reactive reactive)]
   (when (seq promoted-by-name)
     [:span {:class (stl/css :skill-by)} (tr "dashboard.agent-skills.by" promoted-by-name)])])

(mf/defc skill-card*
  {::mf/private true}
  [{:keys [skill on-open]}]
  (let [{:keys [label description category reactive promoted-by-name]} skill]
    [:button {:class (stl/css :skill-card) :type "button" :on-click on-open}
     [:span {:class (stl/css :skill-name)} label]
     (when (seq description)
       [:span {:class (stl/css :skill-desc)} description])
     [:> skill-tags* {:category category :reactive reactive :promoted-by-name promoted-by-name}]]))

(mf/defc skill-detail*
  {::mf/private true}
  [{:keys [skill on-back]}]
  (let [{:keys [label category reactive promoted-by-name trigger description]} skill]
    [:div {:class (stl/css :detail)}
     [:div {:class (stl/css :detail-head)}
      [:button {:class (stl/css :detail-back) :type "button" :on-click on-back
                :aria-label "Back"}
       [:> icon* {:icon-id i/arrow-left}]]
      [:h2 {:class (stl/css :detail-name)} label]]
     [:> skill-tags* {:category category :reactive reactive :promoted-by-name promoted-by-name}]

     (when (seq trigger)
       [:*
        [:div {:class (stl/css :detail-label)} (tr "dashboard.agent-skills.trigger")]
        [:p {:class (stl/css :detail-trigger)} (dm/str "“" trigger "”")]])

     [:div {:class (stl/css :detail-label)} (tr "dashboard.agent-skills.what")]
     [:p {:class (stl/css :detail-what)} description]

     ;; the dashboard has no open file — say so instead of offering a Test action
     [:p {:class (stl/css :detail-note)} (tr "dashboard.agent-skills.no-test")]]))

(mf/defc agent-skills-page*
  [{:keys [team]}]
  (let [team-id   (:id team)
        skills    (mf/deref ref:team-skills)
        selected* (mf/use-state nil)
        selected  (deref selected*)
        skill     (when selected (some #(when (= selected (:id %)) %) skills))]

    (mf/with-effect [team]
      (dom/set-html-title (tr "title.agent-skills")))

    (mf/with-effect [team-id]
      (when team-id
        (st/emit! (dwts/fetch-team-skills team-id))))

    [:section {:class (stl/css :agent-skills-page)}
     (cond
       skill
       [:> skill-detail* {:skill skill :on-back #(reset! selected* nil)}]

       (seq skills)
       [:*
        [:div {:class (stl/css :hero)}
         [:h2 {:class (stl/css :hero-title)} (tr "labels.agent-skills")]
         [:p {:class (stl/css :hero-desc)} (tr "dashboard.agent-skills.description")]]
        [:ul {:class (stl/css :skill-list)}
         (for [s skills]
           [:li {:key (:id s)}
            [:> skill-card* {:skill s :on-open #(reset! selected* (:id s))}]])]]

       :else
       [:div {:class (stl/css :empty)}
        [:div {:class (stl/css :empty-icon)}
         [:> icon* {:icon-id i/bot-message-square :size "l"}]]
        [:div {:class (stl/css :empty-title)} (tr "dashboard.agent-skills.empty-title")]
        [:p {:class (stl/css :empty-desc)} (tr "dashboard.agent-skills.empty-desc")]])]))
