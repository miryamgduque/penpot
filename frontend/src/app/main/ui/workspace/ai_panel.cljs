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
   [app.main.data.ai-providers :as dai]
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

(defn- provider-pool
  "Flattens the connected providers into `{:provider :model}` entries — one per
  model the user enabled."
  [providers]
  (vec (for [[_ status] providers
             model (:enabled-models status)]
         {:provider (:provider status) :model model})))

;; The built-in skills catalog shown on the Skills tab's first-run view. Static
;; built-in data, mirroring skills-core's `builtinCatalog()` plus curated display
;; copy. Grouped by category in display order; the dispatch router and the
;; shared/core house-rule docs are intentionally excluded. Audit + build skills
;; ship enabled; the single auto-fix skill ships off (it writes directly).
(def ^:private skills-catalog
  [{:category "Audits"
    :skills [{:name "penpot-audit-accessibility" :label "Accessibility audit"
              :blurb "WCAG 2.1/2.2 AA checks" :mode "suggest" :enabled true}
             {:name "penpot-audit-tokens" :label "Tokens governance audit"
              :blurb "Hardcoded values, off-grid spacing" :mode "suggest" :enabled true}
             {:name "penpot-design-to-code-review" :label "Design-to-code review"
              :blurb "Design vs. built code drift" :mode "suggest" :enabled true}]}
   {:category "Build"
    :skills [{:name "penpot-foundations" :label "Foundations"
              :blurb "Design tokens setup" :mode "review" :enabled true}
             {:name "penpot-component-factory" :label "Component factory"
              :blurb "Builds full variant matrix" :mode "review" :enabled true}
             {:name "penpot-build-screen" :label "Build screen"
              :blurb "Designs screens from a brief" :mode "review" :enabled true}
             {:name "penpot-build-from-code" :label "Build from code"
              :blurb "Recreates a view on your tokens" :mode "review" :enabled true}
             {:name "penpot-document-handoff" :label "Document handoff"
              :blurb "Annotates a design for devs" :mode "review" :enabled true}
             {:name "penpot-migrate" :label "Migrate"
              :blurb "Figma → Penpot migration" :mode "review" :enabled true}]}
   {:category "Auto-fix"
    :skills [{:name "penpot-rename-layers" :label "Rename layers"
              :blurb "Auto-fixes messy layer names" :mode "autofix" :enabled false}]}])

(def ^:private mode-label
  {"suggest" "suggest" "review" "review" "autofix" "auto-fix"})

