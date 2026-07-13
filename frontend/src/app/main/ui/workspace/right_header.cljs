;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.right-header
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.shortcuts :as scd]
   [app.main.data.team :as dtm]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.drawing.common :as dwc]
   [app.main.data.workspace.history :as dwh]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.data.workspace.skills :as dwsk]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
   [app.main.ui.dashboard.team]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.exports.assets :refer [progress-widget]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.presence :refer [active-sessions*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ref:persistence-status
  (l/derived :status refs/persistence))

(def ^:private ref:skills-dock
  (l/derived :skills-dock refs/workspace-local))

;; --- Zoom Widget

(mf/defc zoom-widget-workspace
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [zoom on-increase on-decrease on-zoom-reset on-zoom-fit on-zoom-selected]}]
  (let [open*           (mf/use-state false)
        open?           (deref open*)

        open-dropdown
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! open* true)))

        close-dropdown
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! open* false)))

        on-increase
        (mf/use-fn
         (mf/deps on-increase)
         (fn [event]
           (dom/stop-propagation event)
           (on-increase)))

        on-decrease
        (mf/use-fn
         (mf/deps on-decrease)
         (fn [event]
           (dom/stop-propagation event)
           (on-decrease)))

        zoom (fmt/format-percent zoom {:precision 0})]

    [:*
     [:div {:on-click open-dropdown
            :class (stl/css-case :zoom-widget true
                                 :selected open?)
            :title (tr "workspace.header.zoom")}
      [:span {:class (stl/css :label)} zoom]]
     [:& dropdown {:show open? :on-close close-dropdown}
      [:ul {:class (stl/css :dropdown)}
       [:li {:class (stl/css :basic-zoom-bar)}
        [:span {:class (stl/css :zoom-btns)}
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "shortcuts.decrease-zoom")
                           :on-click on-decrease
                           :icon i/remove}]
         [:p {:class (stl/css :zoom-text)} zoom]
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "shortcuts.increase-zoom")
                           :on-click on-increase
                           :icon i/add}]]
        [:button {:class (stl/css :reset-btn)
                  :on-click on-zoom-reset}
         (tr "workspace.header.reset-zoom")]]
       [:li {:class (stl/css :zoom-option)
             :on-click on-zoom-fit}
        (tr "workspace.header.zoom-fit-all")
        [:span {:class (stl/css :shortcuts)}
         (for [sc (scd/split-sc (sc/get-tooltip :fit-all))]
           [:span {:class (stl/css :shortcut-key)
                   :key (str "zoom-fit-" sc)} sc])]]
       [:li {:class (stl/css :zoom-option)
             :on-click on-zoom-selected}
        (tr "workspace.header.zoom-selected")
        [:span {:class (stl/css :shortcuts)}
         (for [sc (scd/split-sc (sc/get-tooltip :zoom-selected))]
           [:span {:class (stl/css :shortcut-key)
                   :key (str "zoom-selected-" sc)} sc])]]]]]))

;; --- Header Component

