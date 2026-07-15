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
   [app.common.math :as mth]
   [app.common.media :as cm]
   [app.common.uuid :as uuid]
   [app.main.data.ai-providers :as dai]
   [app.main.data.workspace.agent :as agent]
   [app.main.data.workspace.agent-skills :as ask]
   [app.main.data.workspace.ai-panel :as dwaip]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.skill-state :as skst]
   [app.main.data.workspace.user-skills :as dusk]
   [app.main.data.workspace.zoom :as dwz]
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

(defn- chat-context
  "The context payload riding a user message: current page + selection. Shared
  by the composer send, Fix-it-now and the pending-fix drain."
  [page selected objects]
  {:file (:name page)
   :page (:name page)
   :selection (->> selected
                   (map #(select-keys (get objects %) [:name :type]))
                   (vec))})

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
      (when busy?
        [:div {:class (stl/css :message :message-thinking)} "Thinking…"])]

     (when-not at-bottom?
       [:> icon-button* {:class (stl/css :jump-to-latest)
                         :variant "primary"
                         :aria-label "Jump to latest message"
                         :on-click scroll-to-end
                         :icon i/arrow-down}])]))

;; --- Affected strip (auto-fix watcher)
;;
;; Surfaces the live violations set kept by the data-layer watcher
;; (`dwaip/start-watcher`): a one-line summary between the context chip and
;; the transcript, expandable into a per-rule breakdown. Zero violations
;; renders nothing at all — the strip is presence, not chrome.

(def ^:private rule-labels
  {"layer-naming"      "Layer naming"
   "token-only-colors" "Token-only colors"})

(def ^:private max-strip-shapes 8)

(defn- group-violations
  "violations → [{:rule :label :n :shapes}] — biggest group first, then by
  rule name so equal counts render stably."
  [violations]
  (->> violations
       (group-by :rule)
       (map (fn [[rule vs]]
              {:rule   rule
               :label  (get rule-labels rule rule)
               :n      (count vs)
               :shapes vs}))
       (sort-by (juxt (comp - :n) :rule))
       (vec)))

