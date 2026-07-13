;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-panel
  "The All-In Penpot AI panel: a native, right-docked workspace side panel
  (like Layers/Assets on the left), toggled from the workspace toolbar and
  Alt+B.

  Phase 03 adds the two-tab shell (Chat / Skills). The tab bodies are stubs:
  the Chat transcript + agent wiring lands in Phase 05, and the Skills
  manager is owned by its own story. Open/close is still the scaffold
  `:ai-panel` layout flag (Phase 04 replaces it with a file-bound store)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.layout :as dwl]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.hooks :as hooks]
   [rumext.v2 :as mf]))

(mf/defc ai-panel*
  ;; `file` / `page` are passed for the context-aware tabs in later phases;
  ;; Phase 03 renders the tab shell with stub bodies.
  [_props]
  (let [tab*      (hooks/use-persisted-state ::ai-panel-tab "chat")
        tab       (deref tab*)
        on-change (mf/use-fn #(reset! tab* %))

        on-close  (mf/use-fn #(st/emit! (dwl/remove-layout-flag :ai-panel)))

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
        [:div {:class (stl/css :chat-tab)}
         [:div {:class (stl/css :transcript-empty)}
          "Start a conversation with the design agent."]
         [:div {:class (stl/css :composer)}
          [:textarea {:class (stl/css :composer-input)
                      :placeholder "Ask the agent…"
                      :disabled true}]]]

        "skills"
        [:div {:class (stl/css :skills-tab)}
         [:div {:class (stl/css :placeholder)}
          "Skills manager — coming in its own story."]])]]))
