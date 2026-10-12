;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.dashboard.agent-recipes
  "Team Dashboard → Agent Recipes: the recipes the design agent builds from.

  The team's own recipes come first: whoever can edit the team writes one,
  changes it, takes it out of use, removes it, brings one in as a file or
  takes one out as a file. A team recipe of the name of a built-in one is
  used in its place. The built-in ones are listed after, to read and to take
  out as a file, and cannot be changed."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.agent.recipes :as cars]
   [app.main.data.workspace.team-recipes :as dwtr]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:team-recipes
  (l/derived :team-recipes st/state))

(def ^:private ref:community-recipes
  (l/derived :community-recipes st/state))

(defn- as-text
  "A recipe as the file and the editor show it."
  [recipe]
  (js/JSON.stringify (clj->js recipe) nil 2))

(def ^:private starter
  {:recipe 1
   :name "my-recipe"
   :title "My recipe"
   :purpose "What it is for, in a sentence."
   :category "container"
   :tree {:type "board"
          :name "My recipe"
          :layout {:type "flex" :dir "column"}
          :tokens {:fill "color.surface" :padding "spacing.lg" :gap "spacing.sm"}
          :children [{:type "text"
                      :name "Title"
                      :text "Title"
                      :tokens {:fill "color.text" :fontSize "font-size.lg"}}]}})

