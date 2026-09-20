;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.session-recorder
  "The record control and the session browser (phase 08 of the
  design-session-recording plan).

  This namespace is also what puts the recorder into the `:main` build: nothing
  required `session-recorder`/`session-persist` before, so shadow-cljs left them
  out of the app entirely and none of phases 02–07 could be exercised in a
  browser.

  ## Recording state must be impossible to misread

  A recording captures identifiable activity by people who did not press the
  button. The control therefore never shows an ambiguous state: it is either
  plainly idle or plainly recording, with a live event count while active, and it
  says out loud when a recording has stopped persisting (`:local-only?`) or was
  ended by a cap rather than by a person.

  While a session runs, `recording-timer*` shows elapsed time, the event count and
  a Stop action as a card at the top of the panel body, shaped like the observer
  notification cards beneath it.

  **Recording state is visible only inside the panel** (Santi, 2026-07-26: the
  workspace-header REC badge was removed as out of place). The state IS still
  broadcast over the websocket and lands on each collaborator's presence entry, so
  a future surface can show it without new plumbing — but as it stands a
  collaborator with the panel closed sees nothing, which is a product decision to
  revisit before this ships beyond the branch.

  DS gap worked around: there is no `record` or `stop` glyph. `stroke-circle` is
  tinted into the universal red dot (filled while live), and the sessions browser
  uses `clock` — `history` would have been the same glyph as the chat's own
  History button sitting next to it."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.main.data.workspace.ai-panel :as dwaip]
   [app.main.data.workspace.session-persist :as spersist]
   [app.main.data.workspace.session-recorder :as srec]
   [app.main.data.workspace.session-review :as srev]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.clipboard :as clipboard]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(defn- two-digits
  [n]
  (if (< n 10) (str "0" n) (str n)))

(defn- duration-label
  "Elapsed time as `m:ss`, or `h:mm:ss` past an hour."
  [ms]
  (let [total (max 0 (quot ms 1000))
        h     (quot total 3600)
        m     (mod (quot total 60) 60)
        s     (mod total 60)]
    (if (pos? h)
      (str h ":" (two-digits m) ":" (two-digits s))
      (str m ":" (two-digits s)))))

(defn- stop-reason-label
  "Why a recording ended. A cap must never read as a clean stop — the critique
  reasons from this, and so does the person reading the list."
  [reason]
  (case (some-> reason name)
    "manual"      nil
    "file-closed" "ended when the file closed"
    "event-cap"   "stopped early: event limit reached"
    "time-cap"    "stopped early: time limit reached"
    "raw-cap"     "stopped early: buffer limit reached"
    (when reason (str "stopped: " (name reason)))))

;; NOTE: the session list deliberately shows no participant count. The list
;; endpoint returns metadata only — deriving "2 people, agent" needs the events,
;; and they are transit-encoded in jsonb, so counting distinct actors in SQL
;; would mean parsing transit keys in Postgres. It wants a denormalized column on
;; `design_session`; noted as a follow-up rather than faked here.

;; --- the live control

