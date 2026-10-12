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
   [app.main.ui.dashboard.team :refer [header*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.controls.utilities.label :refer [label*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.layout.modal :refer [modal* modal-header* modal-content* modal-footer*]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.forms :as fc]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

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
  [{:label (tr "dashboard.skills.kind.skill") :value "skill" :id "skill"}
   {:label (tr "dashboard.skills.kind.rule") :value "rule" :id "rule"}])

(defn- enforcement-options
  []
  [{:label (tr "dashboard.skills.enforcement.advisory") :value "advisory" :id "advisory"}
   {:label (tr "dashboard.skills.enforcement.triggered") :value "triggered" :id "triggered"}
   {:label (tr "dashboard.skills.enforcement.enforced") :value "enforced" :id "enforced"}])

(defn- trigger-options
  []
  [{:label "—" :value "" :id ""}
   {:label "fill-change" :value "fill-change" :id "fill-change"}
   {:label "shape-new" :value "shape-new" :id "shape-new"}
   {:label "rename" :value "rename" :id "rename"}
   {:label "selection-change" :value "selection-change" :id "selection-change"}
   {:label "content-save" :value "content-save" :id "content-save"}])

(defn- on-open-change
  "DS modal* closes itself (close button, Escape, backdrop) through this
  callback; the dialogs here live in the modal store, so hide it there."
  [open?]
  (when-not open?
    (modal/hide!)))

(mf/defc mandatory-field*
  "DS checkbox* bound to the form's :is-mandatory (there is no form-aware
  checkbox wrapper)."
  {::mf/private true}
  [{:keys [form]}]
  (let [id        (mf/use-id)
        checked   (boolean (get-in @form [:data :is-mandatory]))
        on-change (mf/use-fn
                   (mf/deps form)
                   (fn [event]
                     (fm/on-input-change form :is-mandatory
                                         (boolean (dom/checked? (dom/get-target event))))))]
    [:> checkbox* {:id id
                   :label (tr "dashboard.skills.form.mandatory")
                   :checked checked
                   :on-change on-change}]))

(mf/defc body-field*
  "Multi-line body. The DS has no textarea, so this is a plain textarea
  with a DS label*, styled with the DS input tokens."
  {::mf/private true}
  [{:keys [form]}]
  (let [id        (mf/use-id)
        value     (get-in @form [:data :body] "")
        on-change (mf/use-fn
                   (mf/deps form)
                   (fn [event]
                     (fm/on-input-change form :body
                                         (dom/get-value (dom/get-target event)))))]
    [:div {:class (stl/css :fields-row)}
     [:> label* {:for id} (tr "dashboard.skills.form.body")]
     [:textarea {:id id
                 :class (stl/css :body-textarea)
                 :rows 10
                 :value value
                 :placeholder (tr "dashboard.skills.form.body-hint")
                 :on-change on-change}]]))

(mf/defc select-field*
  {::mf/private true}
  [{:keys [name label options]}]
  (let [id (mf/use-id)]
    [:div {:class (stl/css :fields-row)}
     [:> label* {:for id} label]
     ;; select* renders a <button> with no type; inside the form it would
     ;; default to submit and save the dialog on click.
     [:> fc/form-select* {:id id
                          :type "button"
                          :name name
                          :options options}]]))

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

    [:> modal* {:is-open true
                :size "large"
                :on-open-change on-open-change}
     [:> fc/form* {:form form
                   :class (stl/css :skill-form)
                   :on-submit on-submit}
      [:> modal-header*
       {:title (cond
                 (some? skill) (tr "modals.edit-design-skill.title")
                 (= scope :app) (tr "modals.create-design-skill.title-app")
                 :else (tr "modals.create-design-skill.title-team"))}]

      [:> modal-content* {:class (stl/css :skill-form-content)}
       [:> fc/form-input* {:type "text"
                           :auto-focus true
                           :name :name
                           :max-length 250
                           :label (tr "dashboard.skills.form.name")
                           :placeholder "token-only-colors"}]
       [:div {:class (stl/css :fields-pair)}
        [:> select-field* {:name :kind
                           :label (tr "dashboard.skills.form.kind")
                           :options (kind-options)}]
        [:> select-field* {:name :enforcement
                           :label (tr "dashboard.skills.form.enforcement")
                           :options (enforcement-options)}]]
       [:div {:class (stl/css :fields-pair)}
        [:> select-field* {:name :trigger-on
                           :label (tr "dashboard.skills.form.trigger")
                           :options (trigger-options)}]
        [:div {:class (stl/css :fields-row :fields-row-checkbox)}
         [:> mandatory-field* {:form form}]
         [:> text* {:as "div"
                    :typography t/body-small
                    :class (stl/css :hint)}
          (tr "dashboard.skills.form.mandatory-hint")]]]
       [:> fc/form-input* {:type "text"
                           :name :description
                           :max-length 2000
                           :label (tr "dashboard.skills.form.description")
                           :placeholder (tr "dashboard.skills.form.description-hint")}]
       [:> body-field* {:form form}]]

      [:> modal-footer* {}
       [:> button* {:variant "secondary"
                    :on-click modal/hide!}
        (tr "labels.cancel")]
       [:> fc/form-submit* {:variant "primary"}
        (if skill
          (tr "modals.edit-design-skill.submit-label")
          (tr "modals.create-design-skill.submit-label"))]]]]))

