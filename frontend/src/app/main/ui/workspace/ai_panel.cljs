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
   [app.main.data.workspace.agent :as agent]
   [app.main.data.workspace.agent-skills :as ask]
   [app.main.data.workspace.ai-panel :as dwaip]
   [app.main.data.workspace.skill-state :as skst]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
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

;; The built-in skills catalog is shared with the agent — see
;; app.main.data.workspace.agent-skills (`ask/catalog`, `ask/mode-label`).

(defn- format-tokens
  [n]
  (let [n (or n 0)]
    (cond
      (>= n 1000000) (dm/str (.toFixed (/ n 1000000) 1) "M")
      (>= n 1000)    (dm/str (.toFixed (/ n 1000) 1) "k")
      :else          (str n))))

(defn- usage-summary
  "Compact spend line: calls · prompt tokens (cached %) · output tokens · ~$
  (Claude models only)."
  [{:keys [input-tokens output-tokens cache-read-tokens cache-write-tokens requests] :as usage} model]
  (let [prompt (+ (or input-tokens 0) (or cache-read-tokens 0) (or cache-write-tokens 0))
        cached (if (pos? prompt) (js/Math.round (* (/ (or cache-read-tokens 0) prompt) 100)) 0)
        cost   (agent/estimate-cost-usd model usage)]
    (dm/str requests " calls · " (format-tokens prompt) " in (" cached "% cached) · "
            (format-tokens output-tokens) " out"
            (when cost (dm/str " · ~$" (.toFixed cost 2))))))

(mf/defc chat-tab*
  {::mf/private true}
  []
  (let [messages  (mf/deref refs/ai-panel-messages)
        page      (mf/deref refs/workspace-page)
        selected  (mf/deref refs/selected-shapes)
        objects   (mf/deref refs/workspace-page-objects)
        providers (mf/deref refs/ai-providers)
        busy?     (mf/deref refs/ai-panel-busy?)
        usage     (mf/deref refs/ai-panel-usage)

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

        picker-open* (mf/use-state false)
        picker-open? (deref picker-open*)
        picker-ref   (mf/use-ref nil)
        on-toggle-picker (mf/use-fn #(swap! picker-open* not))

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
                         (send))))

        on-clear    (mf/use-fn
                     (mf/deps busy?)
                     (fn []
                       (when-not busy?
                         (st/emit! (dwaip/clear-chat)))))]

    ;; close the model picker on any click outside it
    (mf/with-effect [picker-open?]
      (when ^boolean picker-open?
        (let [on-doc (fn [event]
                       (let [node (mf/ref-val picker-ref)]
                         (when (and node (not (.contains node (dom/get-target event))))
                           (reset! picker-open* false))))]
          (.addEventListener js/document "pointerdown" on-doc)
          (fn [] (.removeEventListener js/document "pointerdown" on-doc)))))

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
        [:> i/icon* {:icon-id i/bot-message-square :size "m"}]])

     ;; Spend meter + clear, shown once there is a conversation to act on.
     (when (or (seq messages) (some-> usage :requests pos?))
       [:div {:class (stl/css :chat-status)}
        [:span {:class (stl/css :usage-meter)
                :title "Session token usage across all API calls from this panel. Cost is an estimate at standard list prices."}
         (if (some-> usage :requests pos?)
           (usage-summary usage (:model settings))
           "Restored conversation")]
        [:button {:class (stl/css :chat-clear)
                  :type "button"
                  :disabled busy?
                  :title "Clear this file's chat history and start a fresh session"
                  :on-click on-clear}
         "✕ Clear"]])

     [:div {:class (stl/css :composer)}
      [:div {:class (stl/css :model-picker)
             :ref picker-ref}
       [:button {:type "button"
                 :class (stl/css-case :model-picker-trigger true
                                      :model-picker-open picker-open?)
                 :on-click on-toggle-picker}
        [:span {:class (stl/css :model-picker-current)}
         (if settings (:model settings) "No model")]
        [:> i/icon* {:icon-id i/arrow-down :class (stl/css :model-picker-caret)}]]

       (when picker-open?
         [:div {:class (stl/css :model-picker-menu)}
          (for [[provider entries] (group-by #(:provider (second %)) (map-indexed vector pool))]
            [:div {:key provider :class (stl/css :model-picker-group)}
             [:div {:class (stl/css :model-picker-group-label)} provider]
             (for [[i entry] entries]
               [:button {:key i
                         :type "button"
                         :class (stl/css-case :model-picker-option true
                                              :selected (= i idx))
                         :on-click #(do (reset! picked* i)
                                        (reset! picker-open* false))}
                (:model entry)])])
          [:a {:class (stl/css :model-picker-manage)
               :href "#/settings/integrations"}
           "Manage your models"]])]
      [:textarea {:class (stl/css :composer-input)
                  :placeholder "Ask the agent…"
                  :value input
                  :disabled busy?
                  :on-change on-input
                  :on-key-down on-key-down}]]]))

(mf/defc mode-badge*
  "The colored mode pill shared by the catalog cards and the detail view."
  {::mf/private true}
  [{:keys [mode]}]
  [:span {:class (stl/css-case :mode-badge true
                               :mode-suggest (= mode "suggest")
                               :mode-review  (= mode "review")
                               :mode-autofix (= mode "autofix"))}
   (get ask/mode-label mode mode)])