(defn- strip-summary
  "\"12 layers need attention · 2 rules\" — layers counted distinct (one shape
  can violate several rules)."
  [violations]
  (let [shapes (count (into #{} (map :shapeId) violations))
        rules  (count (into #{} (map :rule) violations))]
    (dm/str shapes (if (= 1 shapes) " layer needs attention · " " layers need attention · ")
            rules (if (= 1 rules) " rule" " rules"))))

(mf/defc affected-strip*
  {::mf/private true}
  [{:keys [on-fix]}]
  (let [violations (mf/deref refs/ai-panel-violations)
        expanded*  (mf/use-state false)
        expanded?  (deref expanded*)
        on-toggle  (mf/use-fn #(swap! expanded* not))

        on-fix-all (mf/use-fn
                    (mf/deps on-fix violations)
                    (fn [] (when on-fix (on-fix violations))))

        ;; click a layer name → select + zoom on canvas, so the strip doubles
        ;; as navigation to the offending shape
        on-shape-click
        (mf/use-fn
         (fn [event]
           (when-let [id (-> (dom/get-current-target event)
                             (dom/get-data "id")
                             (uuid/parse*))]
             (st/emit! (dws/select-shape id)
                       dwz/zoom-to-selected-shape))))]
    (when (seq violations)
      [:div {:class (stl/css :affected-strip)}
       [:div {:class (stl/css :affected-row)}
        [:button {:type "button"
                  :class (stl/css :affected-summary)
                  :aria-expanded expanded?
                  :on-click on-toggle}
         [:span {:aria-hidden true :class (stl/css :affected-bolt)} "⚡"]
         [:span {:class (stl/css :affected-text)} (strip-summary violations)]
         [:span {:aria-hidden true :class (stl/css :affected-chevron)}
          (if expanded? "▾" "▸")]]
        [:button {:type "button"
                  :class (stl/css :affected-fix)
                  :title "Ask the agent to fix everything listed, in this conversation"
                  :on-click on-fix-all}
         "✦ Fix it now"]]
       (when expanded?
         [:div {:class (stl/css :affected-detail)}
          (for [{:keys [rule label n shapes]} (group-violations violations)]
            [:div {:key rule :class (stl/css :affected-group)}
             [:div {:class (stl/css :affected-group-head)}
              [:span {:class (stl/css :affected-group-label)} label]
              [:span {:class (stl/css :affected-group-count)} n]
              (when on-fix
                [:button {:type "button"
                          :class (stl/css :affected-group-fix)
                          :title (dm/str "Fix only the " label " violations")
                          ;; render-time closure, deliberately: hooks cannot
                          ;; live inside `for`, and the list is tiny
                          :on-click (fn [_] (on-fix shapes))}
                 "Fix"])]
             [:ul {:class (stl/css :affected-shapes)}
              (for [v (take max-strip-shapes shapes)]
                [:li {:key (:shapeId v)}
                 [:button {:type "button"
                           :class (stl/css :affected-shape)
                           :title (:reason v)
                           :data-id (:shapeId v)
                           :on-click on-shape-click}
                  ;; ✦ = flagged by the semantic tick, not the native scan
                  (when (:semantic v)
                    [:span {:aria-hidden true :class (stl/css :affected-semantic)} "✦ "])
                  (:shapeName v)]])
              (when (> n max-strip-shapes)
                [:li {:class (stl/css :affected-more)}
                 (dm/str "+" (- n max-strip-shapes) " more")])]])])])))

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
        pending-fix (mf/deref refs/ai-panel-pending-fix)

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

        on-input  (mf/use-fn
                   (fn [event]
                     (reset! input* (dom/get-value (dom/get-target event)))))

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
                             (st/emit! (dwaip/send-message settings text (chat-context page selected objects) images))
                             (reset! input* "")
                             (reset! images* [])
                             (reset! attach-error* nil)))))))

        ;; Fix it now: compose the visible message from the clicked subset and
        ;; resolve which model runs it (the skill's declared cheap model when
        ;; the pool has it); if a turn is running, park both in the pending slot
        on-fix    (mf/use-fn
                   (mf/deps settings busy? pool page selected objects)
                   (fn [violations]
                     (when (and settings (seq violations))
                       (let [text   (dwaip/compose-fix-message violations)
                             fix-st (dwaip/fix-settings violations pool settings)]
                         (if busy?
                           (st/emit! (dwaip/set-pending-fix text fix-st))
                           (st/emit! (dwaip/send-message fix-st text (chat-context page selected objects))))))))

        on-cancel-pending (mf/use-fn #(st/emit! (dwaip/clear-pending-fix)))

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

    ;; drain the pending Fix-it-now once the running turn ends: the queued
    ;; message becomes a normal visible send, with fresh page/selection context
    ;; but the settings resolved when it was queued (the skill's model)
    (mf/with-effect [busy? pending-fix settings page selected objects]
      (when (and (not busy?) (seq (:text pending-fix)) settings)
        (st/emit! (dwaip/clear-pending-fix)
                  (dwaip/send-message (or (:settings pending-fix) settings)
                                      (:text pending-fix)
                                      (chat-context page selected objects)))))

    [:div {:class (stl/css :chat-tab)}
     ;; Current-file context surfaced to the agent: page + selection.
     [:div {:class (stl/css :context-chip)}
      [:span {:class (stl/css :context-page)} (:name page)]
      [:span {:class (stl/css :context-sep)} "·"]
      [:span {:class (stl/css :context-selection)} (selection-label selected objects)]]

     [:> affected-strip* {:on-fix on-fix}]

     (if (seq messages)
       [:> transcript* {:messages messages :busy? busy?}]
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

     ;; A Fix-it-now queued behind the running turn: visible, cancellable,
     ;; sends itself when the turn ends (drain effect above).
     (when (seq (:text pending-fix))
       [:div {:class (stl/css :pending-fix)}
        [:div {:class (stl/css :pending-fix-body)}
         [:span {:class (stl/css :pending-fix-label)}
          (dm/str "Queued — sends when the current turn ends"
                  ;; name the model only when it is not the one the user is
                  ;; chatting with — that divergence is worth a heads-up
                  (when-let [m (get-in pending-fix [:settings :model])]
                    (when (not= m (:model settings))
                      (dm/str " · via " m))))]
         [:span {:class (stl/css :pending-fix-text)} (:text pending-fix)]]
        [:button {:type "button"
                  :class (stl/css :pending-fix-cancel)
                  :aria-label "Cancel the queued fix"
                  :on-click on-cancel-pending}
         [:span {:aria-hidden true} "✕"]]])

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
  [{:keys [selected on-select on-create]}]
  (let [catalog     (mf/deref refs/skills-catalog)
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

;; --- Panel resize
;;
;; The workspace grid gives the panel an `auto` column, so the element's own
;; width is authoritative and an inline style is all resizing needs. Width is
;; a global preference (one width everywhere, localStorage-persisted) —
;; deliberately not `use-resize-hook`, whose persistence is keyed per file.

(def ^:private panel-min-width 360)

(defn- clamp-panel-width
  "Between the designed minimum (the composer's anchored controls need it) and
  half the window, so the viewport always stays usable."
  [width]
  (let [max-width (max panel-min-width (* 0.5 (:width (dom/get-window-size))))]
    (mth/clamp width panel-min-width max-width)))

(defn- use-panel-resize
  "Drag mechanics for the left-edge handle, modeled on `use-resize-hook`'s
  pointer-capture handlers. The panel is right-docked, so dragging left grows
  it. Returns the current width and the three handlers for the handle node."
  []
  (let [width*    (hooks/use-persisted-state ::panel-width panel-min-width)
        width     (deref width*)
        ;; nil when idle; {:x pointer-x :width panel-width} at drag start
        start-ref (mf/use-ref nil)

        on-pointer-down
        (mf/use-fn
         (mf/deps width)
         (fn [event]
           (dom/capture-pointer event)
           (mf/set-ref-val! start-ref {:x (:x (dom/get-client-position event))
                                       :width width})))

        on-pointer-move
        (mf/use-fn
         (fn [event]
           (when-let [start (mf/ref-val start-ref)]
             (let [pos (dom/get-client-position event)]
               (reset! width* (clamp-panel-width (+ (:width start)
                                                    (- (:x start) (:x pos)))))))))

        on-lost-pointer-capture
        (mf/use-fn
         (fn [event]
           (dom/release-pointer event)
           (mf/set-ref-val! start-ref nil)))]

    ;; a window shrink can strand the saved width past the max — re-clamp
    (mf/with-effect []
      (let [on-resize #(swap! width* clamp-panel-width)]
        (.addEventListener js/window "resize" on-resize)
        (fn [] (.removeEventListener js/window "resize" on-resize))))

    {:width width
     :on-pointer-down on-pointer-down
     :on-pointer-move on-pointer-move
     :on-lost-pointer-capture on-lost-pointer-capture}))

;; --- Text scale
;;
;; Discrete steps around 1 (today's sizes). The chosen step is a global
;; reading preference, persisted like the panel width; the scss consumes it
;; as the `--ai-font-scale` custom property set inline on the panel root.

(def ^:private font-scale-steps [0.85 1 1.15 1.3 1.45])
(def ^:private font-scale-default-step 1)

(defn- valid-font-step
  "The stored step, or the default when the stored value is out of range or
  not an int (an old/garbage localStorage value must never break rendering)."
  [step]
  (if (and (int? step) (< -1 step (count font-scale-steps)))
    step
    font-scale-default-step))

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
        ;; description carried over when creation is started from Chat (US #9).
        seed*       (mf/use-state nil)

        open-skills (mf/use-fn (fn [] (reset! creating* false) (reset! skill* nil) (reset! view* :skills)))
        on-select   (mf/use-fn #(reset! skill* %))
        open-create (mf/use-fn (fn [] (reset! seed* nil) (reset! creating* true)))
        on-created  (mf/use-fn #(reset! creating* false))
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
                     (mf/deps skill creating?)
                     (fn []
                       (cond
                         creating?     (reset! creating* false) ;; create → list
                         (some? skill) (reset! skill* nil)      ;; detail → list
                         :else         (reset! view* :chat))))  ;; list → chat

        providers   (mf/deref refs/ai-providers)
        pool        (mf/with-memo [providers] (provider-pool providers))
        selected-model* (hooks/use-persisted-state ::selected-model nil)
        settings    (selected-settings pool (deref selected-model*))

        {:keys [width]
         :as   resize} (use-panel-resize)

        font-step*  (hooks/use-persisted-state ::font-step font-scale-default-step)
        font-step   (valid-font-step (deref font-step*))
        font-scale  (nth font-scale-steps font-step)
        on-font-dec (mf/use-fn
                     (mf/deps font-step)
                     #(reset! font-step* (max 0 (dec font-step))))
        on-font-inc (mf/use-fn
                     (mf/deps font-step)
                     #(reset! font-step* (min (dec (count font-scale-steps))
                                              (inc font-step))))]

    ;; Providers are configured on the settings page; load them so we know
    ;; whether to show the chat or the connect-a-provider prompt. Skill state
    ;; (per-account + this file's overrides) and the user's created skills drive
    ;; which skills the agent routes to, so load them up front too.
    (mf/with-effect []
      (st/emit! (dai/fetch-ai-providers)
                (skst/fetch-skill-states)
                (dusk/fetch-user-skills)))

    [:aside {:class (stl/css :ai-panel)
             :style #js {:width (dm/str width "px")
                         "--ai-font-scale" (dm/str font-scale)}}
     ;; Left-edge drag handle — the right-docked panel's resizable edge.
     [:div {:class (stl/css :resize-area)
            :on-pointer-down (:on-pointer-down resize)
            :on-pointer-move (:on-pointer-move resize)
            :on-lost-pointer-capture (:on-lost-pointer-capture resize)}]

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
          (cond creating? "New skill" skill "Skill info" :else "Skills")]]
        [:span {:class (stl/css :title)} "Agent"])
      (when-not skills?
        [:div {:class (stl/css :header-actions)}
         ;; A−/A+ text-size stepper. The header itself deliberately doesn't
         ;; scale, so these stay put while the body text steps.
         [:button {:type "button"
                   :class (stl/css :font-step-btn)
                   :aria-label "Decrease text size"
                   :title "Decrease text size"
                   :disabled (zero? font-step)
                   :on-click on-font-dec}
          "A−"]
         [:button {:type "button"
                   :class (stl/css :font-step-btn)
                   :aria-label "Increase text size"
                   :title "Increase text size"
                   :disabled (= font-step (dec (count font-scale-steps)))
                   :on-click on-font-inc}
          "A+"]
         [:> icon-button* {:variant "ghost"
                           :aria-label "Open Skills"
                           :on-click open-skills
                           :icon i/list-checks}]])]

     [:div {:class (stl/css :body)}
      (cond
        ;; Skills is a static catalog — reachable even before a provider is set up.
        skills?       (if creating?
                        [:> skill-create* {:settings settings :seed (deref seed*) :on-created on-created}]
                        [:> skills-tab* {:selected skill :on-select on-select :on-create open-create}])
        (empty? pool) [:> connect-empty*]
        :else         [:> chat-tab* {:on-create-skill on-create-skill}])]]))
