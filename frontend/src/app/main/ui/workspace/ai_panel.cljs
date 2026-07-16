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
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main.data.ai-providers :as dai]
   [app.main.data.workspace.agent :as agent]
   [app.main.data.workspace.agent-chats :as dwach]
   [app.main.data.workspace.agent-skills :as ask]
   [app.main.data.workspace.ai-panel :as dwaip]
   [app.main.data.workspace.design-doc :as dd]
   [app.main.data.workspace.design-md :as dmd]
   [app.main.data.workspace.elicitation :as el]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.skill-state :as skst]
   [app.main.data.workspace.slash-commands :as slc]
   [app.main.data.workspace.team-skills :as dwts]
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

(defn- propose-reactive
  "A default reactive behavior guessed from the description (US #14): watch-ish
  phrasing (audit, watch, monitor, keep an eye, flag, remind…) suggests an
  Observer; everything else is On-demand. The user can override."
  [what]
  (let [w (str/lower (or what ""))]
    (if (re-find #"audit|watch|monitor|keep an eye|flag|remind|notice|track|observe" w)
      "observer"
      "on-demand")))

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
;; app.main.data.workspace.agent-skills (`ask/catalog`, `ask/reactive-label`).

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
  [{:keys [question qstate room on-toggle on-other-text on-text
           on-add-images on-remove-image]}]
  (let [{:keys [id type options hint optional allow-decide allow-other
                allow-images]} question
        selected   (or (:selected qstate) #{})
        other-on?  (contains? selected :other)
        multi?     (= "multi" type)
        text-type? (= "text" type)
        images     (or (:images qstate) [])
        file-ref   (mf/use-ref nil)
        on-pick-images
        (mf/use-fn
         (mf/deps id on-add-images)
         (fn [event]
           (let [target (dom/get-target event)]
             (on-add-images id (array-seq (.-files ^js target)))
             ;; re-picking the same file must re-fire `change`
             (dom/set-value! target ""))))
        on-attach-click
        (mf/use-fn #(some-> (mf/ref-val file-ref) (.click)))]
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
                :on-change #(on-other-text id (dom/get-value (dom/get-target %)))}])

     ;; reference images on a text question (allow_images): thumbnails + an
     ;; attach control, reusing the composer's recompression pipeline
     (when (and text-type? allow-images)
       [:div {:class (stl/css :eform-attach-row)}
        [:input {:type "file"
                 :ref file-ref
                 :class (stl/css :composer-file-input)
                 :accept dwm/accept-image-types
                 :multiple true
                 :on-change on-pick-images}]
        (for [[i image] (map-indexed vector images)]
          [:div {:key i :class (stl/css :eform-thumb)}
           [:img {:class (stl/css :eform-thumb-img)
                  :src (image-src image)
                  :alt (dm/str "Reference image " (inc i))}]
           [:button {:type "button"
                     :class (stl/css :eform-thumb-remove)
                     :aria-label (dm/str "Remove reference image " (inc i))
                     :on-click #(on-remove-image id i)}
            [:span {:aria-hidden true} "✕"]]])
        [:button {:type "button"
                  :class (stl/css :eform-attach-btn)
                  :disabled (zero? room)
                  :title (if (pos? room)
                           "Attach reference images"
                           "Image limit reached")
                  :on-click on-attach-click}
         [:> i/icon* {:icon-id i/img}]
         "Add reference"]])]))

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
                                   :allow-decide (true? (:allow_decide q))
                                   :allow-images (and (= "text" (:type q))
                                                      (true? (:allow_images q)))))
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
        on-add-images (mf/use-fn
                       (fn [id files]
                         (let [pictures (filterv image-file? (vec files))]
                           (when (seq pictures)
                             (->> (read-images pictures)
                                  (rx/subs!
                                   (fn [read]
                                     ;; room is re-measured inside the swap —
                                     ;; two async reads racing must not
                                     ;; overshoot the caps
                                     (swap! state*
                                            (fn [s]
                                              (let [room (el/image-room s id)]
                                                (if (pos? room)
                                                  (update-in s [id :images]
                                                             (fnil into [])
                                                             (take room read))
                                                  s)))))))))))
        on-remove-image
        (mf/use-fn
         (fn [id i]
           (swap! state* update-in [id :images]
                  (fn [v]
                    (vec (concat (subvec v 0 i) (subvec v (inc i))))))))

        on-submit (mf/use-fn
                   (mf/deps questions state ready?)
                   (fn []
                     (when ready?
                       (let [{:keys [images counts]} (el/form-images questions state)
                             payload (cond-> {:answers (el/answers questions state)}
                                       (seq images)
                                       (assoc :attachments counts
                                              :images images
                                              :note (str "the user attached reference images; they follow "
                                                         "this result in question order — `attachments` "
                                                         "holds the per-question counts")))]
                         (st/emit! (dwaip/submit-form
                                    payload
                                    (el/summary questions state)))))))]

    [:div {:class (stl/css :eform)}
     (when (seq title)
       [:div {:class (stl/css :eform-title)} title])
     (for [q questions]
       [:> elicitation-question* {:key (:id q)
                                  :question q
                                  :qstate (get state (:id q))
                                  :room (el/image-room state (:id q))
                                  :on-toggle on-toggle
                                  :on-other-text on-other-text
                                  :on-text on-text
                                  :on-add-images on-add-images
                                  :on-remove-image on-remove-image}])
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
  [{:keys [messages busy? form checkpoint]}]
  (let [;; consecutive tool calls collapse into one row; `partition-by` on the
        ;; role predicate yields alternating runs of tools / everything else
        runs          (mf/with-memo [messages]
                        (->> (map-indexed vector messages)
                             (partition-by (fn [[_ m]] (= "tool" (:role m))))))

        on-continue   (mf/use-fn #(st/emit! (dwaip/continue-turn)))
        on-stop-here  (mf/use-fn #(st/emit! (dwaip/dismiss-checkpoint)))

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

    ;; `form`/`checkpoint` are in the deps: an interview or a pause appearing
    ;; is new content to follow
    (mf/with-layout-effect [messages busy? form checkpoint]
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
                  ;; harness notes (e.g. "conversation compacted") — not a
                  ;; bubble from either party, rendered as a quiet seam
                  note?  (= "note" (:role message))
                  images (:images message)]
              [:div {:key idx
                     :class (stl/css-case :message true
                                          :message-user user?
                                          :message-note note?
                                          :message-md (not (or user? note?)))}
               (when (seq images)
                 [:div {:class (stl/css :message-images)}
                  (for [[i image] (map-indexed vector images)]
                    [:img {:key i
                           :class (stl/css :message-image)
                           :src (image-src image)
                           :alt (dm/str "Attached image " (inc i))}])])
               ;; the user didn't write markdown — don't eat their asterisks;
               ;; a note is a plain string by construction
               (if (or user? note?)
                 (:content message)
                 [:> md/markdown* {:text (:content message)}])]))))
      ;; while an interview is open the turn is busy *waiting on the user* —
      ;; a "Thinking…" bubble under the form would be a lie
      (when (some? form)
        [:> elicitation-form* {:key (hash form) :form form}])
      ;; the runaway brake: the turn crossed its round/spend allowance and is
      ;; paused, resumable as-is — the user decides whether it keeps going
      (when (some? checkpoint)
        (let [{:keys [rounds usage settings]} checkpoint
              cost (agent/estimate-cost-usd (:model settings) usage)]
          [:div {:class (stl/css :checkpoint)}
           [:div {:class (stl/css :checkpoint-label)}
            (dm/str "Still working — " rounds " tool rounds"
                    (when cost (dm/str " and ~$" (.toFixed cost 2)))
                    " this turn. Keep going?")]
           [:div {:class (stl/css :checkpoint-actions)}
            [:button {:type "button"
                      :class (stl/css :checkpoint-continue)
                      :on-click on-continue}
             "Continue"]
            [:button {:type "button"
                      :class (stl/css :checkpoint-stop)
                      :on-click on-stop-here}
             "Stop here"]]]))
      (when (and busy? (nil? form))
        [:div {:class (stl/css :message :message-thinking)} "Thinking…"])]

     (when-not at-bottom?
       [:> icon-button* {:class (stl/css :jump-to-latest)
                         :variant "primary"
                         :aria-label "Jump to latest message"
                         :on-click scroll-to-end
                         :icon i/arrow-down}])]))

