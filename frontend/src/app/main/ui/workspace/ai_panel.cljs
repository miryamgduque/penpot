;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-panel
  "The All-In Penpot AI panel: a native, right-docked workspace side panel
  (like Layers/Assets on the left), toggled from the workspace toolbar and
  Alt+B.

  Phase 01 renders the docked column shell only — the open/close state is a
  scaffold layout flag (`:ai-panel`) that later phases replace with a
  file-bound store, and the two tabs (Chat / Skills) plus their contents land
  in the following phases."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.layout :as dwl]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [rumext.v2 :as mf]))

(mf/defc ai-panel*
  ;; `file` / `page` are passed for the context-aware tabs in later phases;
  ;; Phase 01 renders only the shell.
  [_props]
  (let [on-close
        (mf/use-fn
         (fn []
           (st/emit! (dwl/remove-layout-flag :ai-panel))))]
    [:aside {:class (stl/css :ai-panel)}
     [:div {:class (stl/css :header)}
      [:span {:class (stl/css :title)} "All-In Penpot"]
      [:> icon-button* {:variant "ghost"
                        :aria-label "Close All-In Penpot panel"
                        :icon i/close
                        :on-click on-close}]]
     [:div {:class (stl/css :body)}]]))
