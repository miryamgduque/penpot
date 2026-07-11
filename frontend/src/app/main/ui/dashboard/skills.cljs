;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.dashboard.skills
  "The Skills & Rules dashboard page: CRUD over the design skills that
  agents inherit when working on the team's files. Two sections: the
  team's own set and the app-level set (seeded from penpot-ai-kit) that
  every team inherits and can switch off per entry."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.skills :as dsk]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.dashboard.team :refer [header*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private menu-icon
  (deprecated-icon/icon-xref :menu (stl/css :menu-icon)))

(def ^:private ref:design-skills
  (l/derived :design-skills st/state))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SKILL MODAL (create + edit)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:skill-form
  [:map {:title "SkillForm"}
   [:name [:string {:min 1 :max 250}]]
   [:kind ::sm/text]
   [:enforcement ::sm/text]
   [:is-mandatory {:optional true} :boolean]
   [:trigger-on {:optional true} :string]
   [:description {:optional true} [:string {:max 2000}]]
   [:body {:optional true} :string]])

(defn- kind-options
  []
  [{:label (tr "dashboard.skills.kind.skill") :value "skill"}
   {:label (tr "dashboard.skills.kind.rule") :value "rule"}])

(defn- enforcement-options
  []
  [{:label (tr "dashboard.skills.enforcement.advisory") :value "advisory"}
   {:label (tr "dashboard.skills.enforcement.triggered") :value "triggered"}
   {:label (tr "dashboard.skills.enforcement.enforced") :value "enforced"}])

(defn- trigger-options
  []
  [{:label "—" :value ""}
   {:label "fill-change" :value "fill-change"}
   {:label "shape-new" :value "shape-new"}
   {:label "rename" :value "rename"}
   {:label "selection-change" :value "selection-change"}
   {:label "content-save" :value "content-save"}])