(mf/defc delete-skill-dialog
  {::mf/register modal/components
   ::mf/register-as :design-skill-delete}
  [{:keys [skill]}]
  (let [on-accept
        (mf/use-fn
         (mf/deps skill)
         (fn []
           (st/emit! (dsk/delete-design-skill {:id (:id skill)})
                     (modal/hide))))]
    [:> modal* {:is-open true
                :size "small"
                :on-open-change on-open-change}
     [:> modal-header* {:title (tr "modals.delete-design-skill.title")}]
     [:> modal-content* {}
      [:> text* {:as "p" :typography t/body-large}
       (tr "modals.delete-design-skill.message")]]
     [:> modal-footer* {}
      [:> button* {:variant "secondary"
                   :on-click modal/hide!}
       (tr "labels.cancel")]
      [:> button* {:variant "destructive"
                   :on-click on-accept}
       (tr "labels.delete")]]]))

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
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.options")
                         :on-click on-show
                         :icon i/menu}]
       [:& dropdown {:show @show? :on-close on-hide :dropdown-id "skill-actions"}
        [:ul {:class (stl/css :skill-actions-dropdown)}
         [:li {:on-click on-edit
               :class (stl/css :skill-dropdown-item)} (tr "labels.edit")]
         [:li {:on-click on-delete
               :class (stl/css :skill-dropdown-item)} (tr "labels.delete")]]]])))

(mf/defc skill-item*
  {::mf/private true}
  [{:keys [skill scope can-edit can-toggle enabled on-toggle]}]
  (let [on-edit
        (mf/use-fn
         (mf/deps skill scope)
         (fn []
           (st/emit! (modal/show :design-skill {:skill skill :scope scope}))))

        on-delete
        (mf/use-fn
         (mf/deps skill)
         (fn []
           (st/emit! (modal/show :design-skill-delete {:skill skill}))))

        on-switch
        (mf/use-fn
         (mf/deps skill on-toggle)
         (fn [checked]
           (on-toggle skill checked)))

        toggle-hint
        (if (:is-mandatory skill)
          (tr "dashboard.skills.mandatory-toggle-hint")
          (tr "dashboard.skills.toggle-hint"))]

    [:div {:class (stl/css :table-row :skill-row)}
     [:> tooltip* {:content toggle-hint}
      [:> switch* {:default-checked (boolean enabled)
                   :aria-label toggle-hint
                   :disabled (boolean (or (:is-mandatory skill) (not can-toggle)))
                   :on-change on-switch}]]
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
  [{:keys [title description scope skills can-edit can-toggle enabled-fn on-toggle]}]
  [:div {:class (stl/css :skills-section)}
   [:div {:class (stl/css :section-head)}
    [:div
     [:h2 {:class (stl/css :section-title)} title]
     [:p {:class (stl/css :section-desc)} description]]
    (when can-edit
      [:> button* {:variant "primary"
                   :icon i/add
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
          :can-toggle can-toggle
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
        :can-toggle can-edit
        :enabled-fn (fn [skill] (:is-enabled skill))
        :on-toggle on-toggle-team}]

      ;; App-level skills are seed-only (see design-skills backend ns): teams
      ;; can switch inheritance on/off but cannot create, edit or delete them.
      [:> skills-section*
       {:title (tr "dashboard.skills.app-section")
        :description (tr "dashboard.skills.app-section-desc")
        :scope :app
        :skills (:app data)
        :can-edit false
        :can-toggle can-edit
        :enabled-fn (fn [skill] (not (contains? overrides (:name skill))))
        :on-toggle on-toggle-app}]]]))