(mf/defc skill-detail*
  "Detail for one catalog skill, shown in place of the list within the Skills tab
  (not a modal, not a new tab). Carries the same on/off toggle as the list card;
  `enabled` is the resolved state and `on-toggle` receives the new boolean."
  {::mf/private true}
  [{:keys [skill enabled on-toggle on-back]}]
  (let [{:keys [label category mode example what]} skill]
    [:div {:class (stl/css :skill-detail)}
     [:button {:type "button" :class (stl/css :detail-back) :on-click on-back}
      "← All skills"]
     [:div {:class (stl/css :detail-category)} category]
     [:div {:class (stl/css :detail-head)}
      [:div {:class (stl/css :detail-name)} label]
      [:> switch* {:default-checked enabled
                   :aria-label (dm/str (if enabled "Disable " "Enable ") label)
                   :on-change on-toggle}]]
     [:div {:class (stl/css :detail-tags)}
      [:> mode-badge* {:mode mode}]]
     [:div {:class (stl/css :detail-section-label)} "Example trigger phrase"]
     [:div {:class (stl/css :detail-example)} (dm/str "“" example "”")]
     [:div {:class (stl/css :detail-section-label)} "What it does"]
     [:div {:class (stl/css :detail-what)} what]]))

(mf/defc skills-tab*
  "The built-in skills catalog: cards group the bundled skills by category with a
  mode badge and an on/off toggle. Clicking a card opens its detail view in
  place; toggling flips the skill for this file (per-user, instant) and drops a
  disabled skill from the agent's router — see agent-skills/resolve-enabled."
  {::mf/private true}
  []
  (let [selected*   (mf/use-state nil)
        selected    (deref selected*)
        skill       (when selected (ask/find-skill selected))
        enabled-map (mf/deref refs/resolved-skills-enabled)
        on-back     (mf/use-fn #(reset! selected* nil))
        toggle      (mf/use-fn
                     (fn [name checked]
                       (st/emit! (skst/set-skill-enabled name checked))))]
    (if skill
      (let [enabled? (get enabled-map (:name skill) true)]
        [:> skill-detail* {:skill skill
                           :enabled enabled?
                           :on-toggle #(toggle (:name skill) %)
                           :on-back on-back}])
      [:div {:class (stl/css :skills-tab)}
       (for [{:keys [category skills]} ask/catalog]
         [:div {:key category :class (stl/css :catalog-group)}
          [:div {:class (stl/css :catalog-group-label)} category]
          (for [{:keys [name label blurb mode]} skills]
            (let [enabled? (get enabled-map name true)
                  open     #(reset! selected* name)]
              [:div {:key name
                     :role "button"
                     :tab-index 0
                     :class (stl/css-case :catalog-card true :disabled (not enabled?))
                     :on-click open
                     :on-key-down (fn [event]
                                    (when (or (kbd/enter? event) (kbd/space? event))
                                      (dom/prevent-default event)
                                      (open)))}
               [:div {:class (stl/css :catalog-card-head)}
                [:span {:class (stl/css :catalog-name)} label]
                ;; The toggle sits inside the clickable card, so swallow its
                ;; click/keydown to keep them from opening the detail view.
                [:span {:class (stl/css :catalog-toggle)
                        :on-click dom/stop-propagation
                        :on-key-down dom/stop-propagation}
                 [:> switch* {:default-checked enabled?
                              :aria-label (dm/str (if enabled? "Disable " "Enable ") label)
                              :on-change #(toggle name %)}]]]
               [:div {:class (stl/css :catalog-desc)}
                [:span {:class (stl/css :catalog-blurb)} blurb]
                [:> mode-badge* {:mode mode}]]]))])])))

(mf/defc connect-empty*
  "Shown in place of the whole panel body (tabs included) when no AI provider
  is connected — a single call to action to set one up."
  {::mf/private true}
  []
  [:div {:class (stl/css :connect-empty)}
   [:div {:class (stl/css :connect-icon)}
    [:> i/icon* {:icon-id i/unplug :size "l"}]]
   [:div {:class (stl/css :connect-title)} "Ready when you are!"]
   [:p {:class (stl/css :connect-subtitle)}
    "Works with Claude, ChatGPT, and others. Bring your own key, no subscription through Penpot."]
   [:a {:class (stl/css :connect-button)
        :href "#/settings/integrations"}
    "Connect a provider"]])

(mf/defc ai-panel*
  ;; `file` / `page` are passed for future context-aware tabs; the Chat tab
  ;; reads live context from refs.
  [_props]
  (let [tab*      (hooks/use-persisted-state ::ai-panel-tab "chat")
        tab       (deref tab*)
        on-change (mf/use-fn #(reset! tab* %))

        on-close  (mf/use-fn #(st/emit! (dwaip/close-panel)))

        providers (mf/deref refs/ai-providers)
        pool      (mf/with-memo [providers] (provider-pool providers))

        tabs      (mf/with-memo []
                    [{:label "Chat" :id "chat"}
                     {:label "Skills" :id "skills"}])]

    ;; Providers are configured on the settings page; load them so we know
    ;; whether to show the panel or the connect-a-provider prompt. Skill state
    ;; (per-account + this file's overrides) drives which skills the agent
    ;; routes to, so load it up front too.
    (mf/with-effect []
      (st/emit! (dai/fetch-ai-providers)
                (skst/fetch-skill-states)))

    [:aside {:class (stl/css :ai-panel)}
     ;; Title header, sized to the workspace right-header band so the tabs
     ;; below line up with the sidebar's Design/Prototype/Inspect tabs.
     [:div {:class (stl/css :header)}
      [:span {:class (stl/css :title)} "Agent"]
      [:> icon-button* {:variant "ghost"
                        :aria-label "Close Agent panel"
                        :on-click on-close
                        :icon i/close}]]

     (if (empty? pool)
       ;; No provider connected — replace the whole body, tabs included.
       [:> connect-empty*]

       [:> tab-switcher* {:tabs tabs
                          :selected tab
                          :on-change on-change
                          :scrollable-panel true
                          :class (stl/css :tabs)}
        (case tab
          "chat"
          [:> chat-tab*]

          "skills"
          [:> skills-tab*])])]))