(mf/defc recording-timer*
  "The active-recording card, rendered at the TOP of the panel body — above the
  observer alerts.

  While a session is being recorded this is the most important state in the
  panel, so it gets a full card rather than a cramped header readout, and it
  carries the stop action so stopping never means hunting through the header.
  Renders nothing when no recording is running."
  []
  (let [session    (mf/deref refs/session-recorder)
        recording? (true? (:active? session))

        ;; a ticking clock rather than a static number: a recording that looks
        ;; frozen is indistinguishable from one that has died
        now*  (mf/use-state #(inst-ms (js/Date.)))
        now   (deref now*)

        on-stop (mf/use-fn #(st/emit! (srec/stop-recording)))]

    (mf/with-effect [recording?]
      (when recording?
        (let [id (js/setInterval #(reset! now* (inst-ms (js/Date.))) 1000)]
          #(js/clearInterval id))))

    (when recording?
      ;; wrapper mirrors `.observer-list`: same horizontal chat padding, so the
      ;; card lines up with the notification cards below it rather than floating
      ;; at a different inset
      [:div {:class (stl/css :timer-list)}
       [:div {:class (stl/css :timer-card)}
        [:div {:class (stl/css :timer-body)}
         [:span {:class (stl/css :rec-dot)}]
         [:div {:class (stl/css :timer-main)}
          [:span {:class (stl/css :timer-elapsed)}
           (duration-label (- now (:started-at session 0)))]
          [:span {:class (stl/css :timer-meta)}
           (let [n (count (:events session []))]
             (str "Recording this session · " n (if (= 1 n) " event" " events")))]]

         (when (:local-only? session)
           [:span {:class (stl/css :status-warning)
                   :title (str "The last " spersist/max-flush-failures
                               " saves failed. Recording continues in this browser "
                               "but is no longer being stored.")}
            "not saving"])

         [:button {:type "button"
                   :class (stl/css :timer-stop)
                   :on-click on-stop}
          "Stop"]]]])))

(mf/defc session-row*
  {::mf/private true}
  [{:keys [session on-export on-review]}]
  (let [started  (:started-at session)
        stopped  (:stopped-at session)
        reason   (stop-reason-label (:stop-reason session))
        events   (:event-count session 0)
        dropped  (:raw-dropped session 0)]
    [:li {:class (stl/css :session-row)}
     [:div {:class (stl/css :session-main)}
      [:span {:class (stl/css :session-when)}
       (if started (ct/timeago started) "just now")]
      [:span {:class (stl/css :session-meta)}
       (str events (if (= 1 events) " event" " events")
            (when (and started stopped)
              (str " · " (duration-label (- (inst-ms stopped) (inst-ms started))))))]]

     (when-not stopped
       [:span {:class (stl/css :session-live)} "recording"])

     (when reason
       [:span {:class (stl/css :session-reason)} reason])

     (when (pos? dropped)
       [:span {:class (stl/css :session-reason)
               :title "The raw buffer rolled during this recording, so its raw
                       history is incomplete. The timeline itself is intact."}
        (str dropped " raw dropped")])

     ;; only a finished session can be reviewed: critiquing a recording that is
     ;; still running would judge an unfinished process
     (when stopped
       [:> icon-button* {:variant (if (:has-review session) "primary" "ghost")
                         :aria-label "Review this session"
                         :title (if (:has-review session)
                                  "Already reviewed — run it again"
                                  "Ask the agent to critique this session")
                         :on-click #(on-review (:id session))
                         :icon i/feedback}])

     [:> icon-button* {:variant "ghost"
                       :aria-label "Export this session"
                       :title "Copy this session as JSON"
                       :on-click #(on-export (:id session))
                       :icon i/download}]]))

(mf/defc sessions-popover*
  {::mf/private true}
  [{:keys [sessions on-export on-export-all on-review]}]
  [:div {:class (stl/css :popover)}
   [:div {:class (stl/css :popover-head)}
    [:span "Recorded sessions"]
    (when (seq sessions)
      [:button {:type "button"
                :class (stl/css :export-all)
                :on-click #(on-export-all)}
       "Export all"])]

   (if (empty? sessions)
     [:p {:class (stl/css :empty)}
      "No recordings yet. Press record to capture a design session."]
     [:ul {:class (stl/css :session-list)}
      (for [s sessions]
        [:> session-row* {:key (dm/str (:id s))
                          :session s
                          :on-export on-export
                          :on-review on-review}])])])

(mf/defc record-controls*
  "Start/stop a recording and browse this file's sessions.

  Export copies the bundle to the clipboard: the audience is a bot, and the
  admin's job is to hand it somewhere else. Non-admins get a clear rejection from
  the backend rather than a silent empty file."
  []
  (let [session    (mf/deref refs/session-recorder)
        sessions   (mf/deref refs/design-sessions)
        recording? (true? (:active? session))

        open*      (mf/use-state false)
        open?      (deref open*)
        note*      (mf/use-state nil)
        note       (deref note*)
        root-ref   (mf/use-ref nil)

        on-toggle-rec
        (mf/use-fn
         (mf/deps recording?)
         (fn []
           (if recording?
             (st/emit! (srec/stop-recording))
             (st/emit! (srec/start-recording)
                       (spersist/start-persisting)))))

        on-export
        (mf/use-fn
         (fn [id]
           (st/emit!
            (spersist/export-sessions
             (fn [json & [err]]
               (if json
                 (-> (clipboard/to-clipboard json)
                     (.then #(reset! note* "Copied to clipboard"))
                     ;; the Clipboard API is unavailable on an insecure origin;
                     ;; say so rather than appearing to have copied
                     (.catch #(reset! note* "Could not copy — clipboard unavailable")))
                 (reset! note* (case err
                                 :not-authorized "Only team admins can export"
                                 :sessions-database-unavailable "Recording storage is unavailable"
                                 "Export failed"))))
             (when id [id])))))

        on-export-all (mf/use-fn (fn [] (on-export nil)))

        on-review
        (mf/use-fn
         (fn [id]
           (reset! note* "Reviewing…")
           (st/emit!
            (spersist/load-session-for-review
             id
             (fn [session err]
               (if session
                 (st/emit!
                  (srev/review-session
                   session
                   (fn [[kind payload]]
                     (case kind
                       ;; the critique goes to the agent transcript: it is a
                       ;; reply from the agent about this file, and that is
                       ;; where the user already reads those
                       :review (do (st/emit! (dwaip/append-message "assistant" payload))
                                   (reset! note* "Review added to the chat")
                                   (st/emit! (spersist/fetch-sessions)))
                       :note   (st/emit! (dwaip/append-message "note" payload))
                       :usage  (st/emit! (dwaip/accumulate-review-usage payload))
                       :error  (reset! note* (str "Review failed: " (name payload)))
                       nil))))
                 (reset! note* (str "Could not load that session"
                                    (when err (str " (" (name err) ")"))))))))))

        on-toggle-list
        (mf/use-fn
         (fn []
           (swap! open* not)
           (reset! note* nil)
           (st/emit! (spersist/fetch-sessions))))]

    ;; A reload does not end a recording (phase 07): if this file had one in
    ;; progress, pick it up.
    ;;
    ;; LIMITATION: this runs when the control mounts, i.e. when the panel opens.
    ;; Edits made between a reload and reopening the panel are therefore missed.
    ;; Resuming from the workspace mount instead would close that gap and is the
    ;; right fix if recording outgrows the panel.
    (mf/with-effect []
      (st/emit! (spersist/resume-recording)))

    ;; outside-click + Escape close, the model-picker / History pattern
    (mf/with-effect [open?]
      (when ^boolean open?
        (let [on-doc (fn [event]
                       (let [node (mf/ref-val root-ref)]
                         (when (and node (not (.contains node (dom/get-target event))))
                           (reset! open* false))))
              on-key (fn [event]
                       (when (= "Escape" (.-key event))
                         (dom/prevent-default event)
                         (reset! open* false)))]
          (.addEventListener js/document "pointerdown" on-doc true)
          (.addEventListener js/document "keydown" on-key true)
          #(do (.removeEventListener js/document "pointerdown" on-doc true)
               (.removeEventListener js/document "keydown" on-key true)))))

    [:div {:class (stl/css :record-controls) :ref root-ref}
     ;; No glyph in the DS reads as "record", and `play` reads as "run", so the
     ;; record affordance is a circle styled as a red dot — the universal one.
     ;; While recording the elapsed time and stop live in `recording-timer*` at
     ;; the top of the panel body, not here.
     [:> icon-button* {:variant "ghost"
                       :class (stl/css-case :rec-button true :rec-button-live recording?)
                       :aria-label (if recording? "Stop recording" "Record this design session")
                       :title (if recording?
                                "Stop recording this design session"
                                "Record this design session")
                       :on-click on-toggle-rec
                       :icon i/stroke-circle}]

     ;; `clock` rather than `history`: the chat's own History button sits right
     ;; beside this one and two identical glyphs are indistinguishable.
     [:> icon-button* {:variant "ghost"
                       :aria-label "Recorded sessions"
                       :title "Recorded sessions"
                       :on-click on-toggle-list
                       :icon i/clock}]

     (when open?
       [:*
        [:> sessions-popover* {:sessions sessions
                               :on-export on-export
                               :on-export-all on-export-all
                               :on-review on-review}]
        (when note
          [:span {:class (stl/css :note)} note])])]))