(mf/defc skill-modal
  {::mf/register modal/components
   ::mf/register-as :design-skill}
  [{:keys [skill scope]}]
  (let [initial (mf/with-memo []
                  (if skill
                    (-> (select-keys skill [:id :name :kind :enforcement :is-mandatory
                                            :trigger-on :description :body])
                        (update :trigger-on #(or % ""))
                        (update :description #(or % ""))
                        (update :body #(or % "")))
                    {:name ""
                     :kind "skill"
                     :enforcement "advisory"
                     :is-mandatory false
                     :trigger-on ""
                     :description ""
                     :body ""}))
        form    (fm/use-form :schema schema:skill-form
                             :initial initial)

        on-success
        (mf/use-fn
         (fn [_]
           (rx/of (ntf/success (tr "dashboard.skills.save-success"))
                  (modal/hide))))

        on-submit
        (mf/use-fn
         (mf/deps scope)
         (fn [form]
           (let [cdata  (:clean-data @form)
                 params {:name (:name cdata)
                         :kind (:kind cdata)
                         :enforcement (:enforcement cdata)
                         :is-mandatory (boolean (:is-mandatory cdata))
                         :trigger-on (not-empty (:trigger-on cdata))
                         :description (or (:description cdata) "")
                         :body (or (:body cdata) "")}
                 mdata  {:on-success on-success}]
             (if-let [id (:id cdata)]
               (st/emit! (dsk/update-design-skill (with-meta (assoc params :id id) mdata)))
               (st/emit! (dsk/create-design-skill (with-meta (assoc params :scope scope) mdata)))))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:& fm/form {:form form :on-submit on-submit}
       [:div {:class (stl/css :modal-header)}
        [:h2 {:class (stl/css :modal-title)}
         (cond
           (some? skill) (tr "modals.edit-design-skill.title")
           (= scope :app) (tr "modals.create-design-skill.title-app")
           :else (tr "modals.create-design-skill.title-team"))]
        [:button {:class (stl/css :modal-close-btn)
                  :on-click modal/hide!} deprecated-icon/close]]

       [:div {:class (stl/css :modal-content)}
        [:div {:class (stl/css :fields-row)}
         [:& fm/input {:type "text"
                       :auto-focus? true
                       :form form
                       :name :name
                       :label (tr "dashboard.skills.form.name")
                       :placeholder "token-only-colors"}]]
        [:div {:class (stl/css :fields-pair)}
         [:div {:class (stl/css :fields-row)}
          [:div {:class (stl/css :select-title)} (tr "dashboard.skills.form.kind")]
          [:& fm/select {:options (kind-options)
                         :default "skill"
                         :name :kind}]]
         [:div {:class (stl/css :fields-row)}
          [:div {:class (stl/css :select-title)} (tr "dashboard.skills.form.enforcement")]
          [:& fm/select {:options (enforcement-options)
                         :default "advisory"
                         :name :enforcement}]]]
        [:div {:class (stl/css :fields-pair)}
         [:div {:class (stl/css :fields-row)}
          [:div {:class (stl/css :select-title)} (tr "dashboard.skills.form.trigger")]
          [:& fm/select {:options (trigger-options)
                         :default ""
                         :name :trigger-on}]]
         [:div {:class (stl/css :fields-row :fields-row-checkbox)}
          [:& fm/input {:type "checkbox"
                        :form form
                        :name :is-mandatory
                        :label (tr "dashboard.skills.form.mandatory")}]
          [:div {:class (stl/css :hint)} (tr "dashboard.skills.form.mandatory-hint")]]]
        [:div {:class (stl/css :fields-row)}
         [:& fm/input {:type "text"
                       :form form
                       :name :description
                       :label (tr "dashboard.skills.form.description")
                       :placeholder (tr "dashboard.skills.form.description-hint")}]]
        [:div {:class (stl/css :fields-row)}
         [:& fm/textarea {:form form
                          :name :body
                          :rows 10
                          :label (tr "dashboard.skills.form.body")
                          :placeholder (tr "dashboard.skills.form.body-hint")}]]]

       [:div {:class (stl/css :modal-footer)}
        [:div {:class (stl/css :action-buttons)}
         [:input {:class (stl/css :cancel-button)
                  :type "button"
                  :value (tr "labels.cancel")
                  :on-click modal/hide!}]
         [:> fm/submit-button*
          {:label (if skill
                    (tr "modals.edit-design-skill.submit-label")
                    (tr "modals.create-design-skill.submit-label"))}]]]]]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LISTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc skill-actions*
  {::mf/private true}
  [{:keys [on-edit on-delete can-edit]}]
  (let [show?   (mf/use-state false)
        on-show (mf/use-fn #(reset! show? true))
        on-hide (mf/use-fn #(reset! show? false))]
    (when can-edit
      [:*
       [:button {:class (stl/css :menu-btn)
                 :on-click on-show}
        menu-icon]
       [:& dropdown {:show @show? :on-close on-hide :dropdown-id "skill-actions"}
        [:ul {:class (stl/css :skill-actions-dropdown)}
         [:li {:on-click on-edit
               :class (stl/css :skill-dropdown-item)} (tr "labels.edit")]
         [:li {:on-click on-delete
               :class (stl/css :skill-dropdown-item)} (tr "labels.delete")]]]])))

(mf/defc skill-item*
  {::mf/private true}
  [{:keys [skill scope can-edit enabled on-toggle]}]
  (let [on-edit
        (mf/use-fn
         (mf/deps skill scope)
         (fn []
           (st/emit! (modal/show :design-skill {:skill skill :scope scope}))))

        on-delete-accepted
        (mf/use-fn
         (mf/deps skill)
         (fn []
           (st/emit! (dsk/delete-design-skill {:id (:id skill)}))))

        on-delete
        (mf/use-fn
         (mf/deps on-delete-accepted)
         (fn []
           (st/emit! (modal/show {:type :confirm
                                  :title (tr "modals.delete-design-skill.title")
                                  :message (tr "modals.delete-design-skill.message")
                                  :accept-label (tr "labels.delete")
                                  :on-accept on-delete-accepted}))))]

    [:div {:class (stl/css :table-row :skill-row)}
     [:label {:class (stl/css :skill-toggle)
              :title (if (:is-mandatory skill)
                       (tr "dashboard.skills.mandatory-toggle-hint")
                       (tr "dashboard.skills.toggle-hint"))}
      [:input {:type "checkbox"
               :checked (boolean enabled)
               :disabled (or (:is-mandatory skill) (not can-edit))
               :on-change #(on-toggle skill (not enabled))}]]
     [:div {:class (stl/css :skill-main)}
      [:div {:class (stl/css :skill-name)} (:name skill)]
      [:div {:class (stl/css :skill-desc)} (:description skill)]]
     [:div {:class (stl/css :skill-badges)}
      [:span {:class (stl/css :badge)} (:kind skill)]
      [:span {:class (stl/css :badge :badge-enforcement)} (:enforcement skill)]
      (when (:is-mandatory skill)
        [:span {:class (stl/css :badge :badge-mandatory)} (tr "dashboard.skills.form.mandatory")])]
     [:div {:class (stl/css :actions)}
      [:> skill-actions*
       {:on-edit on-edit
        :on-delete on-delete
        :can-edit can-edit}]]]))

(mf/defc skills-section*
  {::mf/private true}
  [{:keys [title description scope skills can-edit enabled-fn on-toggle]}]
  [:div {:class (stl/css :skills-section)}
   [:div {:class (stl/css :section-head)}
    [:div
     [:h2 {:class (stl/css :section-title)} title]
     [:p {:class (stl/css :section-desc)} description]]
    (when can-edit
      [:button {:class (stl/css :create-btn)
                :on-click #(st/emit! (modal/show :design-skill {:scope scope}))}
       (tr "dashboard.skills.create")])]
   (if (empty? skills)
     [:div {:class (stl/css :skills-empty)}
      [:div (tr "dashboard.skills.empty")]]
     [:div {:class (stl/css :table-rows)}
      (for [skill skills]
        [:> skill-item*
         {:key (dm/str (:id skill))
          :skill skill
          :scope scope
          :can-edit can-edit
          :enabled (enabled-fn skill)
          :on-toggle on-toggle}])])])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PAGE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc skills-page*
  [{:keys [team]}]
  (let [data        (mf/deref ref:design-skills)
        permissions (:permissions team)
        can-edit    (boolean (:can-edit permissions))
        overrides   (set (:overrides data))

        on-toggle-team
        (mf/use-fn
         (fn [skill enabled]
           (st/emit! (dsk/update-design-skill {:id (:id skill) :is-enabled enabled}))))

        on-toggle-app
        (mf/use-fn
         (fn [skill enabled]
           (st/emit! (dsk/set-inherited-skill-enabled
                      {:skill-name (:name skill) :enabled enabled}))))]

    (mf/with-effect [team]
      (dom/set-html-title
       (tr "title.team-skills"
           (if (:is-default team)
             (tr "dashboard.your-penpot")
             (:name team)))))

    (mf/with-effect []
      (st/emit! (dsk/fetch-design-skills)))

    [:*
     [:> header* {:team team :section :dashboard-team-skills}]
     [:section {:class (stl/css :dashboard-container :dashboard-team-skills)}
      [:div {:class (stl/css :skills-hero)}
       [:h2 {:class (stl/css :hero-title)} (tr "labels.skills")]
       [:p {:class (stl/css :hero-desc)} (tr "dashboard.skills.description")]]

      [:> skills-section*
       {:title (tr "dashboard.skills.team-section")
        :description (tr "dashboard.skills.team-section-desc")
        :scope :team
        :skills (:team data)
        :can-edit can-edit
        :enabled-fn (fn [skill] (:is-enabled skill))
        :on-toggle on-toggle-team}]

      [:> skills-section*
       {:title (tr "dashboard.skills.app-section")
        :description (tr "dashboard.skills.app-section-desc")
        :scope :app
        :skills (:app data)
        :can-edit true
        :enabled-fn (fn [skill] (not (contains? overrides (:name skill))))
        :on-toggle on-toggle-app}]]]))
