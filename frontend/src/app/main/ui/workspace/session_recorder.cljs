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

  **Known gap, deliberately not solved here:** the indicator is visible only to
  the profile whose panel is open. A collaborator being recorded sees nothing.
  Closing that needs presence work (broadcasting recording state over the
  websocket) and is a blocker for shipping beyond this branch, not a nicety — see
  the phase file.

  DS gaps worked around: there is no `record` or `stop` glyph, so `play` starts a
  recording and a styled `stroke-circle` marks the active state."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.main.data.workspace.session-persist :as spersist]
   [app.main.data.workspace.session-recorder :as srec]
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

(mf/defc record-status*
  "The active-recording readout: elapsed time, event count, and any warning that
  the recording is no longer being saved."
  {::mf/private true}
  [{:keys [session]}]
  (let [;; a ticking clock rather than a static number: a recording that looks
        ;; frozen is indistinguishable from one that has died
        now*  (mf/use-state #(inst-ms (js/Date.)))
        now   (deref now*)]
    (mf/with-effect []
      (let [id (js/setInterval #(reset! now* (inst-ms (js/Date.))) 1000)]
        #(js/clearInterval id)))

    [:div {:class (stl/css :status)}
     [:span {:class (stl/css :rec-dot)}]
     [:span {:class (stl/css :status-text)}
      (str (duration-label (- now (:started-at session 0)))
           " · "
           (count (:events session []))
           " events")]
     (when (:local-only? session)
       [:span {:class (stl/css :status-warning)
               :title (str "The last " spersist/max-flush-failures
                           " saves failed. Recording continues in this browser "
                           "but is no longer being stored.")}
        "not saving"])]))

(mf/defc session-row*
  {::mf/private true}
  [{:keys [session on-export]}]
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

     [:> icon-button* {:variant "ghost"
                       :aria-label "Export this session"
                       :title "Copy this session as JSON"
                       :on-click #(on-export (:id session))
                       :icon i/download}]]))

(mf/defc sessions-popover*
  {::mf/private true}
  [{:keys [sessions on-export on-export-all]}]
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
                          :on-export on-export}])])])

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
     (when recording?
       [:> record-status* {:session session}])

     [:> icon-button* {:variant (if recording? "primary" "ghost")
                       :aria-label (if recording? "Stop recording" "Record session")
                       :title (if recording?
                                "Stop recording this design session"
                                "Record this design session")
                       :on-click on-toggle-rec
                       :icon (if recording? i/stroke-circle i/play)}]

     [:> icon-button* {:variant "ghost"
                       :aria-label "Recorded sessions"
                       :title "Recorded sessions"
                       :on-click on-toggle-list
                       :icon i/history}]

     (when open?
       [:*
        [:> sessions-popover* {:sessions sessions
                               :on-export on-export
                               :on-export-all on-export-all}]
        (when note
          [:span {:class (stl/css :note)} note])])]))
