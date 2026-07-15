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
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.markdown :as md]
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

;; How close to the end still counts as "at the bottom", in px. Slack for
;; sub-pixel scroll positions and the last message's bottom margin.
(def ^:private bottom-threshold 24)

(defn- tool-failed?
  [message]
  (contains? #{"error" "rejected"} (:status message)))

(mf/defc tool-group*
  "One run of consecutive tool calls, collapsed to a summary you can open.

  Grouping happens at render time rather than in state: the transcript is a
  flat vector appended to one message at a time, and folding runs into it
  would make every append inspect and rewrite its tail."
  {::mf/private true}
  [{:keys [messages]}]
  (let [failed?  (some tool-failed? messages)
        ;; a blocked or failed write is the thing the user most needs to see —
        ;; never hide it behind a click
        open*    (mf/use-state (boolean failed?))
        open?    (deref open*)
        detail-id (mf/use-id)
        on-toggle (mf/use-fn #(swap! open* not))
        n        (count messages)]
    [:div {:class (stl/css :tool-group)}
     [:button {:type "button"
               :class (stl/css-case :tool-group-summary true
                                    :tool-chip-error (boolean failed?))
               :aria-expanded open?
               :aria-controls detail-id
               :on-click on-toggle}
      [:span {:class (stl/css :tool-chip-glyph)} (if failed? "✕" "✓")]
      [:span {:class (stl/css :tool-chip-name)}
       (if (= 1 n)
         (:name (first messages))
         (dm/str "Ran " n " tools"))]
      [:> i/icon* {:icon-id (if open? i/arrow-down i/arrow-right)
                   :class (stl/css :tool-group-caret)}]]

     (when open?
       [:div {:id detail-id :class (stl/css :tool-group-detail)}
        (for [[i message] (map-indexed vector messages)]
          [:div {:key i :class (stl/css :tool-detail-row)}
           [:div {:class (stl/css-case :tool-detail-name true
                                       :tool-chip-error (tool-failed? message))}
            (:name message)]
           (when (tool-failed? message)
             [:div {:class (stl/css :tool-chip-detail)}
              (or (:rule message) (:detail message))])
           (when-let [input (:input message)]
             [:pre {:class (stl/css :tool-detail-payload)}
              (js/JSON.stringify (clj->js input) nil 2)])
           (when-let [result (:result message)]
             [:pre {:class (stl/css :tool-detail-payload)} result])])])]))

(mf/defc transcript*
  "The message list, split out so it can own its scroll ref.

  Follows the latest message only while the user is already at the bottom, so
  reading back mid-turn is never yanked away. `at-bottom?` state drives the
  pill; the layout effect reads a ref instead, so it sees the pre-append
  position and doesn't re-run when the state settles.

  Deliberately a scroll handler rather than `hooks/use-visible`: that hook
  reports `false` until its observer first fires, so a fresh transcript would
  never pin to the latest — and once the content outgrows the panel the
  sentinel is never seen, leaving it wrongly detached forever."
  {::mf/private true}
  [{:keys [messages busy?]}]
  (let [;; consecutive tool calls collapse into one row; `partition-by` on the
        ;; role predicate yields alternating runs of tools / everything else
        runs          (mf/with-memo [messages]
                        (->> (map-indexed vector messages)
                             (partition-by (fn [[_ m]] (= "tool" (:role m))))))

        scroll-ref    (mf/use-ref nil)
        ;; a fresh transcript starts pinned to the newest message
        at-bottom*    (mf/use-state true)
        at-bottom?    (deref at-bottom*)
        at-bottom-ref (hooks/use-update-ref at-bottom?)

        scroll-to-end (mf/use-fn
                       (fn []
                         (when-let [node (mf/ref-val scroll-ref)]
                           (dom/set-scroll-pos! node (.-scrollHeight node)))))

        on-scroll     (mf/use-fn
                       (fn [event]
                         (let [node    (dom/get-target event)
                               bottom? (< (- (.-scrollHeight node)
                                             (.-scrollTop node)
                                             (.-clientHeight node))
                                          bottom-threshold)]
                           ;; only on a real edge crossing — this fires per frame
                           (when (not= bottom? (mf/ref-val at-bottom-ref))
                             (reset! at-bottom* bottom?)))))]

    ;; land on the newest message when the transcript first appears
    (mf/with-layout-effect []
      (scroll-to-end))

    (mf/with-layout-effect [messages busy?]
      (when ^boolean (mf/ref-val at-bottom-ref)
        (scroll-to-end)))

    ;; The pill lives outside the scrolling element, anchored to this wrapper —
    ;; inside it, it would just scroll away with the messages.
    [:div {:class (stl/css :transcript-wrap)}
     [:div {:class (stl/css :transcript)
            :ref scroll-ref
            :on-scroll on-scroll
            :role "log"
            :aria-live "polite"
            ;; while a turn streams, every token would otherwise re-announce the
            ;; whole region; `aria-busy` holds announcements until it settles
            :aria-busy busy?
            :aria-relevant "additions text"}
      ;; `map-indexed` before the partition keeps `:key` on the stable
      ;; transcript index — appends are tail-only, so it never shifts
      (for [run runs]
        (if (= "tool" (:role (second (first run))))
          [:> tool-group* {:key (ffirst run) :messages (mapv second run)}]
          (for [[idx message] run]
            (let [user? (= "user" (:role message))]
              [:div {:key idx
                     :class (stl/css-case :message true
                                          :message-user user?
                                          :message-md (not user?))}
               ;; the user didn't write markdown — don't eat their asterisks
               (if user?
                 (:content message)
                 [:> md/markdown* {:text (:content message)}])]))))
      (when busy?
        [:div {:class (stl/css :message :message-thinking)} "Thinking…"])]

     (when-not at-bottom?
       [:> icon-button* {:class (stl/css :jump-to-latest)
                         :variant "primary"
                         :aria-label "Jump to latest message"
                         :on-click scroll-to-end
                         :icon i/arrow-down}])]))

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
        input-ref (mf/use-ref nil)

        on-input  (mf/use-fn
                   (fn [event]
                     (reset! input* (dom/get-value (dom/get-target event)))))

        picker-open* (mf/use-state false)
        picker-open? (deref picker-open*)
        picker-ref   (mf/use-ref nil)
        trigger-ref  (mf/use-ref nil)
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
                         (st/emit! (dwaip/clear-chat)))))

        on-cancel   (mf/use-fn #(st/emit! (dwaip/cancel-turn)))]

    ;; Grow the composer with its content up to a cap, then scroll. Reset to
    ;; "auto" first so it can shrink too (e.g. after send clears it); add the
    ;; borders back since border-box height excludes what scrollHeight measures.
    (mf/with-effect [input]
      (when-let [node (mf/ref-val input-ref)]
        (let [style (.-style node)]
          (set! (.-height style) "auto")
          (let [borders (- (.-offsetHeight node) (.-clientHeight node))
                height  (min 200 (+ (.-scrollHeight node) borders))]
            (set! (.-height style) (dm/str height "px"))))))

    ;; close the model picker on any click outside it, or on Escape — without
    ;; the latter it is a keyboard trap: openable by keyboard, not closable
    (mf/with-effect [picker-open?]
      (when ^boolean picker-open?
        (let [on-doc (fn [event]
                       (let [node (mf/ref-val picker-ref)]
                         (when (and node (not (.contains node (dom/get-target event))))
                           (reset! picker-open* false))))
              on-key (fn [event]
                       (when (= "Escape" (.-key event))
                         (dom/prevent-default event)
                         (reset! picker-open* false)
                         ;; focus would otherwise fall back to <body>
                         (some-> (mf/ref-val trigger-ref) dom/focus!)))]
          (.addEventListener js/document "pointerdown" on-doc)
          (.addEventListener js/document "keydown" on-key)
          (fn []
            (.removeEventListener js/document "pointerdown" on-doc)
            (.removeEventListener js/document "keydown" on-key)))))

    [:div {:class (stl/css :chat-tab)}
     ;; Current-file context surfaced to the agent: page + selection.
     [:div {:class (stl/css :context-chip)}
      [:span {:class (stl/css :context-page)} (:name page)]
      [:span {:class (stl/css :context-sep)} "·"]
      [:span {:class (stl/css :context-selection)} (selection-label selected objects)]]

     (if (seq messages)
       [:> transcript* {:messages messages :busy? busy?}]
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
        ;; the glyph is decorative — kept out of the accessible name, which
        ;; would otherwise read "multiplication x clear"
        [:button {:class (stl/css :chat-clear)
                  :type "button"
                  :disabled busy?
                  :title "Clear this file's chat history and start a fresh session"
                  :on-click on-clear}
         [:span {:aria-hidden true} "✕"]
         "Clear"]])

     [:div {:class (stl/css :composer)}
      [:div {:class (stl/css :model-picker)
             :ref picker-ref}
       [:button {:type "button"
                 :class (stl/css-case :model-picker-trigger true
                                      :model-picker-open picker-open?)
                 :ref trigger-ref
                 :aria-haspopup "listbox"
                 :aria-expanded picker-open?
                 :on-click on-toggle-picker}
        [:span {:class (stl/css :model-picker-current)}
         (if settings (:model settings) "No model")]
        [:> i/icon* {:icon-id i/arrow-down :class (stl/css :model-picker-caret)}]]

       (when picker-open?
         [:div {:class (stl/css :model-picker-menu)
                :role "listbox"}
          (for [[provider entries] (group-by #(:provider (second %)) (map-indexed vector pool))]
            [:div {:key provider :class (stl/css :model-picker-group)}
             [:div {:class (stl/css :model-picker-group-label)} provider]
             (for [[i entry] entries]
               [:button {:key i
                         :type "button"
                         :role "option"
                         :aria-selected (= i idx)
                         :class (stl/css-case :model-picker-option true
                                              :selected (= i idx))
                         :on-click #(do (reset! picked* i)
                                        (reset! picker-open* false))}
                (:model entry)])])
          [:a {:class (stl/css :model-picker-manage)
               :href "#/settings/integrations"}
           "Manage your models"]])]
      [:div {:class (stl/css :composer-row)}
       ;; deliberately NOT disabled while busy: only *sending* needs gating,
       ;; and being unable to even type through a long turn is the harshest
       ;; part of the current experience
       [:textarea {:class (stl/css :composer-input)
                   :ref input-ref
                   :placeholder "Ask the agent…"
                   :value input
                   :on-change on-input
                   :on-key-down on-key-down}]
       (if busy?
         ;; the DS has no stop glyph — `close` is the closest; a filled square
         ;; would need a new DS icon, which is its own change
         [:> icon-button* {:class (stl/css :composer-action)
                           :variant "destructive"
                           :aria-label "Stop generating"
                           :on-click on-cancel
                           :icon i/close}]
         [:> icon-button* {:class (stl/css :composer-action)
                           :variant "primary"
                           :aria-label "Send message"
                           :disabled (or (not settings) (empty? (str/trim input)))
                           :on-click send
                           :icon i/arrow-up}])]]]))

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