;; --- Observer notifications (issue #37)
;;
;; The concrete surface of the "Observer" reactive behavior (US #14): while the
;; panel is open, the data-layer watcher (`dwaip/start-watcher`) keeps a live
;; violation set, and each affected Observer skill surfaces one calm card here,
;; between the context chip and the transcript. A card names its skill, folds a
;; per-rule breakdown open, and lists the flagged layers as rows you can jump to.
;; Zero violations — or a dismissed set that hasn't changed — renders nothing.

(def ^:private rule-labels
  {"layer-naming"      "Layer naming"
   "token-only-colors" "Token-only colors"})

(def ^:private max-row-shapes 3)

(defn- parse-swatch
  "The first hex colour named in a violation `:reason` — token-only-colors spells
  out the offending raw colours, so a row can show the actual swatch. nil for
  rules with no colour (layer naming), where the row shows a neutral marker."
  [reason]
  (when (string? reason)
    (re-find #"#[0-9a-fA-F]{3,8}" reason)))

(defn- rule-groups
  "One skill's violations → [{:rule :label :n :shapes}], biggest group first,
  then by rule name so equal counts render stably."
  [violations]
  (->> violations
       (group-by :rule)
       (map (fn [[rule vs]]
              {:rule rule :label (get rule-labels rule rule) :n (count vs) :shapes vs}))
       (sort-by (juxt (comp - :n) :rule))
       (vec)))

(defn- summary-line
  "\"12 layers need attention · 2 rules\" for one skill's violations — layers
  counted distinct (a shape can trip several rules)."
  [violations]
  (let [shapes (count (into #{} (map :shapeId) violations))
        rules  (count (into #{} (map :rule) violations))]
    (dm/str shapes (if (= 1 shapes) " layer needs attention · " " layers need attention · ")
            rules (if (= 1 rules) " rule" " rules"))))

(defn- observer-cards
  "Group the live violation set by owning Observer skill → one card model each,
  biggest first. `:signature` is a stable hash of the skill's exact violation set
  (rule+shape), so a dismissal can tell 'same findings' from 'new findings'."
  [violations]
  (->> violations
       (group-by (fn [v] (:name (ask/rule->skill (:rule v)))))
       (map (fn [[skill-name vs]]
              (let [sk (ask/rule->skill (:rule (first vs)))]
                {:name      skill-name
                 :label     (or (:label sk) (get rule-labels (:rule (first vs)) skill-name))
                 :shapes    vs
                 :groups    (rule-groups vs)
                 :summary   (summary-line vs)
                 :signature (hash (vec (sort (map (juxt :rule :shapeId) vs))))})))
       (sort-by (comp - count :shapes))
       (vec)))

(mf/defc observer-card*
  "One Observer skill's notification (issue #37): a calm neutral card — eye +
  skill headline + summary + expand chevron, an always-visible Dismiss / Fix all
  footer, and, when expanded, per-rule groups whose rows jump to the layer on the
  canvas. `on-fix` takes the violations to fix; `on-dismiss` waves this card off."
  {::mf/private true}
  [{:keys [card on-fix on-dismiss on-shape-click]}]
  (let [{:keys [label shapes groups summary]} card
        expanded*   (mf/use-state false)
        expanded?   (deref expanded*)
        on-toggle   (mf/use-fn #(swap! expanded* not))
        ;; which rule groups have their full list shown (vs the first few)
        open-rules* (mf/use-state #{})
        open-rules  (deref open-rules*)]
    [:div {:class (stl/css :observer-card)}
     [:button {:type "button"
               :class (stl/css :observer-header)
               :aria-expanded expanded?
               :on-click on-toggle}
      [:span {:class (stl/css :observer-eye) :aria-hidden true}
       [:> i/icon* {:icon-id i/shown}]]
      [:span {:class (stl/css :observer-headline-wrap)}
       [:span {:class (stl/css :observer-headline)} label]
       [:span {:class (stl/css :observer-summary)} summary]]
      [:span {:class (stl/css-case :observer-chevron true :observer-chevron-open expanded?)
              :aria-hidden true}
       [:> i/icon* {:icon-id i/arrow-down}]]]

     (when expanded?
       [:div {:class (stl/css :observer-groups)}
        (for [{:keys [rule label n] g-shapes :shapes} groups]
          (let [open? (contains? open-rules rule)
                shown (if open? g-shapes (take max-row-shapes g-shapes))]
            [:div {:key rule :class (stl/css :observer-group)}
             [:div {:class (stl/css :observer-group-head)}
              [:span {:class (stl/css :observer-group-label)} label]
              [:span {:class (stl/css :observer-group-count)} (dm/str "· " n)]
              [:button {:type "button"
                        :class (stl/css :observer-group-fix)
                        :title (dm/str "Fix only the " label " findings")
                        ;; render-time closure, deliberately: hooks cannot live
                        ;; inside `for`, and the group list is tiny
                        :on-click (fn [_] (on-fix g-shapes))}
               "Fix"]]
             [:ul {:class (stl/css :observer-rows)}
              (for [v shown]
                (let [hex (parse-swatch (:reason v))]
                  [:li {:key (:shapeId v)}
                   [:button {:type "button"
                             :class (stl/css :observer-row)
                             :title (:reason v)
                             :data-id (:shapeId v)
                             :on-click on-shape-click}
                    [:span {:class (stl/css-case :observer-swatch true
                                                 :observer-swatch-neutral (nil? hex))
                            :style (when hex #js {:backgroundColor hex})}]
                    [:span {:class (stl/css :observer-row-name)}
                     ;; ✦ = flagged by the semantic tick, not the native scan
                     (when (:semantic v)
                       [:span {:aria-hidden true :class (stl/css :observer-semantic)} "✦ "])
                     (:shapeName v)]]]))
              (when (and (not open?) (> n max-row-shapes))
                [:li
                 [:button {:type "button"
                           :class (stl/css :observer-showmore)
                           :on-click (fn [_] (swap! open-rules* conj rule))}
                  (dm/str "Show " (- n max-row-shapes) " more")]])]]))])

     [:div {:class (stl/css :observer-footer)}
      [:button {:type "button"
                :class (stl/css :observer-dismiss)
                :on-click on-dismiss}
       "Dismiss"]
      [:button {:type "button"
                :class (stl/css :observer-fixall)
                :title "Ask the agent to fix everything listed, in this conversation"
                :on-click (fn [_] (on-fix shapes))}
       "Fix all"]]]))

(mf/defc observer-notifications*
  "The Observer notification stack (issue #37): one calm card per affected
  Observer skill, hidden entirely at zero violations or when the user has
  dismissed the current set."
  {::mf/private true}
  [{:keys [on-fix]}]
  (let [violations (mf/deref refs/ai-panel-violations)
        dismissed  (mf/deref refs/ai-panel-observer-dismissed)
        ;; click a layer row → select + zoom on canvas; the card stays open so
        ;; the user can walk through the flagged layers one after another
        on-shape-click
        (mf/use-fn
         (fn [event]
           (when-let [id (-> (dom/get-current-target event)
                             (dom/get-data "id")
                             (uuid/parse*))]
             (st/emit! (dws/select-shape id)
                       dwz/zoom-to-selected-shape))))
        cards (->> (observer-cards violations)
                   (remove (fn [c] (= (get dismissed (:name c)) (:signature c)))))]
    (when (seq cards)
      [:div {:class (stl/css :observer-list)}
       (for [c cards]
         [:> observer-card* {:key (:name c)
                             :card c
                             :on-fix on-fix
                             :on-shape-click on-shape-click
                             :on-dismiss (fn [_] (st/emit! (dwaip/dismiss-observer (:name c) (:signature c))))}])])))

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
        checkpoint (mf/deref refs/ai-panel-checkpoint)
        history   (mf/deref refs/ai-panel-history)
        handoff-dismissed? (mf/deref refs/ai-panel-handoff-dismissed)
        ;; history identity only changes at turn boundaries — the size memo
        ;; recomputes then, not per render
        history-chars (mf/with-memo [history] (agent/history-chars history))
        ;; suggest a fresh chat once the conversation is big enough that every
        ;; reply re-reads a lot of it; hidden mid-turn, at a checkpoint, or
        ;; once waved off (conversation-scoped — see dismiss-handoff-notice)
        handoff? (and (> history-chars agent/handoff-notice-chars)
                      (not busy?)
                      (nil? checkpoint)
                      (not handoff-dismissed?))
        on-handoff (mf/use-fn #(st/emit! (dwaip/summarize-into-new-chat)))
        on-handoff-dismiss (mf/use-fn #(st/emit! (dwaip/dismiss-handoff-notice)))
        pending-fix (mf/deref refs/ai-panel-pending-fix)
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
                   (mf/deps input images settings busy? checkpoint page selected objects on-create-skill)
                   (fn []
                     (let [text (str/trim input)]
                       ;; an image on its own is a legitimate message — "what is
                       ;; this?" is often carried entirely by the picture.
                       ;; A pending checkpoint blocks sending like a running
                       ;; turn does: the user answers the pause first.
                       (when (and (or (seq text) (seq images)) (not busy?) (nil? checkpoint))
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
                   (mf/deps settings busy? checkpoint pool page selected objects)
                   (fn [violations]
                     (when (and settings (seq violations))
                       (let [text   (dwaip/compose-fix-message violations)
                             fix-st (dwaip/fix-settings violations pool settings)]
                         ;; a pending checkpoint parks the fix like a running
                         ;; turn does — it drains once the pause is answered
                         (if (or busy? (some? checkpoint))
                           (st/emit! (dwaip/set-pending-fix text fix-st))
                           (st/emit! (dwaip/send-message fix-st text (chat-context page selected objects))))))))

        on-cancel-pending (mf/use-fn #(st/emit! (dwaip/clear-pending-fix)))

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
                         (st/emit! (dwach/new-chat)))))

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

    ;; drain the pending Fix-it-now once the running turn ends: the queued
    ;; message becomes a normal visible send, with fresh page/selection context
    ;; but the settings resolved when it was queued (the skill's model)
    (mf/with-effect [busy? checkpoint pending-fix settings page selected objects]
      (when (and (not busy?) (nil? checkpoint) (seq (:text pending-fix)) settings)
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

     [:> observer-notifications* {:on-fix on-fix}]

     (if (or (seq messages) (some? pending-form))
       [:> transcript* {:messages messages :busy? busy? :form pending-form
                        :checkpoint checkpoint}]
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
        ;; would otherwise read "plus sign new chat"
        [:button {:class (stl/css :chat-clear)
                  :type "button"
                  :disabled busy?
                  :title "Start a new chat — this conversation is kept in History"
                  :on-click on-clear}
         [:span {:aria-hidden true} "+"]
         "New chat"]])

     ;; Fresh-chat suggestion: the conversation is big enough that every reply
     ;; re-reads a lot of it. The action summarizes ALL of it (one cheap round)
     ;; and starts a new chat seeded with the summary; the old conversation
     ;; stays whole in History.
     (when handoff?
       [:div {:class (stl/css :handoff-notice)}
        [:span {:class (stl/css :handoff-text)}
         "This conversation is getting long — replies re-read all of it."]
        [:button {:type "button"
                  :class (stl/css :handoff-action)
                  :title "Summarize this conversation and continue in a fresh chat — this one is kept in History"
                  :on-click on-handoff}
         [:span {:aria-hidden true} "✦ "]
         "Summarize into a new chat"]
        [:button {:type "button"
                  :class (stl/css :handoff-dismiss)
                  :aria-label "Dismiss this suggestion"
                  :on-click on-handoff-dismiss}
         [:span {:aria-hidden true} "✕"]]])

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

      ;; Below the input: the attach button on the left, the model picker (half
      ;; width, borderless) on the right.
      [:div {:class (stl/css :composer-footer)}
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
            "Manage your models"]])]]]]))

(mf/defc reactive-badge*
  "The reactive-behavior pill (US #14) shared by the catalog cards and the detail
  view: On-demand (acts only when invoked) vs Observer (keeps ambient awareness)."
  {::mf/private true}
  [{:keys [reactive]}]
  (when (seq reactive)
    [:span {:class (stl/css-case :reactive-badge true
                                 :reactive-ondemand (= reactive "on-demand")
                                 :reactive-observer (= reactive "observer"))}
     (get ask/reactive-label reactive reactive)]))

(mf/defc skill-edit*
  "Inline editor for a USER-CREATED skill: label, trigger phrase, reactive
  behavior and the generated playbook body. The name slug is deliberately
  absent — it keys the enable state and the router, so it never changes after
  creation."
  {::mf/private true}
  [{:keys [skill on-saved on-cancel]}]
  (let [label*    (mf/use-state (or (:label skill) ""))
        trigger*  (mf/use-state (or (:example skill) ""))
        reactive* (mf/use-state (or (:reactive skill) "on-demand"))
        body*     (mf/use-state (or (:body skill) ""))
        label     (deref label*)
        trigger   (deref trigger*)
        reactive  (deref reactive*)
        body      (deref body*)
        ready?    (and (seq (str/trim label)) (seq (str/trim body)))
        on-save   (mf/use-fn
                   (mf/deps skill label trigger reactive body ready?)
                   (fn []
                     (when ready?
                       (st/emit! (dusk/update-skill
                                  {:id (:id skill)
                                   :label (str/trim label)
                                   :reactive reactive
                                   :trigger (str/trim trigger)
                                   :description (:what skill)
                                   :body body}))
                       (on-saved))))]
    [:div {:class (stl/css :skill-edit)}
     [:label {:class (stl/css :create-label)} "Name"]
     [:input {:class (stl/css :create-input)
              :value label
              :on-change #(reset! label* (dom/get-value (dom/get-target %)))}]

     [:label {:class (stl/css :create-label)} "Trigger phrase"]
     [:input {:class (stl/css :create-input)
              :value trigger
              :on-change #(reset! trigger* (dom/get-value (dom/get-target %)))}]

     [:label {:class (stl/css :create-label)} "Behavior"]
     [:div {:class (stl/css :create-modes)}
      (for [[m lbl] [["on-demand" "💬 On-demand"] ["observer" "👁 Observer"]]]
        [:button {:key m
                  :type "button"
                  :class (stl/css-case :create-mode true :selected (= m reactive))
                  :on-click #(reset! reactive* m)}
         lbl])]

     [:label {:class (stl/css :create-label)} "Playbook (what the agent follows)"]
     [:textarea {:class (stl/css :skill-edit-body)
                 :value body
                 :rows 14
                 :on-change #(reset! body* (dom/get-value (dom/get-target %)))}]

     [:div {:class (stl/css :vibes-actions)}
      [:button {:type "button"
                :class (stl/css :vibes-button-primary)
                :disabled (not ready?)
                :on-click on-save}
       "Save"]
      [:button {:type "button"
                :class (stl/css :vibes-button)
                :on-click on-cancel}
       "Cancel"]]]))

(mf/defc skill-detail*
  "Detail for one catalog skill, shown in place of the list within the Skills
  view. Back navigation lives in the panel header (US #35), so there is no in-body
  back button here. Carries the same on/off toggle as the list card; `enabled` is
  the resolved state and `on-toggle` receives the new boolean.

  A user-created skill (`:user?`) additionally shows its generated playbook and
  can be edited (label/trigger/mode/body) or deleted — built-ins are shared and
  regenerated from the aikit, so they stay read-only. `on-close` returns to the
  list after a delete."
  {::mf/private true}
  [{:keys [skill enabled on-toggle on-close on-promote]}]
  (let [{:keys [label category reactive example what user? team? body]} skill
        editing?* (mf/use-state false)
        editing?  (deref editing?*)
        confirm?* (mf/use-state false)
        confirm?  (deref confirm?*)
        on-edit   (mf/use-fn (fn [] (reset! confirm?* false) (reset! editing?* true)))
        on-saved  (mf/use-fn #(reset! editing?* false))
        on-cancel (mf/use-fn #(reset! editing?* false))
        on-delete (mf/use-fn
                   (mf/deps confirm? skill on-close)
                   (fn []
                     (if confirm?
                       (do (st/emit! (dusk/delete-skill (:id skill)))
                           (on-close))
                       (reset! confirm?* true))))]
    (if editing?
      [:> skill-edit* {:skill skill :on-saved on-saved :on-cancel on-cancel}]
      [:div {:class (stl/css :skill-detail)}
       [:div {:class (stl/css :detail-category)} category]
       [:div {:class (stl/css :detail-head)}
        [:div {:class (stl/css :detail-name)} label]
        [:> switch* {:default-checked enabled
                     :aria-label (dm/str (if enabled "Disable " "Enable ") label)
                     :on-change on-toggle}]]
       [:div {:class (stl/css :detail-tags)}
        [:> reactive-badge* {:reactive reactive}]
        (when team?
          [:span {:class (stl/css :catalog-team)} "Team"])]
       [:div {:class (stl/css :detail-section-label)} "Example trigger phrase"]
       [:div {:class (stl/css :detail-example)} (dm/str "“" example "”")]
       [:div {:class (stl/css :detail-section-label)} "What it does"]
       [:div {:class (stl/css :detail-what)} what]
       (when user?
         [:*
          [:div {:class (stl/css :detail-section-label)} "Playbook"]
          [:pre {:class (stl/css :detail-body)} body]
          [:div {:class (stl/css :vibes-actions)}
           [:button {:type "button" :class (stl/css :vibes-button) :on-click on-edit}
            "Edit"]
           (when on-promote
             [:button {:type "button" :class (stl/css :vibes-button) :on-click on-promote}
              "Promote to team"])
           [:button {:type "button"
                     :class (stl/css-case :vibes-button true
                                          :vibes-button-danger true
                                          :vibes-button-confirm confirm?)
                     :on-click on-delete}
            (if confirm? "Really delete?" "Delete")]]])])))

(mf/defc skill-row*
  "One catalog row: name + description, a muted \"Off\" pill when disabled, and a
  discreet ⋯ overflow menu (always visible, not hover-gated) holding the per-skill
  actions. Clicking the row body opens the detail view; the menu swallows its own
  clicks so it doesn't. Enable/Disable is instant; Fork / Promote to team are
  entry points only (disabled — wired by US #10 / US #12)."
  {::mf/private true}
  [{:keys [label blurb reactive enabled user? on-open on-set-enabled on-promote]}]
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
      [:> reactive-badge* {:reactive reactive}]
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
         ;; Promote to team (US #12): live only for a personal skill; built-ins
         ;; and team skills keep it disabled.
         (if (and user? on-promote)
           [:li {:class (stl/css :menu-option)
                 :role "button"
                 :on-click #(do (on-promote) (close-menu))}
            "Promote to team"]
           [:li {:class (stl/css-case :menu-option true :menu-option-disabled true)
                 :aria-disabled true}
            "Promote to team"])]]]]
     [:div {:class (stl/css :catalog-desc)}
      [:span {:class (stl/css :catalog-blurb)} blurb]]]))

(mf/defc design-doc-view*
  "A DESIGN.md doc rendered read-only (US #38): the YAML frontmatter as a
  token summary — swatches, type lines, scale chips — and only the markdown
  BODY through marked (it would lex the `---` fence as an hr + prose). A
  legacy doc has no frontmatter and renders as plain markdown."
  {::mf/private true}
  [{:keys [doc]}]
  (let [{:keys [frontmatter body]} (dmd/parse doc)
        model (dmd/display-model frontmatter)]
    [:div {:class (stl/css :vibes-doc :message-md)}
     (when model
       [:div {:class (stl/css :vibes-tokens)}
        (when-let [name (:name model)]
          [:div {:class (stl/css :vibes-tokens-name)} name])
        (when-let [description (:description model)]
          [:p {:class (stl/css :vibes-tokens-desc)} description])
        (when-let [colors (seq (:colors model))]
          [:div {:class (stl/css :vibes-swatch-grid)}
           (for [{:keys [name value swatch]} colors]
             [:div {:key name :class (stl/css :vibes-swatch)}
              [:span {:class (stl/css :vibes-swatch-chip)
                      :style (when (string? swatch)
                               #js {:backgroundColor swatch})}]
              [:span {:class (stl/css :vibes-swatch-name)} name]
              [:span {:class (stl/css :vibes-swatch-value)} value]])])
        (when-let [typography (seq (:typography model))]
          [:div {:class (stl/css :vibes-type-rows)}
           (for [{:keys [name summary]} typography]
             [:div {:key name :class (stl/css :vibes-type-row)}
              [:span {:class (stl/css :vibes-token-label)} name]
              [:span {:class (stl/css :vibes-type-summary)} summary]])])
        (for [[section rows] [["rounded" (:rounded model)]
                              ["spacing" (:spacing model)]]
              :when (seq rows)]
          [:div {:key section :class (stl/css :vibes-scale-row)}
           [:span {:class (stl/css :vibes-token-label)} section]
           [:div {:class (stl/css :vibes-scale-chips)}
            (for [{:keys [name value]} rows]
              [:span {:key name :class (stl/css :vibes-scale-chip)}
               (dm/str name " " value)])]])])
     [:> md/markdown* {:text body}]]))

;; Card glyphs for the known foundations; anything user-invented gets the
;; document glyph. A frontmatter `icon` field could replace this once the
;; set grows past a handful.
(def ^:private foundation-icons
  {"vibes" i/swatches
   "tone-of-voice" i/comments})

(defn- foundation-summary
  "The card's one-liner: the frontmatter description, else the first body
  line that says something (skipping headings)."
  [doc]
  (let [{:keys [frontmatter body]} (dmd/parse doc)]
    (or (get frontmatter "description")
        (->> (str/split (or body "") #"\n")
             (map str/trim)
             (remove #(or (str/blank? %) (str/starts-with? % "#")))
             (first)))))

(mf/defc foundations-list*
  "The file's foundations (US #38): an 'Applies to this file' marker, one
  card per foundation (glyph, name, one-line summary), and the add
  affordance. Creation is agent-guided and lands with the detail work — the
  button says so rather than pretending."
  {::mf/private true}
  [{:keys [on-select on-interview]}]
  (let [foundations (mf/deref dd/foundations-ref)]
    [:div {:class (stl/css :foundations-view)}
     [:div {:class (stl/css :foundations-scope)}
      [:> i/icon* {:icon-id i/document}]
      [:span "Applies to this file"]]
     (if (empty? foundations)
       [:div {:class (stl/css :vibes-empty)}
        [:div {:class (stl/css :vibes-empty-title)} "No foundations yet"]
        [:p {:class (stl/css :vibes-empty-text)}
         "Foundations are standing design context for this file — vibes, tone of voice, naming rules. Skills and the agent read them on every task. Start with the vibes interview."]
        [:button {:type "button"
                  :class (stl/css :vibes-button-primary)
                  :on-click on-interview}
         "Set the vibes"]]
       [:*
        (for [{:keys [slug doc]} foundations]
          [:button {:key slug
                    :type "button"
                    :class (stl/css :foundation-card)
                    :on-click #(on-select slug)}
           [:span {:class (stl/css :foundation-card-icon)}
            [:> i/icon* {:icon-id (get foundation-icons slug i/document)}]]
           [:div {:class (stl/css :foundation-card-text)}
            [:div {:class (stl/css :foundation-card-title)}
             (dd/display-name slug)]
            (when-let [summary (foundation-summary doc)]
              [:div {:class (stl/css :foundation-card-summary)} summary])]])
        [:button {:type "button"
                  :class (stl/css :foundation-add)
                  :disabled true
                  :title "Agent-guided creation lands in the next phase"}
         "+ Add a foundation"]])]))

(mf/defc foundation-detail*
  "A non-vibes foundation, read-only for now (US #38 phase 06) — the doc
  rendered like any DESIGN.md. Editing, removal and the agent input arrive
  with phase 07; Vibes routes to `vibes-view*` instead, which already has
  the full edit story."
  {::mf/private true}
  [{:keys [slug]}]
  (let [foundations (mf/deref dd/foundations-ref)
        doc         (some #(when (= slug (:slug %)) (:doc %)) foundations)]
    (if (some? doc)
      [:div {:class (stl/css :vibes-view)}
       [:> design-doc-view* {:doc doc}]]
      [:div {:class (stl/css :vibes-empty)}
       [:p {:class (stl/css :vibes-empty-text)}
        "This foundation is gone — someone may have removed it just now."]])))

(defn- vec-remove
  [v i]
  (vec (concat (subvec v 0 i) (subvec v (inc i)))))

(mf/defc vibes-token-form*
  "The DESIGN.md frontmatter as form fields (US #38): the user edits token
  names and values through inputs — never raw YAML — and edit-model→serialize
  guarantees the saved doc stays structurally valid. `state` is the parent's
  edit-state atom; every input writes an [:model …] path into it."
  {::mf/private true}
  [{:keys [state]}]
  ;; plain deref, NOT mf/deref: `state` is the parent's use-state handle (no
  ;; IWatchable); the parent re-renders on every swap! and takes us with it
  (let [edit    (deref state)
        model   (:model edit)
        set-in  (fn [path]
                  (fn [event]
                    (swap! state assoc-in (cons :model path)
                           (dom/get-value (dom/get-target event)))))
        add-row (fn [k row]
                  (fn [] (swap! state update-in [:model k] (fnil conj []) row)))
        rm-row  (fn [k i]
                  (fn [] (swap! state update-in [:model k] vec-remove i)))
        scale-section
        (fn [k title add-label]
          (mf/html
           [:*
            [:div {:class (stl/css :vibes-form-section-title)} title]
            (for [[i {:keys [name value]}] (map-indexed vector (get model k))]
              [:div {:key (dm/str title i) :class (stl/css :vibes-form-row)}
               [:input {:class (stl/css :vibes-form-input)
                        :placeholder "name (sm, md…)"
                        :value name
                        :on-change (set-in [k i :name])}]
               [:input {:class (stl/css :vibes-form-input :vibes-form-input-mono)
                        :placeholder "value (8px)"
                        :value value
                        :on-change (set-in [k i :value])}]
               [:button {:type "button"
                         :class (stl/css :vibes-form-remove)
                         :aria-label (dm/str "Remove " title " token")
                         :on-click (rm-row k i)}
                "×"]])
            [:button {:type "button"
                      :class (stl/css :vibes-form-add)
                      :on-click (add-row k {:name "" :value ""})}
             add-label]]))]

    [:div {:class (stl/css :vibes-form)}
     [:div {:class (stl/css :vibes-form-field)}
      [:span {:class (stl/css :vibes-form-label)} "Name"]
      [:input {:class (stl/css :vibes-form-input)
               :placeholder "the design system's name"
               :value (:name model)
               :on-change (set-in [:name])}]]
     [:div {:class (stl/css :vibes-form-field)}
      [:span {:class (stl/css :vibes-form-label)} "Description"]
      [:input {:class (stl/css :vibes-form-input)
               :placeholder "identity in one line"
               :value (:description model)
               :on-change (set-in [:description])}]]

     [:div {:class (stl/css :vibes-form-section-title)} "Colors"]
     (for [[i {:keys [name value]}] (map-indexed vector (:colors model))]
       [:div {:key (dm/str "color" i) :class (stl/css :vibes-form-row)}
        [:span {:class (stl/css :vibes-form-swatch)
                :style #js {:backgroundColor value}}]
        [:input {:class (stl/css :vibes-form-input)
                 :placeholder "name (primary…)"
                 :value name
                 :on-change (set-in [:colors i :name])}]
        [:input {:class (stl/css :vibes-form-input :vibes-form-input-mono)
                 :placeholder "#rrggbb or any CSS color"
                 :value value
                 :on-change (set-in [:colors i :value])}]
        [:button {:type "button"
                  :class (stl/css :vibes-form-remove)
                  :aria-label "Remove color"
                  :on-click (rm-row :colors i)}
         "×"]])
     [:button {:type "button"
               :class (stl/css :vibes-form-add)
               :on-click (add-row :colors {:name "" :value ""})}
      "+ Add color"]

     [:div {:class (stl/css :vibes-form-section-title)} "Typography"]
     (for [[i row] (map-indexed vector (:typography model))]
       [:div {:key (dm/str "type" i) :class (stl/css :vibes-form-type)}
        [:div {:class (stl/css :vibes-form-row)}
         [:input {:class (stl/css :vibes-form-input)
                  :placeholder "role (heading, body…)"
                  :value (:name row)
                  :on-change (set-in [:typography i :name])}]
         [:button {:type "button"
                   :class (stl/css :vibes-form-remove)
                   :aria-label "Remove typography role"
                   :on-click (rm-row :typography i)}
          "×"]]
        [:div {:class (stl/css :vibes-form-type-grid)}
         [:input {:class (stl/css :vibes-form-input)
                  :placeholder "family (Inter)"
                  :value (:family row)
                  :on-change (set-in [:typography i :family])}]
         [:input {:class (stl/css :vibes-form-input)
                  :placeholder "size (14px)"
                  :value (:size row)
                  :on-change (set-in [:typography i :size])}]
         [:input {:class (stl/css :vibes-form-input)
                  :placeholder "weight (600)"
                  :value (:weight row)
                  :on-change (set-in [:typography i :weight])}]
         [:input {:class (stl/css :vibes-form-input)
                  :placeholder "line height (1.5)"
                  :value (:line-height row)
                  :on-change (set-in [:typography i :line-height])}]]])
     [:button {:type "button"
               :class (stl/css :vibes-form-add)
               :on-click (add-row :typography {:name "" :family "" :size ""
                                               :weight "" :line-height ""
                                               :extra {}})}
      "+ Add role"]

     (scale-section :rounded "Rounded" "+ Add radius")
     (scale-section :spacing "Spacing" "+ Add step")

     (when (contains? (:extra model) "components")
       [:p {:class (stl/css :vibes-form-note)}
        "components tokens are kept as-is — edit them through the agent for now"])]))

(mf/defc vibes-view*
  "The project vibes document: rendered markdown with Edit / Re-run interview /
  Delete, an editor with the same size cap the tool enforces, and an empty
  state that starts the interview. Editing splits by format: a DESIGN.md doc
  gets the token FORM + a body textarea (raw YAML is never shown), a legacy
  doc keeps the plain textarea plus an 'Add design tokens' path into the form.
  Deleting is a two-click inline confirm — and it goes through the changes
  pipeline, so it is undoable like any edit. `on-interview` seeds the chat
  composer with the vibes trigger and switches to the chat view."
  {::mf/private true}
  [{:keys [on-interview]}]
  (let [doc       (mf/deref dd/doc-ref)
        ;; nil = reading; {:mode :raw :text s} = legacy textarea;
        ;; {:mode :form :model m :body s} = token form + body
        edit*     (mf/use-state nil)
        edit      (deref edit*)
        editing?  (some? edit)
        confirm?* (mf/use-state false)
        confirm?  (deref confirm?*)

        ;; what Save would persist — the cap and the validation gate both run
        ;; against the SERIALIZED doc, exactly like the agent's tool path
        candidate (when edit
                    (if (= :raw (:mode edit))
                      (str/trim (or (:text edit) ""))
                      (dmd/serialize
                       {:frontmatter (dmd/edit-model->frontmatter (:model edit))
                        :body (str/trim (or (:body edit) ""))})))
        problem   (when edit (dd/doc-problem candidate))

        on-edit   (mf/use-fn
                   (mf/deps doc)
                   (fn []
                     (reset! confirm?* false)
                     (let [{:keys [frontmatter body]} (dmd/parse (or doc ""))]
                       (reset! edit*
                               (if frontmatter
                                 {:mode :form
                                  :model (dmd/edit-model frontmatter)
                                  :body body}
                                 {:mode :raw :text (or doc "")})))))
        on-raw-change  (mf/use-fn
                        #(swap! edit* assoc :text (dom/get-value (dom/get-target %))))
        on-body-change (mf/use-fn
                        #(swap! edit* assoc :body (dom/get-value (dom/get-target %))))
        on-add-tokens  (mf/use-fn
                        #(swap! edit* (fn [{:keys [text]}]
                                        {:mode :form
                                         :model (dmd/empty-scaffold)
                                         :body (or text "")})))
        on-cancel (mf/use-fn #(reset! edit* nil))
        on-save   (mf/use-fn
                   (mf/deps candidate problem)
                   (fn []
                     (when-not problem
                       (when-let [file-id (:current-file-id @st/state)]
                         (st/emit! (dd/set-doc file-id candidate))
                         (reset! edit* nil)))))
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
       (if (= :raw (:mode edit))
         [:*
          [:textarea {:class (stl/css :vibes-editor)
                      :value (:text edit)
                      :rows 18
                      :on-change on-raw-change}]
          [:button {:type "button"
                    :class (stl/css :vibes-form-add)
                    :on-click on-add-tokens}
           "+ Add design tokens"]]
         [:*
          [:> vibes-token-form* {:state edit*}]
          [:div {:class (stl/css :vibes-form-section-title)} "Body (markdown)"]
          [:textarea {:class (stl/css :vibes-editor)
                      :value (:body edit)
                      :rows 12
                      :on-change on-body-change}]])
       [:div {:class (stl/css-case :vibes-counter true
                                   :vibes-counter-over (some? problem))}
        (dm/str (count candidate) " / " dd/max-doc-chars)]
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
       [:> design-doc-view* {:doc doc}]
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
  [{:keys [selected on-select on-create on-promote]}]
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
                           :on-toggle #(toggle (:name skill) %)
                           :on-close #(on-select nil)
                           :on-promote #(on-promote skill)}])
      [:div {:class (stl/css :skills-tab)}
       ;; Project vibes moved to the Foundations view (US #38) — reached from
       ;; the compass header icon, alongside any other standing context.
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
            (for [{:keys [name label blurb reactive user?] :as entry} rows]
              [:> skill-row* {:key name
                              :label label
                              :blurb blurb
                              :reactive reactive
                              :user? user?
                              :enabled (get enabled-map name true)
                              :on-open #(on-select name)
                              :on-set-enabled #(toggle name %)
                              :on-promote #(on-promote entry)}])])
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
  "The guided creation flow (US #9): capture what / trigger / reactive behavior
  (with a proposed default), then generate the skill doc and persist it. Lives in
  the Skills view; the header owns the back nav. `seed` prefills the description
  when started from Chat. `on-created` closes the flow (the new card shows up)."
  {::mf/private true}
  [{:keys [settings seed on-created]}]
  (let [what*     (mf/use-state (or seed ""))
        trigger*  (mf/use-state "")
        reactive* (mf/use-state nil)
        status*   (mf/use-state :idle)

        what      (deref what*)
        trigger   (deref trigger*)
        proposed  (propose-reactive what)
        reactive  (or (deref reactive*) proposed)
        status    (deref status*)
        busy?     (= status :generating)
        ready?    (and (seq (str/trim what)) (some? settings) (not busy?))

        on-what    (mf/use-fn #(reset! what* (dom/get-value (dom/get-target %))))
        on-trigger (mf/use-fn #(reset! trigger* (dom/get-value (dom/get-target %))))

        submit
        (mf/use-fn
         (mf/deps what trigger reactive settings busy?)
         (fn []
           (when (and (seq (str/trim what)) settings (not busy?))
             (reset! status* :generating)
             (st/emit!
              (dusk/create-from-answers
               settings
               {:what (str/trim what) :trigger (str/trim trigger) :reactive reactive}
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

       [:label {:class (stl/css :create-label)}
        "Should it only respond when asked, or keep an eye on things and let you know?"]
       [:div {:class (stl/css :create-modes)}
        (for [[m lbl] [["on-demand" "💬 On-demand"] ["observer" "👁 Observer"]]]
          [:button {:key m
                    :type "button"
                    :class (stl/css-case :create-mode true :selected (= m reactive))
                    :disabled busy?
                    :on-click #(reset! reactive* m)}
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

(mf/defc skill-promote*
  "The promote-to-team confirmation (US #12): review the team-facing name +
  description the skill's presentation gets before it goes live, then publish.
  Lives in the Skills view; the header owns the back nav. `on-done` pops back to
  the list (used for Cancel and after a successful publish — the refetch then
  surfaces the team card + the now-linked personal copy)."
  {::mf/private true}
  [{:keys [skill on-done]}]
  (let [team     (mf/deref refs/team)
        team-id  (:id team)
        name*    (mf/use-state (or (:label skill) ""))
        desc*    (mf/use-state (or (:what skill) (:blurb skill) ""))
        status*  (mf/use-state :idle)
        name     (deref name*)
        desc     (deref desc*)
        status   (deref status*)
        busy?    (= status :publishing)
        ready?   (and (seq (str/trim name)) (some? team-id) (not busy?))
        on-name  (mf/use-fn #(reset! name* (dom/get-value (dom/get-target %))))
        on-desc  (mf/use-fn #(reset! desc* (dom/get-value (dom/get-target %))))
        publish  (mf/use-fn
                  (mf/deps skill name desc team-id busy?)
                  (fn []
                    (when (and (seq (str/trim name)) team-id (not busy?))
                      (reset! status* :publishing)
                      (st/emit!
                       (dwts/promote-skill
                        {:source-id (:id skill) :team-id team-id
                         :name (str/trim name) :description (str/trim desc)}
                        {:on-success (fn [_] (reset! status* :idle) (on-done))
                         :on-error   (fn [_] (reset! status* :error))})))))]
    (if (nil? team-id)
      [:div {:class (stl/css :skill-create)}
       [:p {:class (stl/css :create-guard)} "Open a team file to promote a skill."]]
      [:div {:class (stl/css :skill-create)}
       [:p {:class (stl/css :create-intro)}
        "Teammates will see this in Agent Skills. Review before publishing."]

       [:label {:class (stl/css :create-label)} "Name"]
       [:input {:class (stl/css :create-input)
                :value name
                :disabled busy?
                :on-change on-name}]

       [:label {:class (stl/css :create-label)} "Description"]
       [:textarea {:class (stl/css :create-input)
                   :value desc
                   :disabled busy?
                   :on-change on-desc}]

       ;; reactive behavior travels with the skill; foundations don't (they're
       ;; per-file — the skill reads whatever file it runs in) — US #12/#14
       [:p {:class (stl/css :promote-note)}
        (dm/str (get ask/reactive-label (:reactive skill) (:reactive skill))
                " · reads file foundations")]

       (when (= status :error)
         [:p {:class (stl/css :create-error)}
          "Couldn't publish that skill — try again."])

       [:div {:class (stl/css :promote-actions)}
        [:button {:type "button" :class (stl/css :promote-cancel)
                  :disabled busy? :on-click on-done}
         "Cancel"]
        [:button {:type "button" :class (stl/css :create-submit)
                  :disabled (not ready?) :on-click publish}
         (if busy? "Publishing…" "Publish")]]])))

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

(mf/defc chat-controls*
  "Header controls for the chat view: start a new conversation and browse this
  file's saved ones. Self-contained — derefs its own refs and owns the History
  popover (outside-click + Escape close, the model-picker pattern). The
  container ref wraps the triggers too, so opening the menu and clicking its
  own trigger don't fight.

  Everything is disabled while a turn runs: loading or deleting a conversation
  mid-turn would rip the history out from under `run-turn`."
  {::mf/private true}
  []
  (let [chats    (mf/deref refs/ai-panel-chats)
        chat-id  (mf/deref refs/ai-panel-chat-id)
        messages (mf/deref refs/ai-panel-messages)
        busy?    (mf/deref refs/ai-panel-busy?)

        open*    (mf/use-state false)
        open?    (deref open*)
        root-ref (mf/use-ref nil)

        ;; inline rename: the id of the row being edited + the draft title
        editing* (mf/use-state nil)
        editing  (deref editing*)
        draft*   (mf/use-state "")
        draft    (deref draft*)

        on-toggle (mf/use-fn #(swap! open* not))
        on-new    (mf/use-fn
                   (mf/deps busy?)
                   (fn []
                     (when-not busy?
                       (st/emit! (dwach/new-chat)))))]

    (mf/with-effect [open?]
      (when ^boolean open?
        (let [on-doc (fn [event]
                       (let [node (mf/ref-val root-ref)]
                         (when (and node (not (.contains node (dom/get-target event))))
                           (reset! open* false))))
              on-key (fn [event]
                       (when (= "Escape" (.-key event))
                         (dom/prevent-default event)
                         (reset! open* false)
                         ;; focus would otherwise fall back to <body>
                         (some-> (mf/ref-val root-ref)
                                 (.querySelector "button:not([disabled])")
                                 (dom/focus!))))]
          (.addEventListener js/document "pointerdown" on-doc)
          (.addEventListener js/document "keydown" on-key)
          (fn []
            (.removeEventListener js/document "pointerdown" on-doc)
            (.removeEventListener js/document "keydown" on-key)))))

    [:div {:class (stl/css :chat-controls)
           :ref root-ref}
     ;; disabled on an empty chat: "new" from nothing is a no-op
     [:> icon-button* {:variant "ghost"
                       :aria-label "New chat"
                       :disabled (or busy? (empty? messages))
                       :on-click on-new
                       :icon i/add}]
     (when (seq chats)
       [:> icon-button* {:variant "ghost"
                         :aria-label "Chat history"
                         :aria-haspopup "listbox"
                         :aria-expanded open?
                         :on-click on-toggle
                         :icon i/history}])

     (when (and open? (seq chats))
       [:div {:class (stl/css :chat-history-menu)
              :role "listbox"}
        (for [{:keys [id title updated-at]} chats]
          (let [commit-rename
                (fn []
                  (let [next (str/trim draft)]
                    (when (and (seq next) (not= next title))
                      (st/emit! (dwach/rename-chat id next))))
                  (reset! editing* nil))]
            [:div {:key (dm/str id)
                   :class (stl/css-case :chat-history-row true
                                        :chat-history-active (= id chat-id))}
             (if (= id editing)
               ;; renaming: the row is an input. Enter/blur commit, Escape
               ;; cancels — stopping propagation so the menu's own Escape
               ;; handler doesn't also close the popover.
               [:input {:class (stl/css :chat-history-rename-input)
                        :type "text"
                        :value draft
                        :auto-focus true
                        :on-focus #(.select (dom/get-target %))
                        :on-change #(reset! draft* (dom/get-value (dom/get-target %)))
                        :on-blur commit-rename
                        :on-key-down (fn [event]
                                       (case (.-key event)
                                         "Enter"  (commit-rename)
                                         "Escape" (do (dom/stop-propagation event)
                                                      (reset! editing* nil))
                                         nil))}]
               [:*
                [:button {:type "button"
                          :class (stl/css :chat-history-select)
                          :role "option"
                          :aria-selected (= id chat-id)
                          :disabled busy?
                          :on-click #(do (st/emit! (dwach/load-chat id))
                                         (reset! open* false))}
                 [:span {:class (stl/css :chat-history-title)}
                  (if (str/blank? title) "Untitled chat" title)]
                 [:span {:class (stl/css :chat-history-time)}
                  (ct/timeago updated-at)]]
                ;; rename stays enabled while busy — it only touches metadata,
                ;; never the live history
                [:> icon-button* {:variant "ghost"
                                  :aria-label "Rename conversation"
                                  :class (stl/css :chat-history-rename)
                                  :on-click #(do (reset! draft* (or title ""))
                                                 (reset! editing* id))
                                  :icon i/pentool}]
                [:> icon-button* {:variant "ghost"
                                  :aria-label "Delete conversation"
                                  :class (stl/css :chat-history-delete)
                                  :disabled busy?
                                  :on-click #(st/emit! (dwach/delete-chat id))
                                  :icon i/delete}]])]))])]))

(mf/defc ai-panel*
  "The Agent panel shell. Chat is the home surface and fills the body; Skills is a
  full-panel view reached from a muted header icon (US #35). The view is in-memory
  and defaults to chat, so closing and reopening the panel always lands on chat
  (US #2) — there are no tabs. The panel is closed from the workspace toggle
  (Alt+B), so the header carries no close button.

  Skills and Foundations are each two levels — a list and one entry's detail —
  both owned here so the header back pops a single level: detail → list → chat."
  [_props]
  (let [view*       (mf/use-state :chat)
        view        (deref view*)
        skills?     (= view :skills)
        foundations? (= view :foundations)

        ;; The open skill within the Skills view (nil = the list) and whether the
        ;; create flow is open. Both lifted here so the header back pops one level.
        skill*      (mf/use-state nil)
        skill       (deref skill*)
        creating*   (mf/use-state false)
        creating?   (deref creating*)
        ;; the skill being promoted to the team (nil = not promoting) — another
        ;; leaf within Skills; the header back pops it to the list (US #12)
        promoting*  (mf/use-state nil)
        promoting   (deref promoting*)
        ;; the open foundation within the Foundations view (a slug; nil = list)
        foundation* (mf/use-state nil)
        foundation  (deref foundation*)
        ;; description carried over when creation is started from Chat (US #9).
        seed*       (mf/use-state nil)

        open-skills (mf/use-fn (fn [] (reset! creating* false) (reset! skill* nil) (reset! promoting* nil) (reset! view* :skills)))
        on-select   (mf/use-fn #(reset! skill* %))
        open-create (mf/use-fn (fn [] (reset! seed* nil) (reset! creating* true)))
        open-promote (mf/use-fn (fn [sk] (reset! promoting* sk)))
        close-promote (mf/use-fn (fn [] (reset! promoting* nil)))
        on-created  (mf/use-fn #(reset! creating* false))
        open-foundations   (mf/use-fn (fn [] (reset! foundation* nil) (reset! view* :foundations)))
        on-open-foundation (mf/use-fn #(reset! foundation* %))
        ;; "Set the vibes" / "Re-run interview": hand the chat a ready-to-send
        ;; trigger and land there — the user presses Enter themselves.
        on-vibes-interview
        (mf/use-fn
         (fn []
           (st/emit! (dwaip/seed-composer "Set the design vibes for this project."))
           (reset! foundation* nil)
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
        ;; values (in deps) — reading the atoms from a no-deps callback captures
        ;; their initial nil/false and jumps straight to chat.
        on-back     (mf/use-fn
                     (mf/deps skill creating? promoting foundation view)
                     (fn []
                       (cond
                         (and (= view :skills) creating?)         (reset! creating* false)   ;; create → list
                         (and (= view :skills) (some? promoting)) (reset! promoting* nil)    ;; promote → list
                         (and (= view :skills) (some? skill))     (reset! skill* nil)        ;; detail → list
                         (and (= view :foundations)
                              (some? foundation))                 (reset! foundation* nil)   ;; detail → list
                         :else                                    (reset! view* :chat))))    ;; list → chat

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
                                              (inc font-step))))

        ;; "More actions" overflow menu next to the Skills icon. `more-ref` is the
        ;; dropdown's container so clicking a row (e.g. the A−/A+ stepper) keeps
        ;; the menu open — only a click outside it closes.
        more-open*  (mf/use-state false)
        more-open?  (deref more-open*)
        more-ref    (mf/use-ref nil)
        toggle-more (mf/use-fn #(swap! more-open* not))
        close-more  (mf/use-fn #(reset! more-open* false))]

    ;; Providers are configured on the settings page; load them so we know
    ;; whether to show the chat or the connect-a-provider prompt. Skill state
    ;; (per-account + this file's overrides) and the user's created skills drive
    ;; which skills the agent routes to, so load them up front too. Saved
    ;; conversations load with restore: an empty panel lands back in the file's
    ;; most recent conversation (hard-refresh survival).
    (mf/with-effect []
      (st/emit! (dai/fetch-ai-providers)
                (skst/fetch-skill-states)
                (dusk/fetch-user-skills)
                ;; team-promoted skills for the current team (US #12) — every
                ;; member picks up promotions on panel open
                (dwts/fetch-team-skills (:id (deref refs/team)))
                (dwach/fetch-chats true)))

    [:aside {:class (stl/css :ai-panel)
             :style #js {:width (dm/str width "px")
                         "--ai-font-scale" (dm/str font-scale)}}
     ;; Left-edge drag handle — the right-docked panel's resizable edge.
     [:div {:class (stl/css :resize-area)
            :on-pointer-down (:on-pointer-down resize)
            :on-pointer-move (:on-pointer-move resize)
            :on-lost-pointer-capture (:on-lost-pointer-capture resize)}]

     ;; Adaptive header (sized to the workspace right-header band): chat shows the
     ;; "Agent" title + the muted Foundations/Skills icons; a visited view swaps
     ;; those for a back arrow and its own title.
     [:div {:class (stl/css :header)}
      (if (or skills? foundations?)
        [:div {:class (stl/css :header-lead)}
         [:> icon-button* {:variant "ghost"
                           :aria-label "Back"
                           :on-click on-back
                           :icon i/arrow-left}]
         [:span {:class (stl/css :title)}
          (cond
            foundations? (if foundation (dd/display-name foundation) "Foundations")
            creating?    "New skill"
            promoting    "Promote to team"
            skill        "Skill info"
            :else        "Skills")]]
        [:span {:class (stl/css :title)} "Agent"])
      (when-not (or skills? foundations?)
        [:div {:class (stl/css :header-actions)}
         ;; Conversation controls (new chat + history) lead the band — they
         ;; act on the chat itself, where the rest configure the panel. (The
         ;; A−/A+ stepper moved into the More-actions menu below.)
         [:> chat-controls*]
         [:> icon-button* {:variant "ghost"
                           :aria-label "Open Foundations"
                           :on-click open-foundations
                           :icon i/compass}]
         [:> icon-button* {:variant "ghost"
                           :aria-label "Open Skills"
                           :on-click open-skills
                           :icon i/list-checks}]
         ;; More actions — a dropdown of extra controls. For now: the text-size
         ;; stepper (the header itself never scales, so it stays put).
         [:div {:class (stl/css :more-actions)
                :ref more-ref}
          [:> icon-button* {:variant "ghost"
                            :aria-label "More actions"
                            :on-click toggle-more
                            :icon i/menu}]
          [:& dropdown {:show more-open? :on-close close-more :container more-ref}
           [:div {:class (stl/css :more-menu)}
            [:div {:class (stl/css :more-row)}
             [:span {:class (stl/css :more-row-label)} "Text size"]
             [:div {:class (stl/css :font-stepper)}
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
               "A+"]]]]]]])]

     [:div {:class (stl/css :body)}
      (cond
        ;; Skills is a static catalog — reachable even before a provider is set up.
        skills?       (cond
                        creating? [:> skill-create* {:settings settings :seed (deref seed*) :on-created on-created}]
                        promoting [:> skill-promote* {:skill promoting :on-done close-promote}]
                        :else     [:> skills-tab* {:selected skill
                                                   :on-select on-select
                                                   :on-create open-create
                                                   :on-promote open-promote}])
        ;; Foundations: the file's standing design context (US #38). Vibes keeps
        ;; its full view (edit/interview/delete); other foundations are read-only
        ;; until the phase-07 detail work.
        foundations?  (cond
                        (= foundation dd/vibes-slug)
                        [:> vibes-view* {:on-interview on-vibes-interview}]

                        (some? foundation)
                        [:> foundation-detail* {:slug foundation}]

                        :else
                        [:> foundations-list* {:on-select on-open-foundation
                                               :on-interview on-vibes-interview}])
        (empty? pool) [:> connect-empty*]
        :else         [:> chat-tab* {:on-create-skill on-create-skill}])]]))
