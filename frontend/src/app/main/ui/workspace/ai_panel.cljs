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
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.main.data.ai-providers :as dai]
   [app.main.data.workspace.agent :as agent]
   [app.main.data.workspace.agent-skills :as ask]
   [app.main.data.workspace.ai-panel :as dwaip]
   [app.main.data.workspace.design-doc :as dd]
   [app.main.data.workspace.elicitation :as el]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.skill-state :as skst]
   [app.main.data.workspace.slash-commands :as slc]
   [app.main.data.workspace.user-skills :as dusk]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.markdown :as md]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; --- Attachments
;;
;; Images are held as `{:mtype :data}` (raw base64) — the same shape the agent's
;; canonical message uses — and never touch Penpot's media storage. They ride
;; the turn and are gone on reload; that is the deal, not an oversight.

(def ^:private max-images 5)

;; Long-edge cap. 1568 is not a round number picked for feel: it is Anthropic's
;; standard-tier resolution limit, above which the provider downscales the image
;; server-side anyway — so a bigger upload buys nothing but bytes. (The
;; high-resolution tier goes to 2576, but the payload budget is the binding
;; constraint here, and 1568 is ample for a screenshot.) It also stops a phone
;; photo or a 4K capture dead.
(def ^:private max-image-edge 1568)

;; The cap alone does not save us — a 1440×900 screenshot is already under it,
;; and five of those as PNG are 66% of the request budget (measured, Phase 04).
;; Re-encoding is what actually pays. WebP over JPEG because it holds flat UI
;; colour and text edges far better at the same size, and text legibility is the
;; thing we cannot trade away: an unreadable screenshot is worse than none.
(def ^:private image-mtype "image/webp")
(def ^:private image-quality 0.85)

(def ^:private data-uri-re #"^data:([^;,]+);base64,(.*)$")

(defn- data-uri->image
  "`data:image/png;base64,iVBOR…` → `{:mtype \"image/png\" :data \"iVBOR…\"}`.
  The canonical model wants the two halves apart, because the providers
  disagree about how to put them back together."
  [duri]
  (when-let [[_ mtype data] (re-matches data-uri-re duri)]
    {:mtype mtype :data data}))

(defn- image-file?
  [file]
  (contains? cm/image-types (.-type ^js file)))

(defn- load-image
  "data-uri → a decoded `js/Image`, so its natural size can be measured."
  [duri]
  (rx/create
   (fn [subs]
     (let [img (js/Image.)]
       (obj/set! img "onload" #(do (rx/push! subs img) (rx/end! subs)))
       (obj/set! img "onerror" #(rx/error! subs (ex/error :type :validation
                                                          :code :unreadable-image)))
       (obj/set! img "src" duri)
       (fn [] (obj/set! img "src" ""))))))

(defn- recompress
  "Draws the image at (at most) `max-image-edge` and re-encodes it, → a blob."
  [img]
  (let [w      (.-naturalWidth ^js img)
        h      (.-naturalHeight ^js img)
        ;; only ever shrink: upscaling a small image would invent detail and
        ;; cost bytes for it
        scale  (min 1 (/ max-image-edge (max w h)))
        tw     (js/Math.round (* w scale))
        th     (js/Math.round (* h scale))
        canvas (js/OffscreenCanvas. tw th)
        ctx    (.getContext canvas "2d")]
    (.drawImage ^js ctx img 0 0 tw th)
    (rx/from (wapi/create-blob-from-canvas canvas #js {:type image-mtype
                                                       :quality image-quality}))))

(defn- prepare-image
  "File → a compact `{:mtype :data}`.

  The mimetype is read back off the re-encoded blob rather than assumed: if a
  browser cannot write WebP it silently hands back PNG, and claiming otherwise
  would put a lie on the wire."
  [file]
  (->> (wapi/read-file-as-data-url file)
       (rx/mapcat load-image)
       (rx/mapcat recompress)
       (rx/mapcat wapi/read-file-as-data-url)
       (rx/map data-uri->image)
       (rx/filter some?)))

(defn- read-images
  "Files → a stream of one `[{:mtype :data}]` vector, in the order given.
  `read-file-as-data-url` carries a Safari repeated-mimetype fix, which is why
  this goes through it rather than a bare FileReader."
  [files]
  (->> (rx/from files)
       ;; beicon's `mapcat` is rxjs `concatMap`, so the reads stay in order and
       ;; the user's first image is still first. `merge-map` would interleave.
       (rx/mapcat prepare-image)
       (rx/reduce conj [])))

(defn- image-src
  [{:keys [mtype data]}]
  (dm/str "data:" mtype ";base64," data))

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

(defn- model-key
  "Stable identity of a pool entry, so the chosen model is remembered by what it
  is rather than by its index (which shifts as providers/models change)."
  [{:keys [provider model]}]
  (str provider "/" model))

(defn- selected-settings
  "The pool entry the user remembered (`selected-key`), or the first — nil pool
  yields nil. Shared by the chat composer and the skill-creation flow."
  [pool selected-key]
  (let [idx (or (when selected-key
                  (->> pool
                       (keep-indexed (fn [i entry]
                                       (when (= selected-key (model-key entry)) i)))
                       first))
                0)]
    (nth pool idx nil)))