(mf/defc skill-row*
  "One catalog row: name + description, a muted \"Off\" pill when disabled, and a
  discreet ⋯ overflow menu (always visible, not hover-gated) holding the per-skill
  actions. Clicking the row body opens the detail view; the menu swallows its own
  clicks so it doesn't. Enable/Disable is instant; Fork / Promote to team are
  entry points only (disabled — wired by US #10 / US #12)."
  {::mf/private true}
  [{:keys [label blurb enabled on-open on-set-enabled]}]
  (let [show-menu?  (mf/use-state false)
        toggle-menu (mf/use-fn #(swap! show-menu? not))
        close-menu  (mf/use-fn #(reset! show-menu? false))
        open-detail (mf/use-fn
                     (mf/deps on-open)
                     (fn [event]
                       (when (or (nil? event) (kbd/enter? event) (kbd/space? event))
                         (some-> event dom/prevent-default)
                         (on-open))))
        on-enable   (mf/use-fn
                     (mf/deps on-set-enabled enabled)
                     (fn []
                       (on-set-enabled (not enabled))))]
    [:div {:class (stl/css-case :catalog-card true :disabled (not enabled))
           :role "button"
           :tab-index 0
           :on-click on-open
           :on-key-down open-detail}
     [:div {:class (stl/css :catalog-card-head)}
      [:span {:class (stl/css :catalog-name)} label]
      (when-not enabled
        [:span {:class (stl/css :catalog-off)} "Off"])
      ;; The menu lives inside the clickable row, so swallow its click/keydown to
      ;; keep them from opening the detail view.
      [:div {:class (stl/css :catalog-menu)
             :on-click dom/stop-propagation
             :on-key-down dom/stop-propagation}
       [:> icon-button* {:variant "ghost"
                         :icon i/menu
                         :aria-label (dm/str "Actions for " label)
                         :on-click toggle-menu}]
       [:& dropdown {:show @show-menu? :on-close close-menu}
        [:ul {:class (stl/css :skill-menu)}
         [:li {:class (stl/css :menu-option)
               :role "button"
               :on-click #(do (on-enable) (close-menu))}
          (if enabled "Disable" "Enable")]
         [:li {:class (stl/css-case :menu-option true :menu-option-disabled true)
               :aria-disabled true}
          "Fork"]
         [:li {:class (stl/css-case :menu-option true :menu-option-disabled true)
               :aria-disabled true}
          "Promote to team"]]]]]
     [:div {:class (stl/css :catalog-desc)}
      [:span {:class (stl/css :catalog-blurb)} blurb]]]))

(mf/defc skills-tab*
  "The built-in skills catalog: rows grouped by category. Each row opens its
  detail view on click and carries a discreet ⋯ menu (Enable/Disable + Fork /
  Promote entry points). Enable/Disable flips the skill for this file (per-user,
  instant) and drops a disabled skill from the agent's router — see
  agent-skills/resolve-enabled."
  {::mf/private true}
  []
  (let [selected*   (mf/use-state nil)
        selected    (deref selected*)
        skill       (when selected (ask/find-skill selected))
        enabled-map (mf/deref refs/resolved-skills-enabled)
        active      (mf/deref refs/skills-filter)
        on-back     (mf/use-fn #(reset! selected* nil))
        toggle      (mf/use-fn
                     (fn [name checked]
                       (st/emit! (skst/set-skill-enabled name checked))))
        ;; Under :enabled, hide disabled skills; drop groups left empty.
        visible?    (fn [name] (or (= active :all) (get enabled-map name true)))
        groups      (keep (fn [{:keys [category skills]}]
                            (let [rows (filterv #(visible? (:name %)) skills)]
                              (when (seq rows) [category rows])))
                          ask/catalog)]
    (if skill
      (let [enabled? (get enabled-map (:name skill) true)]
        [:> skill-detail* {:skill skill
                           :enabled enabled?
                           :on-toggle #(toggle (:name skill) %)
                           :on-back on-back}])
      [:div {:class (stl/css :skills-tab)}
       [:div {:class (stl/css :skills-filter)}
        (for [[opt lbl] [[:all "All"] [:enabled "Enabled"]]]
          [:button {:key (name opt)
                    :type "button"
                    :class (stl/css-case :skills-filter-option true
                                         :selected (= active opt))
                    :on-click #(st/emit! (dwaip/set-skills-filter opt))}
           lbl])]
       (if (seq groups)
         (for [[category rows] groups]
           [:div {:key category :class (stl/css :catalog-group)}
            [:div {:class (stl/css :catalog-group-label)} category]
            (for [{:keys [name label blurb]} rows]
              [:> skill-row* {:key name
                              :label label
                              :blurb blurb
                              :enabled (get enabled-map name true)
                              :on-open #(reset! selected* name)
                              :on-set-enabled #(toggle name %)}])])
         [:div {:class (stl/css :skills-empty)}
          "No enabled skills. Switch to All to see everything."])])))

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
