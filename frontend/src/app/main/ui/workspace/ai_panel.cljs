;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-panel
  "The All-In Penpot (Agents) panel: a native, right-docked workspace side
  panel (like Layers/Assets on the left), toggled from the workspace toolbar
  and Alt+B.

  Two tabs (Chat / Skills). The Chat tab shows a per-file transcript that
  survives navigation and a context chip with the current page + selection;
  the composer appends messages to that transcript. The live agent turn that
  produces assistant replies is the CLJS port of the `ai-skills` agent (a
  separate plan). The Skills manager tab is owned by its own story."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.ai-panel :as dwaip]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- selection-label
  "`refs/selected-shapes` is a set of shape ids; resolve the single-selection
  name against the page objects."
  [selected objects]
  (let [n (count selected)]
    (cond
      (zero? n) "No selection"
      (= 1 n)   (dm/str "1 layer: " (:name (get objects (first selected))))
      :else     (dm/str n " layers selected"))))

(mf/defc chat-tab*
  {::mf/private true}
  []
  (let [messages  (mf/deref refs/ai-panel-messages)
        page      (mf/deref refs/workspace-page)
        selected  (mf/deref refs/selected-shapes)
        objects   (mf/deref refs/workspace-page-objects)

        input*    (mf/use-state "")
        input     (deref input*)

        on-input  (mf/use-fn
                   (fn [event]
                     (reset! input* (dom/get-value (dom/get-target event)))))

        send      (mf/use-fn
                   (mf/deps input)
                   (fn []
                     (let [text (str/trim input)]
                       (when (seq text)
                         (st/emit! (dwaip/append-message "user" text))
                         (reset! input* "")))))

        on-key-down (mf/use-fn
                     (mf/deps send)
                     (fn [event]
                       (when (and (= "Enter" (.-key event))
                                  (not (.-shiftKey event)))
                         (dom/prevent-default event)
                         (send))))]

    [:div {:class (stl/css :chat-tab)}
     ;; Current-file context surfaced to the agent: page + selection.
     [:div {:class (stl/css :context-chip)}
      [:span {:class (stl/css :context-page)} (:name page)]
      [:span {:class (stl/css :context-sep)} "·"]
      [:span {:class (stl/css :context-selection)} (selection-label selected objects)]]

     (if (seq messages)
       [:div {:class (stl/css :transcript)}
        (for [[idx message] (map-indexed vector messages)]
          [:div {:key idx
                 :class (stl/css-case :message true
                                      :message-user (= "user" (:role message)))}
           (:content message)])]
       [:div {:class (stl/css :transcript-empty)}
        "Start a conversation with the design agent."])

     [:div {:class (stl/css :composer)}
      [:textarea {:class (stl/css :composer-input)
                  :placeholder "Ask the agent…"
                  :value input
                  :on-change on-input
                  :on-key-down on-key-down}]]]))

(mf/defc ai-panel*
  ;; `file` / `page` are passed for future context-aware tabs; the Chat tab
  ;; reads live context from refs.
  [_props]
  (let [tab*      (hooks/use-persisted-state ::ai-panel-tab "chat")
        tab       (deref tab*)
        on-change (mf/use-fn #(reset! tab* %))

        on-close  (mf/use-fn #(st/emit! (dwaip/close-panel)))

        tabs      (mf/with-memo []
                    [{:label "Chat" :id "chat"}
                     {:label "Skills" :id "skills"}])]

    [:aside {:class (stl/css :ai-panel)}
     ;; Title header, sized to the workspace right-header band so the tabs
     ;; below line up with the sidebar's Design/Prototype/Inspect tabs.
     [:div {:class (stl/css :header)}
      [:span {:class (stl/css :title)} "Agents"]
      [:> icon-button* {:variant "ghost"
                        :aria-label "Close Agents panel"
                        :on-click on-close
                        :icon i/close}]]

     [:> tab-switcher* {:tabs tabs
                        :selected tab
                        :on-change on-change
                        :scrollable-panel true
                        :class (stl/css :tabs)}

      (case tab
        "chat"
        [:> chat-tab*]

        "skills"
        [:div {:class (stl/css :skills-tab)}
         [:div {:class (stl/css :placeholder)}
          "Skills manager — coming in its own story."]])]]))