(defn- propose-mode
  "A default mode guessed from the description — a report-ish skill suggests, a
  direct-fix one auto-fixes, generative work is review. The user can override."
  [what]
  (let [w (str/lower (or what ""))]
    (cond
      (re-find #"rename|clean up|fix|correct|format|tidy" w) "autofix"
      (re-find #"build|create|generate|design|make|add|produce" w) "review"
      :else "suggest")))

(defn- skill-create-intent
  "When a chat message asks to create a skill, the described 'what' with the
  leading phrase stripped (possibly empty); nil when it isn't such a request.
  Tolerates natural lead-ins (\"let's…\", \"I want to…\", \"can you…\") before the
  verb, but ignores questions *about* skills (\"how do I…\"). Creation always
  happens in the Skills view (US #9), so Chat only routes there."
  [text]
  (let [t (str/trim (or text ""))]
    (when-not (re-find #"(?i)^(?:how|what|why|when|where|is|are|does)\b" t)
      (some-> (re-find #"(?i)(?:create|make|build|add|set\s?up|generate)\s+(?:me\s+)?(?:a\s+|an\s+|my\s+|new\s+|custom\s+)*skill\b(?:\s+(?:that|which|to|for|called|named|:|-))?\s*(.*)$" t)
              (second)
              (str/trim)))))

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

(mf/defc elicitation-question*
  "One question of the ask_user form: prompt + hint, then its input — option
  chips (single or multi), an Other… chip that expands an inline text field,
  a \"Decide for me\" chip, or a free textarea. `qstate` is this question's
  ui-state map; the handlers close over the question id."
  {::mf/private true}
  [{:keys [question qstate on-toggle on-other-text on-text]}]
  (let [{:keys [id type options hint optional allow-decide allow-other]} question
        selected   (or (:selected qstate) #{})
        other-on?  (contains? selected :other)
        multi?     (= "multi" type)
        text-type? (= "text" type)]
    [:div {:class (stl/css :eform-question)}
     [:div {:class (stl/css :eform-prompt)}
      (:question question)
      (when optional [:span {:class (stl/css :eform-optional)} " (optional)"])]
     (when (seq hint)
       [:div {:class (stl/css :eform-hint)} hint])

     (if text-type?
       [:textarea {:class (stl/css :eform-textarea)
                   :rows 3
                   :placeholder "Your answer…"
                   :value (or (:text qstate) "")
                   :on-change #(on-text id (dom/get-value (dom/get-target %)))}]

       [:div {:class (stl/css :eform-chips)}
        (for [opt options]
          (let [active? (contains? selected opt)]
            [:button {:key opt
                      :type "button"
                      :class (stl/css-case :eform-chip true
                                           :eform-chip-active active?)
                      :aria-pressed active?
                      :on-click #(on-toggle id type opt)}
             (when multi?
               [:span {:class (stl/css :eform-chip-mark) :aria-hidden true}
                (if active? "☑" "☐")])
             opt]))
        (when allow-other
          [:button {:type "button"
                    :class (stl/css-case :eform-chip true
                                         :eform-chip-other true
                                         :eform-chip-active other-on?)
                    :aria-pressed other-on?
                    :on-click #(on-toggle id type :other)}
           "Other…"])
        (when allow-decide
          [:button {:type "button"
                    :class (stl/css-case :eform-chip true
                                         :eform-chip-active (contains? selected :decide))
                    :aria-pressed (contains? selected :decide)
                    :on-click #(on-toggle id type :decide)}
           "Decide for me"])])

     (when (and (not text-type?) other-on?)
       [:input {:class (stl/css :eform-other-input)
                :type "text"
                :placeholder "Tell me in your words…"
                :value (or (:other-text qstate) "")
                ;; Enter must not bubble into anything submit-like; the form
                ;; sends only from its own button
                :on-key-down #(when (kbd/enter? %) (dom/prevent-default %))
                :on-change #(on-other-text id (dom/get-value (dom/get-target %)))}])]))

(mf/defc elicitation-form*
  "The ask_user interview, rendered at the tail of the transcript while a
  turn is paused on the user's answers. Submitting collapses it into a plain
  answers-summary bubble (appended by `dwaip/submit-form`) — the form itself
  is presentation state and vanishes once resolved.

  Remounted per form via `:key (hash form)` at the call site, so ui state
  never leaks from one interview into the next."
  {::mf/private true}
  [{:keys [form]}]
  (let [{:keys [title questions]} form
        ;; normalized once: snake_case JSON keys → the names the question
        ;; component reads; Other… defaults to on for choice questions
        questions (mf/with-memo [questions]
                    (mapv (fn [q]
                            (assoc q
                                   :allow-other (and (not= "text" (:type q))
                                                     (not (false? (:allow_other q))))
                                   :allow-decide (true? (:allow_decide q))))
                          questions))
        state*    (mf/use-state {})
        state     (deref state*)
        ready?    (el/complete? questions state)

        on-toggle     (mf/use-fn
                       (fn [id type opt]
                         (swap! state* update-in [id :selected]
                                #(el/toggle type % opt))))
        on-other-text (mf/use-fn
                       (fn [id text]
                         (swap! state* assoc-in [id :other-text] text)))
        on-text       (mf/use-fn
                       (fn [id text]
                         (swap! state* assoc-in [id :text] text)))

        on-submit (mf/use-fn
                   (mf/deps questions state ready?)
                   (fn []
                     (when ready?
                       (st/emit! (dwaip/submit-form
                                  (el/answers questions state)
                                  (el/summary questions state))))))]

    [:div {:class (stl/css :eform)}
     (when (seq title)
       [:div {:class (stl/css :eform-title)} title])
     (for [q questions]
       [:> elicitation-question* {:key (:id q)
                                  :question q
                                  :qstate (get state (:id q))
                                  :on-toggle on-toggle
                                  :on-other-text on-other-text
                                  :on-text on-text}])
     [:div {:class (stl/css :eform-actions)}
      [:button {:type "button"
                :class (stl/css :eform-submit)
                :disabled (not ready?)
                :on-click on-submit}
       "Send answers"]
      (when-not ready?
        [:span {:class (stl/css :eform-pending-hint)}
         "Answer the required questions to continue"])]]))

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
  [{:keys [messages busy? form]}]
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

    ;; `form` is in the deps: an interview appearing is new content to follow
    (mf/with-layout-effect [messages busy? form]
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
            (let [user?  (= "user" (:role message))
                  images (:images message)]
              [:div {:key idx
                     :class (stl/css-case :message true
                                          :message-user user?
                                          :message-md (not user?))}
               (when (seq images)
                 [:div {:class (stl/css :message-images)}
                  (for [[i image] (map-indexed vector images)]
                    [:img {:key i
                           :class (stl/css :message-image)
                           :src (image-src image)
                           :alt (dm/str "Attached image " (inc i))}])])
               ;; the user didn't write markdown — don't eat their asterisks
               (if user?
                 (:content message)
                 [:> md/markdown* {:text (:content message)}])]))))
      ;; while an interview is open the turn is busy *waiting on the user* —
      ;; a "Thinking…" bubble under the form would be a lie
      (when (some? form)
        [:> elicitation-form* {:key (hash form) :form form}])
      (when (and busy? (nil? form))
        [:div {:class (stl/css :message :message-thinking)} "Thinking…"])]

     (when-not at-bottom?
       [:> icon-button* {:class (stl/css :jump-to-latest)
                         :variant "primary"
                         :aria-label "Jump to latest message"
                         :on-click scroll-to-end
                         :icon i/arrow-down}])]))

(mf/defc chat-tab*
  {::mf/private true}
  [{:keys [on-create-skill]}]
  (let [messages  (mf/deref refs/ai-panel-messages)
        page      (mf/deref refs/workspace-page)
        selected  (mf/deref refs/selected-shapes)
        objects   (mf/deref refs/workspace-page-objects)
        providers (mf/deref refs/ai-providers)
        busy?     (mf/deref refs/ai-panel-busy?)
        usage     (mf/deref refs/ai-panel-usage)
        pending-form (mf/deref refs/ai-panel-pending-form)
        composer-seed (mf/deref refs/ai-panel-composer-seed)

        pool      (mf/with-memo [providers] (provider-pool providers))

        ;; The chosen model persists across remounts and browser sessions
        ;; (localStorage), keyed by identity not index so it survives the pool
        ;; changing. Until the user picks — or if the remembered model is gone —
        ;; default to the first entry.
        selected-model* (hooks/use-persisted-state ::selected-model nil)
        selected-key    (deref selected-model*)
        idx       (or (when selected-key
                        (->> pool
                             (keep-indexed (fn [i entry]
                                             (when (= selected-key (model-key entry)) i)))
                             first))
                      0)
        settings  (selected-settings pool selected-key)

        input*    (mf/use-state "")
        input     (deref input*)
        input-ref (mf/use-ref nil)

        ;; --- Slash menu. Open whenever the input *starts* with "/" and
        ;; something still matches; Esc dismisses it until the input changes.
        slash-entries    (mf/deref refs/slash-menu-entries)
        slash-dismissed* (mf/use-state false)
        slash-hi*        (mf/use-state 0)
        slash-menu-ref   (mf/use-ref nil)
        slash-query      (slc/query input)
        slash-matches    (mf/with-memo [slash-entries slash-query]
                           (when (some? slash-query)
                             (slc/filter-entries slash-entries slash-query)))
        slash-open?      (and (seq slash-matches)
                              (not (deref slash-dismissed*)))
        ;; clamped at read time: the match list shrinks as the user types
        slash-hi         (min (deref slash-hi*)
                              (dec (count slash-matches)))

        on-input  (mf/use-fn
                   (fn [event]
                     ;; typing reopens a dismissed menu and rests the highlight
                     (reset! slash-dismissed* false)
                     (reset! slash-hi* 0)
                     (reset! input* (dom/get-value (dom/get-target event)))))

        pick-entry (mf/use-fn
                    (fn [entry]
                      ;; fill the composer — the user still sends it themselves
                      (reset! input* (:insert entry))
                      (some-> (mf/ref-val input-ref) dom/focus!)))

        ;; attachments live with the composer, not in app state: they belong to
        ;; the message being written and die with it
        images*   (mf/use-state [])
        images    (deref images*)
        ;; a rejection the user needs told about (wrong type, over the cap);
        ;; cleared on the next successful attach
        attach-error* (mf/use-state nil)
        attach-error  (deref attach-error*)
        file-input-ref (mf/use-ref nil)
        dragging?*    (mf/use-state false)
        dragging?     (deref dragging?*)

        ;; whether the ACTIVE model can read an image. Not a provider-level
        ;; fact: within zhipu, glm-5v-turbo sees and glm-5.2 does not.
        sees?     (dai/vision? (:provider settings) (:model settings))

        add-files (mf/use-fn
                   (mf/deps images)
                   (fn [files]
                     (let [files    (vec files)
                           pictures (filterv image-file? files)
                           room     (- max-images (count images))
                           accepted (vec (take room pictures))
                           ;; report the *first* reason that applies rather
                           ;; than stacking messages: one clear sentence beats
                           ;; two competing ones
                           error    (cond
                                      (and (seq files) (empty? pictures))
                                      "Only images can be attached (PNG, JPEG or WebP)."

                                      (< (count pictures) (count files))
                                      "Some files were skipped — only images can be attached."

                                      (< (count accepted) (count pictures))
                                      (dm/str "Only " max-images " images can be attached at once."))]
                       (reset! attach-error* error)
                       (when (seq accepted)
                         (->> (read-images accepted)
                              (rx/subs! (fn [read]
                                          (swap! images* into read))
                                        (fn [_]
                                          (reset! attach-error*
                                                  "That image could not be read."))))))))

        on-pick   (mf/use-fn
                   (mf/deps add-files)
                   (fn [event]
                     (let [target (dom/get-target event)]
                       (add-files (array-seq (.-files ^js target)))
                       ;; re-picking the same file must re-fire `change`
                       (dom/set-value! target ""))))

        on-attach (mf/use-fn
                   (fn []
                     (some-> (mf/ref-val file-input-ref) (.click))))

        on-remove (mf/use-fn
                   (fn [event]
                     (let [i (-> (dom/get-current-target event)
                                 (dom/get-data "index")
                                 (d/parse-integer))]
                       (when (some? i)
                         (swap! images* (fn [v]
                                          (vec (concat (subvec v 0 i)
                                                       (subvec v (inc i))))))))))

        on-paste  (mf/use-fn
                   (mf/deps add-files sees?)
                   (fn [event]
                     ;; the canvas paste handler already ignores TEXTAREA
                     ;; targets, so this cannot race paste-image-onto-canvas
                     (let [items (some-> (.-clipboardData ^js event) (.-items))
                           files (when items
                                   (->> (array-seq items)
                                        (filter #(= "file" (.-kind ^js %)))
                                        (keep #(.getAsFile ^js %))
                                        (vec)))]
                       (when (and sees? (seq files))
                         ;; only swallow the paste when it really is an image;
                         ;; otherwise pasting text into the box would break
                         (dom/prevent-default event)
                         (add-files files)))))

        on-drag-over (mf/use-fn
                      (mf/deps sees?)
                      (fn [event]
                        (when sees?
                          (dom/prevent-default event)
                          (reset! dragging?* true))))

        on-drag-leave (mf/use-fn (fn [_] (reset! dragging?* false)))

        on-drop   (mf/use-fn
                   (mf/deps add-files sees?)
                   (fn [event]
                     (dom/prevent-default event)
                     (reset! dragging?* false)
                     (when sees?
                       (add-files (array-seq (.. ^js event -dataTransfer -files))))))

        picker-open* (mf/use-state false)
        picker-open? (deref picker-open*)
        picker-ref   (mf/use-ref nil)
        trigger-ref  (mf/use-ref nil)
        on-toggle-picker (mf/use-fn #(swap! picker-open* not))

        send      (mf/use-fn
                   (mf/deps input images settings busy? page selected objects on-create-skill)
                   (fn []
                     (let [text (str/trim input)]
                       ;; an image on its own is a legitimate message — "what is
                       ;; this?" is often carried entirely by the picture
                       (when (and (or (seq text) (seq images)) (not busy?))
                         (if-let [seed (skill-create-intent text)]
                           ;; "create a skill …" → hand off to the Skills view
                           ;; creation flow rather than sending to the agent.
                           (do (when on-create-skill (on-create-skill seed))
                               (reset! input* "")
                               (reset! images* [])
                               (reset! attach-error* nil))
                           (when settings
                             (let [context {:file (:name page)
                                            :page (:name page)
                                            :selection (->> selected
                                                            (map #(select-keys (get objects %) [:name :type]))
                                                            (vec))}]
                               (st/emit! (dwaip/send-message settings text context images))
                               (reset! input* "")
                               (reset! images* [])
                               (reset! attach-error* nil))))))))

        on-key-down (mf/use-fn
                     (mf/deps send slash-open? slash-matches slash-hi)
                     (fn [event]
                       (cond
                         ;; menu first: while it is open, Enter picks — it
                         ;; must never send half a command to the agent
                         slash-open?
                         (cond
                           (kbd/down-arrow? event)
                           (do (dom/prevent-default event)
                               (reset! slash-hi* (mod (inc slash-hi) (count slash-matches))))

                           (kbd/up-arrow? event)
                           (do (dom/prevent-default event)
                               (reset! slash-hi* (mod (dec slash-hi) (count slash-matches))))

                           (or (kbd/enter? event) (kbd/tab? event))
                           (do (dom/prevent-default event)
                               (pick-entry (nth slash-matches slash-hi)))

                           (kbd/esc? event)
                           (do (dom/prevent-default event)
                               (reset! slash-dismissed* true)))

                         (and (= "Enter" (.-key event))
                              (not (.-shiftKey event)))
                         (do (dom/prevent-default event)
                             (send)))))

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

    ;; consume a composer seed left by the vibes view: prefill, clear, focus.
    ;; Prefill-not-send on purpose — the user presses Enter themselves.
    (mf/with-effect [composer-seed]
      (when (seq composer-seed)
        (reset! input* composer-seed)
        (st/emit! (dwaip/seed-composer nil))
        (some-> (mf/ref-val input-ref) dom/focus!)))

    ;; keep the keyboard-highlighted slash option in view as the arrows move
    ;; it (an effect, not part of the key handler: the DOM only has the new
    ;; aria-selected after the state settles)
    (mf/with-effect [slash-hi slash-open?]
      (when slash-open?
        (some-> (mf/ref-val slash-menu-ref)
                (.querySelector "[aria-selected=\"true\"]")
                (.scrollIntoView #js {:block "nearest"}))))

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

     (if (or (seq messages) (some? pending-form))
       [:> transcript* {:messages messages :busy? busy? :form pending-form}]
       [:div {:class (stl/css :transcript-empty)}
        [:> i/icon* {:icon-id i/bot-message-square
                     :size "m"
                     :class (stl/css :bot-icon)}]])

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
      (when (seq images)
        [:div {:class (stl/css :composer-attachments)}
         (for [[i image] (map-indexed vector images)]
           [:div {:key i :class (stl/css :composer-attachment)}
            [:img {:class (stl/css :composer-attachment-img)
                   :src (image-src image)
                   :alt (dm/str "Attached image " (inc i))}]
            [:button {:type "button"
                      :class (stl/css :composer-attachment-remove)
                      :aria-label (dm/str "Remove attached image " (inc i))
                      :data-index (dm/str i)
                      :on-click on-remove}
             [:span {:aria-hidden true} "✕"]]])])

      ;; the thumbnails stay put when the model changes — the agent notes them
      ;; as omitted rather than dropping them silently — but saying so here
      ;; beats letting the user find out from the reply
      (when (and (seq images) (not sees?))
        [:div {:class (stl/css :composer-hint)}
         (dm/str (:model settings) " can't read images — they won't be sent.")])

      (when attach-error
        [:div {:class (stl/css :composer-hint)} attach-error])

      ;; The send/stop control lives inside the textarea (bottom-right) and
      ;; attach mirrors it on the left; the input reserves room on both sides so
      ;; text never runs under either.
      [:div {:class (stl/css-case :composer-row true
                                  :composer-row-dragging dragging?)
             :on-drag-over on-drag-over
             :on-drag-leave on-drag-leave
             :on-drop on-drop}
       (when slash-open?
         [:div {:class (stl/css :slash-menu)
                :ref slash-menu-ref
                :role "listbox"
                :aria-label "Commands and skills"}
          (for [[i entry] (map-indexed vector slash-matches)]
            [:button {:key (:command entry)
                      :type "button"
                      :role "option"
                      :aria-selected (= i slash-hi)
                      :class (stl/css-case :slash-option true
                                           :slash-option-active (= i slash-hi))
                      ;; pointerdown would steal focus from the textarea and
                      ;; blur it before click lands — keep the caret alive
                      :on-pointer-down dom/prevent-default
                      :on-pointer-enter #(reset! slash-hi* i)
                      :on-click #(pick-entry entry)}
             [:span {:class (stl/css :slash-command)} (dm/str "/" (:command entry))]
             [:span {:class (stl/css :slash-title)} (:title entry)]
             (when-let [detail (:detail entry)]
               [:span {:class (stl/css :slash-detail)} detail])])])
       [:input {:type "file"
                :ref file-input-ref
                :class (stl/css :composer-file-input)
                :accept dwm/accept-image-types
                :multiple true
                :on-change on-pick}]
       ;; deliberately NOT disabled while busy: only *sending* needs gating,
       ;; and being unable to even type through a long turn is the harshest
       ;; part of the current experience
       [:textarea {:class (stl/css :composer-input)
                   :ref input-ref
                   ;; one row so the auto-grow's "reset to auto" baseline (which
                   ;; scrollHeight is measured against) is a single line, not the
                   ;; browser default of two
                   :rows 1
                   :placeholder "Ask the agent…"
                   :value input
                   :on-change on-input
                   :on-paste on-paste
                   :on-key-down on-key-down}]
       [:button {:type "button"
                 :class (stl/css :composer-attach)
                 :aria-label (if sees? "Attach images" "This model can't read images")
                 :title (if sees?
                          (dm/str "Attach images (up to " max-images ")")
                          (dm/str (:model settings) " can't read images"))
                 ;; disabled, not hidden: a control that vanishes when you
                 ;; switch models reads as a bug, not a capability
                 :disabled (or (not settings) (not sees?)
                               (>= (count images) max-images))
                 :on-click on-attach}
        [:> i/icon* {:icon-id i/img}]]
       (if busy?
         [:button {:type "button"
                   :class (stl/css :composer-stop)
                   :aria-label "Stop generating"
                   :on-click on-cancel}
          [:> i/icon* {:icon-id i/close}]]
         [:button {:type "button"
                   :class (stl/css :composer-send)
                   :aria-label "Send message"
                   ;; an image on its own is a legitimate message
                   :disabled (or (not settings)
                                 (and (empty? (str/trim input)) (empty? images)))
                   :on-click send}
          [:> i/icon* {:icon-id i/forward}]])]

      ;; Model picker below the composer — half width, right-aligned, borderless.
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
                         :on-click #(do (reset! selected-model* (model-key entry))
                                        (reset! picker-open* false))}
                (:model entry)])])
          [:a {:class (stl/css :model-picker-manage)
               :href "#/settings/integrations"}
           "Manage your models"]])]]]))

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
  "Detail for one catalog skill, shown in place of the list within the Skills
  view. Back navigation lives in the panel header (US #35), so there is no in-body
  back button here. Carries the same on/off toggle as the list card; `enabled` is
  the resolved state and `on-toggle` receives the new boolean."
  {::mf/private true}
  [{:keys [skill enabled on-toggle]}]
  (let [{:keys [label category mode example what]} skill]
    [:div {:class (stl/css :skill-detail)}
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

(mf/defc vibes-view*
  "The project vibes document: rendered markdown with Edit / Re-run interview /
  Delete, an editor with the same size cap the tool enforces, and an empty
  state that starts the interview. Deleting is a two-click inline confirm —
  and it goes through the changes pipeline, so it is undoable like any edit.
  `on-interview` seeds the chat composer with the vibes trigger and switches
  to the chat view."
  {::mf/private true}
  [{:keys [on-interview]}]
  (let [doc       (mf/deref dd/doc-ref)
        editing?* (mf/use-state false)
        editing?  (deref editing?*)
        draft*    (mf/use-state "")
        draft     (deref draft*)
        confirm?* (mf/use-state false)
        confirm?  (deref confirm?*)

        problem   (when editing? (dd/doc-problem draft))

        on-edit   (mf/use-fn
                   (mf/deps doc)
                   (fn []
                     (reset! draft* (or doc ""))
                     (reset! confirm?* false)
                     (reset! editing?* true)))
        on-draft  (mf/use-fn
                   #(reset! draft* (dom/get-value (dom/get-target %))))
        on-cancel (mf/use-fn #(reset! editing?* false))
        on-save   (mf/use-fn
                   (mf/deps draft problem)
                   (fn []
                     (when-not problem
                       (when-let [file-id (:current-file-id @st/state)]
                         (st/emit! (dd/set-doc file-id (str/trim draft)))
                         (reset! editing?* false)))))
        on-delete (mf/use-fn
                   (mf/deps confirm?)
                   (fn []
                     (if confirm?
                       (do (when-let [file-id (:current-file-id @st/state)]
                             (st/emit! (dd/clear-doc file-id)))
                           (reset! confirm?* false))
                       (reset! confirm?* true))))]

    (cond
      editing?
      [:div {:class (stl/css :vibes-view)}
       [:textarea {:class (stl/css :vibes-editor)
                   :value draft
                   :rows 18
                   :on-change on-draft}]
       [:div {:class (stl/css-case :vibes-counter true
                                   :vibes-counter-over (some? problem))}
        (dm/str (count draft) " / " dd/max-doc-chars)]
       (when problem
         [:p {:class (stl/css :vibes-problem)} problem])
       [:div {:class (stl/css :vibes-actions)}
        [:button {:type "button"
                  :class (stl/css :vibes-button-primary)
                  :disabled (some? problem)
                  :on-click on-save}
         "Save"]
        [:button {:type "button"
                  :class (stl/css :vibes-button)
                  :on-click on-cancel}
         "Cancel"]]]

      (some? doc)
      [:div {:class (stl/css :vibes-view)}
       [:div {:class (stl/css :vibes-doc :message-md)}
        [:> md/markdown* {:text doc}]]
       [:div {:class (stl/css :vibes-actions)}
        [:button {:type "button" :class (stl/css :vibes-button) :on-click on-edit}
         "Edit"]
        [:button {:type "button" :class (stl/css :vibes-button) :on-click on-interview}
         "Re-run interview"]
        [:button {:type "button"
                  :class (stl/css-case :vibes-button true
                                       :vibes-button-danger true
                                       :vibes-button-confirm confirm?)
                  :on-click on-delete}
         (if confirm? "Really delete? (undoable)" "Delete")]]]

      :else
      [:div {:class (stl/css :vibes-empty)}
       [:div {:class (stl/css :vibes-empty-title)} "No vibes set yet"]
       [:p {:class (stl/css :vibes-empty-text)}
        "A short interview pins down this project's design direction — vibe, audience, platform, do/don't — as a design.md on this file. The agent then designs against it, and collaborators share it."]
       [:button {:type "button"
                 :class (stl/css :vibes-button-primary)
                 :on-click on-interview}
        "Set the vibes"]])))

(mf/defc skills-tab*
  "The built-in skills catalog: rows grouped by category. Each row opens its
  detail view on click and carries a discreet ⋯ menu (Enable/Disable + Fork /
  Promote entry points). Enable/Disable flips the skill for this file (per-user,
  instant) and drops a disabled skill from the agent's router — see
  agent-skills/resolve-enabled.

  Controlled by the panel: `selected` is the open skill's name (nil = list),
  `on-select` opens one, `on-create` opens the creation flow (US #9). Back
  navigation lives in the panel header (US #35)."
  {::mf/private true}
  [{:keys [selected on-select on-create on-open-vibes]}]
  (let [vibes-set?  (some? (mf/deref dd/doc-ref))
        catalog     (mf/deref refs/skills-catalog)
        skill       (when selected
                      (some (fn [{:keys [category skills]}]
                              (some #(when (= selected (:name %)) (assoc % :category category)) skills))
                            catalog))
        enabled-map (mf/deref refs/resolved-skills-enabled)
        active      (mf/deref refs/skills-filter)
        toggle      (mf/use-fn
                     (fn [name checked]
                       (st/emit! (skst/set-skill-enabled name checked))))
        ;; Under :enabled, hide disabled skills; drop groups left empty.
        visible?    (fn [name] (or (= active :all) (get enabled-map name true)))
        groups      (keep (fn [{:keys [category skills]}]
                            (let [rows (filterv #(visible? (:name %)) skills)]
                              (when (seq rows) [category rows])))
                          catalog)]
    (if skill
      (let [enabled? (get enabled-map (:name skill) true)]
        [:> skill-detail* {:skill skill
                           :enabled enabled?
                           :on-toggle #(toggle (:name skill) %)}])
      [:div {:class (stl/css :skills-tab)}
       ;; Project vibes: pinned above the catalog — it is file-level state,
       ;; not a toggleable skill, so it gets a place rather than a row.
       [:button {:type "button"
                 :class (stl/css :vibes-card)
                 :on-click on-open-vibes}
        [:span {:class (stl/css :vibes-card-title)} "✦ Project vibes"]
        [:span {:class (stl/css :vibes-card-status)}
         (if vibes-set?
           "Set — view or edit the design.md"
           "Not set — run the kickoff interview")]]
       [:div {:class (stl/css :skills-toolbar)}
        [:div {:class (stl/css :skills-filter)}
         (for [[opt lbl] [[:all "All"] [:enabled "Enabled"]]]
          [:button {:key (name opt)
                    :type "button"
                    :class (stl/css-case :skills-filter-option true
                                         :selected (= active opt))
                    :on-click #(st/emit! (dwaip/set-skills-filter opt))}
           lbl])]
        [:button {:class (stl/css :create-skill-btn)
                  :type "button"
                  :on-click on-create}
         "+ Create skill"]]
       (if (seq groups)
         (for [[category rows] groups]
           [:div {:key category :class (stl/css :catalog-group)}
            [:div {:class (stl/css :catalog-group-label)} category]
            (for [{:keys [name label blurb]} rows]
              [:> skill-row* {:key name
                              :label label
                              :blurb blurb
                              :enabled (get enabled-map name true)
                              :on-open #(on-select name)
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

(mf/defc skill-create*
  "The guided creation flow (US #9): capture what / trigger / mode (with a
  proposed default), then generate the skill doc and persist it. Lives in the
  Skills view; the header owns the back nav. `seed` prefills the description when
  started from Chat. `on-created` closes the flow (the new card shows in the list)."
  {::mf/private true}
  [{:keys [settings seed on-created]}]
  (let [what*     (mf/use-state (or seed ""))
        trigger*  (mf/use-state "")
        mode*     (mf/use-state nil)
        status*   (mf/use-state :idle)

        what      (deref what*)
        trigger   (deref trigger*)
        proposed  (propose-mode what)
        mode      (or (deref mode*) proposed)
        status    (deref status*)
        busy?     (= status :generating)
        ready?    (and (seq (str/trim what)) (some? settings) (not busy?))

        on-what    (mf/use-fn #(reset! what* (dom/get-value (dom/get-target %))))
        on-trigger (mf/use-fn #(reset! trigger* (dom/get-value (dom/get-target %))))

        submit
        (mf/use-fn
         (mf/deps what trigger mode settings busy?)
         (fn []
           (when (and (seq (str/trim what)) settings (not busy?))
             (reset! status* :generating)
             (st/emit!
              (dusk/create-from-answers
               settings
               {:what (str/trim what) :trigger (str/trim trigger) :mode mode}
               {:on-success (fn [created] (reset! status* :idle) (on-created created))
                :on-error   (fn [_] (reset! status* :error))})))))]

    (if (nil? settings)
      [:div {:class (stl/css :skill-create)}
       [:p {:class (stl/css :create-guard)}
        "Connect an AI model to create skills."]
       [:a {:class (stl/css :connect-button) :href "#/settings/integrations"}
        "Connect a provider"]]

      [:div {:class (stl/css :skill-create)}
       [:p {:class (stl/css :create-intro)}
        "Describe the skill in your words — the agent writes the playbook."]

       [:label {:class (stl/css :create-label)} "What should it do?"]
       [:textarea {:class (stl/css :create-input)
                   :value what
                   :placeholder "e.g. Check all the copy on a screen against a tone of voice I describe, and flag anything that doesn't match."
                   :disabled busy?
                   :on-change on-what}]

       [:label {:class (stl/css :create-label)} "When should it trigger? An example phrase."]
       [:input {:class (stl/css :create-input)
                :value trigger
                :placeholder "e.g. Check the tone of voice on this screen."
                :disabled busy?
                :on-change on-trigger}]

       [:label {:class (stl/css :create-label)} "Mode"]
       [:div {:class (stl/css :create-modes)}
        (for [[m lbl] [["suggest" "🔍 Suggest"] ["review" "✏️ Review"] ["autofix" "⚡ Auto-fix"]]]
          [:button {:key m
                    :type "button"
                    :class (stl/css-case :create-mode true :selected (= m mode))
                    :disabled busy?
                    :on-click #(reset! mode* m)}
           lbl
           (when (= m proposed)
             [:span {:class (stl/css :create-mode-hint)} " · suggested"])])]

       (when (= status :error)
         [:p {:class (stl/css :create-error)}
          "Couldn't generate that skill — try rephrasing and create again."])

       [:button {:class (stl/css :create-submit)
                 :type "button"
                 :disabled (not ready?)
                 :on-click submit}
        (if busy? "Generating…" "Create skill")]])))

(mf/defc ai-panel*
  "The Agent panel shell. Chat is the home surface and fills the body; Skills is a
  full-panel view reached from a muted header icon (US #35). The view is in-memory
  and defaults to chat, so closing and reopening the panel always lands on chat
  (US #2) — there are no tabs. The panel is closed from the workspace toggle
  (Alt+B), so the header carries no close button.

  Skills navigation is two levels — the list and one skill's detail — both owned
  here so the header back pops a single level: detail → list → chat."
  [_props]
  (let [view*       (mf/use-state :chat)
        view        (deref view*)
        skills?     (= view :skills)

        ;; The open skill within the Skills view (nil = the list) and whether the
        ;; create flow is open. Both lifted here so the header back pops one level.
        skill*      (mf/use-state nil)
        skill       (deref skill*)
        creating*   (mf/use-state false)
        creating?   (deref creating*)
        ;; the Project vibes view within Skills (a third leaf next to
        ;; detail/create — the header back pops it to the list)
        vibes?*     (mf/use-state false)
        vibes?      (deref vibes?*)
        ;; description carried over when creation is started from Chat (US #9).
        seed*       (mf/use-state nil)

        open-skills (mf/use-fn (fn [] (reset! creating* false) (reset! skill* nil) (reset! vibes?* false) (reset! view* :skills)))
        on-select   (mf/use-fn #(reset! skill* %))
        open-create (mf/use-fn (fn [] (reset! seed* nil) (reset! creating* true)))
        on-created  (mf/use-fn #(reset! creating* false))
        open-vibes  (mf/use-fn #(reset! vibes?* true))
        ;; "Set the vibes" / "Re-run interview": hand the chat a ready-to-send
        ;; trigger and land there — the user presses Enter themselves.
        on-vibes-interview
        (mf/use-fn
         (fn []
           (st/emit! (dwaip/seed-composer "Set the design vibes for this project."))
           (reset! vibes?* false)
           (reset! view* :chat)))
        ;; Chat "create a skill …" → take the user to the Skills create flow with
        ;; the described "what" prefilled.
        on-create-skill (mf/use-fn
                         (fn [seed]
                           (reset! seed* seed)
                           (reset! skill* nil)
                           (reset! creating* true)
                           (reset! view* :skills)))
        ;; Pop one level: create/detail → list → chat. Branch on the deref'd
        ;; `skill`/`creating?` (in deps) — reading the atoms from a no-deps
        ;; callback captures their initial nil/false and jumps straight to chat.
        on-back     (mf/use-fn
                     (mf/deps skill creating? vibes?)
                     (fn []
                       (cond
                         creating?     (reset! creating* false) ;; create → list
                         vibes?        (reset! vibes?* false)   ;; vibes → list
                         (some? skill) (reset! skill* nil)      ;; detail → list
                         :else         (reset! view* :chat))))  ;; list → chat

        providers   (mf/deref refs/ai-providers)
        pool        (mf/with-memo [providers] (provider-pool providers))
        selected-model* (hooks/use-persisted-state ::selected-model nil)
        settings    (selected-settings pool (deref selected-model*))]

    ;; Providers are configured on the settings page; load them so we know
    ;; whether to show the chat or the connect-a-provider prompt. Skill state
    ;; (per-account + this file's overrides) and the user's created skills drive
    ;; which skills the agent routes to, so load them up front too.
    (mf/with-effect []
      (st/emit! (dai/fetch-ai-providers)
                (skst/fetch-skill-states)
                (dusk/fetch-user-skills)))

    [:aside {:class (stl/css :ai-panel)}
     ;; Adaptive header (sized to the workspace right-header band): chat shows the
     ;; "Agent" title + the muted Skills icon; Skills swaps those for a back arrow
     ;; and a title ("Skills" for the list, "Skill info" for a detail).
     [:div {:class (stl/css :header)}
      (if skills?
        [:div {:class (stl/css :header-lead)}
         [:> icon-button* {:variant "ghost"
                           :aria-label "Back"
                           :on-click on-back
                           :icon i/arrow-left}]
         [:span {:class (stl/css :title)}
          (cond creating? "New skill" vibes? "Project vibes" skill "Skill info" :else "Skills")]]
        [:span {:class (stl/css :title)} "Agent"])
      (when-not skills?
        [:div {:class (stl/css :header-actions)}
         [:> icon-button* {:variant "ghost"
                           :aria-label "Open Skills"
                           :on-click open-skills
                           :icon i/list-checks}]])]

     [:div {:class (stl/css :body)}
      (cond
        ;; Skills is a static catalog — reachable even before a provider is set up.
        skills?       (cond
                        creating? [:> skill-create* {:settings settings :seed (deref seed*) :on-created on-created}]
                        vibes?    [:> vibes-view* {:on-interview on-vibes-interview}]
                        :else     [:> skills-tab* {:selected skill
                                                   :on-select on-select
                                                   :on-create open-create
                                                   :on-open-vibes open-vibes}])
        (empty? pool) [:> connect-empty*]
        :else         [:> chat-tab* {:on-create-skill on-create-skill}])]]))