(defn- download!
  [recipe]
  (dom/trigger-download (str (:name recipe) ".recipe.json")
                        (js/Blob. #js [(as-text recipe)] #js {:type "application/json"})))

(defn- origin-label
  [{:keys [origin origin-note]}]
  (case origin
    "saved"    (tr "dashboard.agent-recipes.origin.saved" (or origin-note "—"))
    "imported" (tr "dashboard.agent-recipes.origin.imported" (or origin-note "—"))
    "community" (tr "dashboard.agent-recipes.origin.community" (or origin-note "—"))
    (tr "dashboard.agent-recipes.origin.team")))

(mf/defc recipe-row*
  {::mf/private true}
  [{:keys [recipe tags children]}]
  [:li {:class (stl/css :recipe-row)}
   [:div {:class (stl/css :recipe-text)}
    [:span {:class (stl/css :recipe-name)} (:title recipe)]
    [:span {:class (stl/css :recipe-desc)} (:purpose recipe)]
    [:div {:class (stl/css :recipe-tags)}
     [:span {:class (stl/css :recipe-tag)} (:name recipe)]
     [:span {:class (stl/css :recipe-tag)} (:category recipe)]
     (for [tag tags]
       [:span {:class (stl/css :recipe-tag) :key tag} tag])]]
   [:div {:class (stl/css :recipe-actions)} children]])

(mf/defc action*
  {::mf/private true}
  [{:keys [label on-click danger]}]
  [:button {:class (stl/css-case :action true :action-danger danger)
            :type "button"
            :on-click on-click}
   label])

(mf/defc editor*
  {::mf/private true}
  [{:keys [title text error on-change on-save on-cancel]}]
  [:div {:class (stl/css :editor)}
   [:h2 {:class (stl/css :editor-title)} title]
   [:p {:class (stl/css :hero-desc)} (tr "dashboard.agent-recipes.editor-hint")]
   [:textarea {:class (stl/css :editor-text)
               :value text
               :spell-check false
               :aria-label title
               :on-change #(on-change (dom/get-target-val %))}]
   (when (seq error)
     [:p {:class (stl/css :error) :role "alert"} error])
   [:div {:class (stl/css :editor-actions)}
    [:> action* {:label (tr "labels.cancel") :on-click on-cancel}]
    [:button {:class (stl/css :primary) :type "button" :on-click on-save}
     (tr "labels.save")]]])

(mf/defc agent-recipes-page*
  [{:keys [team]}]
  (let [team-id   (:id team)
        can-edit? (boolean (get-in team [:permissions :can-edit]))
        recipes   (mf/deref ref:team-recipes)
        community (mf/deref ref:community-recipes)
        editing*  (mf/use-state nil)
        editing   (deref editing*)
        error*    (mf/use-state nil)
        error     (deref error*)
        input-ref (mf/use-ref nil)

        callbacks
        (mf/with-memo []
          {:on-success (fn [_] (reset! editing* nil) (reset! error* nil))
           :on-error   (fn [problem] (reset! error* problem))})

        on-save
        (mf/use-fn
         (mf/deps team-id editing)
         (fn []
           (st/emit! (dwtr/save-team-recipe {:team-id team-id
                                             :content (:text editing)
                                             :origin (or (:origin editing) "team")
                                             :origin-note (:origin-note editing)}
                                            callbacks))))

        on-import
        (mf/use-fn
         (mf/deps team-id)
         (fn [event]
           (when-let [file (some-> (dom/get-target event) .-files (aget 0))]
             (->> (wapi/read-file-as-text file)
                  (rx/subs! (fn [text]
                              (st/emit! (dwtr/save-team-recipe {:team-id team-id
                                                                :content text
                                                                :origin "imported"
                                                                :origin-note (.-name file)}
                                                               callbacks)))))
             ;; the same file can be chosen again after a refusal
             (set! (.-value (dom/get-target event)) ""))))]

    (mf/with-effect [team]
      (dom/set-html-title (tr "title.agent-recipes")))

    (mf/with-effect [team-id]
      (when team-id
        (st/emit! (dwtr/fetch-team-recipes team-id)
                  (dwtr/fetch-community-recipes team-id))))

    [:section {:class (stl/css :agent-recipes-page)}
     (if (some? editing)
       [:> editor* {:title (if (:new editing)
                             (tr "dashboard.agent-recipes.new")
                             (tr "dashboard.agent-recipes.edit-title" (:name editing)))
                    :text (:text editing)
                    :error error
                    :on-change #(swap! editing* assoc :text %)
                    :on-save on-save
                    :on-cancel #(do (reset! editing* nil) (reset! error* nil))}]
       [:*
        [:div {:class (stl/css :hero)}
         [:h2 {:class (stl/css :hero-title)} (tr "labels.agent-recipes")]
         [:p {:class (stl/css :hero-desc)} (tr "dashboard.agent-recipes.description")]]

        (when can-edit?
          [:div {:class (stl/css :toolbar)}
           [:button {:class (stl/css :primary) :type "button"
                     :on-click #(reset! editing* {:new true :text (as-text starter)})}
            (tr "dashboard.agent-recipes.new")]
           [:> action* {:label (tr "dashboard.agent-recipes.import")
                        :on-click #(some-> (mf/ref-val input-ref) .click)}]
           [:input {:type "file"
                    :ref input-ref
                    :class (stl/css :file-input)
                    :accept ".json,application/json"
                    :data-testid "recipe-file-input"
                    :on-change on-import}]])

        (when (seq error)
          [:p {:class (stl/css :error) :role "alert"}
           (tr "dashboard.agent-recipes.refused" error)])

        [:h3 {:class (stl/css :section-title)} (tr "dashboard.agent-recipes.team-title")]
        (if (seq recipes)
          [:ul {:class (stl/css :recipe-list)}
           (for [{:keys [id recipe disabled overrides-built-in created-by-name] :as row} recipes]
             [:> recipe-row* {:key id
                              :recipe recipe
                              :tags (cond-> [(origin-label row)]
                                      (seq created-by-name) (conj (tr "dashboard.agent-skills.by" created-by-name))
                                      overrides-built-in    (conj (tr "dashboard.agent-recipes.replaces-built-in"))
                                      disabled              (conj (tr "dashboard.agent-recipes.disabled")))}
              [:> action* {:label (tr "dashboard.agent-recipes.export") :on-click #(download! recipe)}]
              (when can-edit?
                [:*
                 [:> action* {:label (tr "labels.edit")
                              :on-click #(reset! editing* {:name (:name recipe)
                                                           :text (as-text recipe)
                                                           :origin (:origin row)
                                                           :origin-note (:origin-note row)})}]
                 [:> action* {:label (if disabled
                                       (tr "dashboard.agent-recipes.enable")
                                       (tr "dashboard.agent-recipes.disable"))
                              :on-click #(st/emit! (dwtr/set-team-recipe-disabled team-id id (not disabled) callbacks))}]
                 [:> action* {:label (tr "labels.delete")
                              :danger true
                              :on-click #(st/emit! (dwtr/delete-team-recipe team-id id callbacks))}]])])]
          [:p {:class (stl/css :empty)} (tr "dashboard.agent-recipes.empty")])

        ;; the community source, when this instance has one
        (when (:configured community)
          [:*
           [:h3 {:class (stl/css :section-title)} (tr "dashboard.agent-recipes.community-title")]
           [:p {:class (stl/css :hero-desc)}
            (tr "dashboard.agent-recipes.community-desc" (or (:source community) "—"))]
           (cond
             (not (:available community))
             [:p {:class (stl/css :empty)} (tr "dashboard.agent-recipes.community-unavailable")]

             (empty? (:recipes community))
             [:p {:class (stl/css :empty)} (tr "dashboard.agent-recipes.community-empty")]

             :else
             [:ul {:class (stl/css :recipe-list)}
              (for [{:keys [name author in-team] :as offered} (:recipes community)]
                [:> recipe-row* {:key name
                                 :recipe offered
                                 :tags (cond-> []
                                         (seq author) (conj (tr "dashboard.agent-skills.by" author))
                                         in-team      (conj (tr "dashboard.agent-recipes.community-in-team")))}
                 (when (and can-edit? (not in-team))
                   [:> action* {:label (tr "dashboard.agent-recipes.import")
                                :on-click #(st/emit! (dwtr/import-community-recipe team-id name callbacks))}])])])])

        [:h3 {:class (stl/css :section-title)} (tr "dashboard.agent-recipes.built-in-title")]
        [:p {:class (stl/css :hero-desc)} (tr "dashboard.agent-recipes.built-in-desc")]
        [:ul {:class (stl/css :recipe-list)}
         (for [recipe cars/all]
           [:> recipe-row* {:key (:name recipe)
                            :recipe recipe
                            :tags [(tr "dashboard.agent-recipes.built-in")]}
            [:> action* {:label (tr "dashboard.agent-recipes.export") :on-click #(download! recipe)}]])]])]))