(mf/defc chat-tab*
  {::mf/private true}
  []
  (let [messages  (mf/deref refs/ai-panel-messages)
        page      (mf/deref refs/workspace-page)
        selected  (mf/deref refs/selected-shapes)
        objects   (mf/deref refs/workspace-page-objects)
        providers (mf/deref refs/ai-providers)
        busy?     (mf/deref refs/ai-panel-busy?)

        pool      (mf/with-memo [providers] (provider-pool providers))

        ;; the model pool loads asynchronously; default to the first entry
        ;; until the user picks another (tracked by index into `pool`)
        picked*   (mf/use-state nil)
        picked    (deref picked*)
        idx       (if (and picked (< picked (count pool))) picked 0)
        settings  (nth pool idx nil)

        input*    (mf/use-state "")
        input     (deref input*)

        on-input  (mf/use-fn
                   (fn [event]
                     (reset! input* (dom/get-value (dom/get-target event)))))

        on-pick   (mf/use-fn
                   (fn [event]
                     (reset! picked* (js/parseInt (dom/get-value (dom/get-target event)) 10))))

        send      (mf/use-fn
                   (mf/deps input settings busy? page selected objects)
                   (fn []
                     (let [text (str/trim input)]
                       (when (and (seq text) settings (not busy?))
                         (let [context {:file (:name page)
                                        :page (:name page)
                                        :selection (->> selected
                                                        (map #(select-keys (get objects %) [:name :type]))
                                                        (vec))}]
                           (st/emit! (dwaip/send-message settings text context))
                           (reset! input* ""))))))

        on-key-down (mf/use-fn
                     (mf/deps send)
                     (fn [event]
                       (when (and (= "Enter" (.-key event))
                                  (not (.-shiftKey event)))
                         (dom/prevent-default event)
                         (send))))]

    ;; the provider pool is fetched from the settings page; make sure it is
    ;; loaded when the chat is shown
    (mf/with-effect []
      (st/emit! (dai/fetch-ai-providers)))

    [:div {:class (stl/css :chat-tab)}
     ;; Current-file context surfaced to the agent: page + selection.
     [:div {:class (stl/css :context-chip)}
      [:span {:class (stl/css :context-page)} (:name page)]
      [:span {:class (stl/css :context-sep)} "·"]
      [:span {:class (stl/css :context-selection)} (selection-label selected objects)]]

     (if (seq messages)
       [:div {:class (stl/css :transcript)}
        (for [[idx message] (map-indexed vector messages)]
          (if (= "tool" (:role message))
            (let [error? (contains? #{"error" "rejected"} (:status message))]
              [:div {:key idx
                     :class (stl/css-case :tool-chip true :tool-chip-error error?)}
               [:span {:class (stl/css :tool-chip-glyph)} (if error? "✕" "✓")]
               [:span {:class (stl/css :tool-chip-name)} (:name message)]
               (when error?
                 [:span {:class (stl/css :tool-chip-detail)}
                  (or (:rule message) (:detail message))])])
            [:div {:key idx
                   :class (stl/css-case :message true
                                        :message-user (= "user" (:role message)))}
             (:content message)]))
        (when busy?
          [:div {:class (stl/css :message :message-thinking)} "Thinking…"])]
       [:div {:class (stl/css :transcript-empty)}
        "Start a conversation with the design agent."])

     (if (seq pool)
       [:div {:class (stl/css :composer)}
        [:select {:class (stl/css :model-picker)
                  :value (str idx)
                  :on-change on-pick}
         (for [[i entry] (map-indexed vector pool)]
           [:option {:key i :value (str i)}
            (dm/str (:provider entry) " / " (:model entry))])]
        [:textarea {:class (stl/css :composer-input)
                    :placeholder "Ask the agent…"
                    :value input
                    :disabled busy?
                    :on-change on-input
                    :on-key-down on-key-down}]]
       [:div {:class (stl/css :composer)}
        [:div {:class (stl/css :no-provider)}
         "Connect an AI provider in "
         [:a {:href "#/settings/integrations"} "Settings → Integrations"]
         " to start chatting."]])]))

(mf/defc skills-tab*
  "The built-in skills catalog: the Skills-tab first-run view. Read-only at this
  stage — cards group the bundled skills by category and show a mode badge; no
  toggling (its own story)."
  {::mf/private true}
  []
  [:div {:class (stl/css :skills-tab)}
   (for [{:keys [category skills]} skills-catalog]
     [:div {:key category :class (stl/css :catalog-group)}
      [:div {:class (stl/css :catalog-group-label)} category]
      (for [{:keys [name label blurb mode enabled]} skills]
        [:button {:key name
                  :type "button"
                  :class (stl/css-case :catalog-card true :disabled (not enabled))}
         [:div {:class (stl/css :catalog-card-head)}
          [:span {:class (stl/css :catalog-name)} label]
          (when-not enabled
            [:span {:class (stl/css :catalog-off)} "off by default"])]
         [:div {:class (stl/css :catalog-desc)}
          [:span {:class (stl/css :catalog-blurb)} blurb]
          [:span {:class (stl/css-case :mode-badge true
                                       :mode-suggest (= mode "suggest")
                                       :mode-review  (= mode "review")
                                       :mode-autofix (= mode "autofix"))}
           (get mode-label mode mode)]]])])])

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
        [:> skills-tab*])]]))