(mf/defc right-header*
  [{:keys [file-id layout page-id]}]
  (let [threads-map       (mf/deref refs/comment-threads)

        zoom              (mf/deref refs/selected-zoom)
        read-only?        (mf/use-ctx ctx/workspace-read-only?)
        selected-drawtool (mf/deref refs/selected-drawing-tool)

        on-increase       (mf/use-fn #(st/emit! (dw/increase-zoom nil)))
        on-decrease       (mf/use-fn #(st/emit! (dw/decrease-zoom nil)))
        on-zoom-reset     (mf/use-fn #(st/emit! dw/reset-zoom))
        on-zoom-fit       (mf/use-fn #(st/emit! dw/zoom-to-fit-all))
        on-zoom-selected  (mf/use-fn #(st/emit! dw/zoom-to-selected-shape))

        editing*          (mf/use-state false)
        editing?          (deref editing*)

        input-ref         (mf/use-ref nil)

        team              (mf/deref refs/team)
        permissions       (get team :permissions)

        has-unread-comments?
        (mf/with-memo [threads-map file-id]
          (->> (vals threads-map)
               (some #(and (= (:file-id %) file-id)
                           (pos? (:count-unread-comments %))))
               (boolean)))

        display-share-button?
        (and (not (:is-default team))
             (or (:is-admin permissions)
                 (:is-owner permissions)))

        nav-to-viewer
        (mf/use-fn
         (mf/deps file-id page-id)
         (fn []
           (let [params {:page-id page-id
                         :file-id file-id
                         :section "interactions"}]
             (st/emit! (dcm/go-to-viewer params)))))

        active-comments
        (mf/use-fn
         (mf/deps layout)
         (fn []
           (st/emit! :interrupt
                     (dw/clear-edition-mode)
                     (-> (dw/remove-layout-flag :document-history)
                         (vary-meta assoc ::ev/origin "workspace-header"))
                     (dw/select-for-drawing :comments))))

        toggle-comments
        (mf/use-fn
         (mf/deps selected-drawtool)
         (fn [_]
           (if (= selected-drawtool :comments)
             (st/emit! (dwc/clear-drawing))
             (active-comments))))

        toggle-history
        (mf/use-fn
         (mf/deps selected-drawtool)
         (fn []
           (when (= :comments selected-drawtool)
             (st/emit! :interrupt
                       (dw/clear-edition-mode)))

           (st/emit! (-> (dwh/initialize-history)
                         (vary-meta assoc ::ev/origin "workspace-header")))))

        open-share-dialog
        (mf/use-fn
         (mf/deps team)
         (fn []
           (st/emit! (dtm/check-and-invite-members {:team-id (:id team)
                                                    :origin :workspace}))))]

    (mf/with-effect [editing?]
      (when ^boolean editing?
        (dom/select-text! (mf/ref-val input-ref))))

    ;; Messages posted by the docked panels to the workspace window:
    ;; - open-chat: the Skills panel asks for the chat panel (e.g. "Fix via
    ;;   chat" on audit violations); the pending prompt travels through the
    ;;   file's shared pluginData and is drained by the chat panel on init.
    ;; - refresh-scopes: the chat panel asks for a fresh skills + AI-provider
    ;;   push (e.g. after connecting a provider in another tab).
    ;; - ai-round: one buffered agent round to relay to the backend proxy.
    (mf/with-effect []
      (let [on-message
            (fn [event]
              (when-let [data (.-data event)]
                (case (unchecked-get data "type")
                  "penpot-skills:open-chat"
                  (st/emit! (dwsk/open-panel :chat))

                  "penpot-skills:refresh-scopes"
                  (st/emit! (dwsk/fetch-and-push-scopes))

                  "penpot-skills:ai-round"
                  (st/emit! (dwsk/relay-ai-round
                             {:id (unchecked-get data "id")
                              :provider (unchecked-get data "provider")
                              :payload (unchecked-get data "payload")}))

                  nil)))]
        (.addEventListener js/window "message" on-message)
        #(.removeEventListener js/window "message" on-message)))

    [:div {:class (stl/css :workspace-header-right)}
     [:div {:class (stl/css :users-section)}
      [:> active-sessions*]]

     [:& progress-widget]

     [:div {:class (stl/css :separator)}]

     [:div {:class (stl/css :zoom-section)}
      [:& zoom-widget-workspace
       {:zoom zoom
        :on-increase on-increase
        :on-decrease on-decrease
        :on-zoom-reset on-zoom-reset
        :on-zoom-fit on-zoom-fit
        :on-zoom-selected on-zoom-selected}]]

     ;; Bundled Penpot panels (agent chat / skills manager), opened
     ;; natively as docked workspace panels — no plugin install. One dock
     ;; slot: opening one panel swaps out the other.
     (let [dock (mf/deref ref:skills-dock)]
       [:*
        [:div {:class (stl/css :comments-section)}
         [:> icon-button* {:variant (if (= dock :chat) "primary" "ghost")
                           :aria-label "Penpot Agent"
                           :icon i/feedback
                           :on-click #(st/emit! (dwsk/toggle-panel :chat))}]]
        [:div {:class (stl/css :comments-section)}
         [:> icon-button* {:variant (if (= dock :skills) "primary" "ghost")
                           :aria-label "Penpot Skills"
                           :icon i/puzzle
                           :on-click #(st/emit! (dwsk/toggle-panel :skills))}]]])

     [:div {:class (stl/css :comments-section)}
      [:button {:title (tr "workspace.toolbar.comments" (sc/get-tooltip :add-comment))
                :aria-label (tr "workspace.toolbar.comments" (sc/get-tooltip :add-comment))
                :class (stl/css-case :comments-btn true
                                     :selected (= selected-drawtool :comments))
                :on-click toggle-comments
                :data-tool "comments"
                :style {:position "relative"}}
       deprecated-icon/comments
       (when ^boolean has-unread-comments?
         [:div {:class (stl/css :unread)}])]]

     (when-not ^boolean read-only?
       [:div {:class (stl/css :history-section)}
        [:button
         {:title (tr "workspace.sidebar.history")
          :aria-label (tr "workspace.sidebar.history")
          :class (stl/css-case :selected (contains? layout :document-history)
                               :history-button true)
          :on-click toggle-history}
         deprecated-icon/history]])

     (when display-share-button?
       [:a {:class (stl/css :viewer-btn)
            :title (tr "workspace.header.share")
            :on-click open-share-dialog}
        deprecated-icon/share])

     [:a {:class (stl/css :viewer-btn)
          :title (tr "workspace.header.viewer" (sc/get-tooltip :open-viewer))
          :on-click nav-to-viewer}
      deprecated-icon/play]]))

