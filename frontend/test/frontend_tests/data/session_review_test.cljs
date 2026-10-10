;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.session-review-test
  "Pins the review turn (phase 09 of the design-session-recording plan).

  Two properties carry the phase and both are asserted here:

  - **The reviewer cannot mutate.** It runs tool-less, which is a stronger
    guarantee than an allowlist: a reviewing agent holding mutation tools would
    inevitably fix what it criticizes, corrupting the file under review and making
    its own critique unfalsifiable.
  - **A truncated record announces itself.** Reviewing part of a session and
    presenting it as the whole thing is precisely the failure this feature exists
    to catch in humans."
  (:require
   [app.main.data.workspace.session-review :as srev]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(defn- event
  [i who]
  (cond-> {:kind :move
           :label (str "moved shape " i)
           :who who
           :at (* i 1000)
           :shape-ids #{}
           :commits 1}
    (= :agent who) (assoc :model "claude-opus-4-8" :provider "anthropic")))

(def ^:private session
  {:id (uuid "00000000-0000-0000-0000-0000000000a1")
   :events [(event 1 :user) (event 2 :agent) (event 3 :user)]
   :stop-reason :manual
   :raw-dropped 0})

;; --- the prompt

(t/deftest the-prompt-carries-the-timeline-and-its-actors
  (let [p (srev/build-prompt session)]
    (t/is (str/includes? p "3 events"))
    (t/is (str/includes? p "moved shape 1"))
    (t/is (str/includes? p "claude-opus-4-8")
          "the acting model must reach the reviewer, or it cannot tell models apart")
    (t/is (str/includes? p "Participants:"))))

(t/deftest a-capped-session-tells-the-reviewer-the-record-may-be-partial
  (t/testing "a cap means the session probably continued past the record; a
              reviewer that does not know will read the end as the end"
    (let [p (srev/build-prompt (assoc session :stop-reason :event-cap))]
      (t/is (str/includes? p "event-cap"))
      (t/is (str/includes? p "may have continued beyond this record")))))

(t/deftest a-rolled-raw-buffer-is-disclosed-to-the-reviewer
  (let [p (srev/build-prompt (assoc session :raw-dropped 42))]
    (t/is (str/includes? p "42"))
    (t/is (str/includes? p "timeline itself is intact")
          "the distinction matters: raw was lossy, the timeline was not")))

(t/deftest a-manual-stop-adds-no-alarming-caveat
  (let [p (srev/build-prompt session)]
    (t/is (not (str/includes? p "may have continued beyond this record")))))

;; --- the char budget

(t/deftest a-timeline-within-budget-is-sent-whole
  (let [{:keys [text dropped]} (srev/windowed-text (:events session))]
    (t/is (= 0 dropped))
    (t/is (str/includes? text "moved shape 1"))))

(t/deftest an-oversized-timeline-keeps-the-NEWEST-events
  (t/testing "when something must go, the recent past is the useful part"
    (let [events (mapv #(event % :user) (range 1 40))
          {:keys [text dropped]} (srev/windowed-text events 300)]
      (t/is (pos? dropped))
      (t/is (<= (count text) 320) "trimmed to roughly the budget")
      (t/is (str/includes? text "moved shape 39") "newest kept")
      (t/is (not (str/includes? text "moved shape 1")) "oldest dropped"))))

(t/deftest truncation-is-disclosed-in-the-prompt-itself
  (t/testing "the reviewer must be told it is seeing part of a session — a
              critique of a partial record presented as complete is the exact
              failure this feature exists to catch"
    (let [events  (mapv #(event % :user) (range 1 40))
          ;; explicit small budget: 39 events render to ~1.3k chars, nowhere near
          ;; the real 60k ceiling, so forcing it is the honest way to test this
          p       (srev/build-prompt (assoc session :events events) 300)]
      (t/is (str/includes? p "OMITTED"))
      (t/is (str/includes? p "you should say so")))))

(t/deftest windowed-text-never-drops-the-last-event
  (t/testing "an impossible budget still yields something rather than looping"
    (let [{:keys [text]} (srev/windowed-text (:events session) 1)]
      (t/is (seq text)))))

;; --- read-only, structurally

(t/deftest the-review-prompt-asks-for-cited-evidence
  (t/testing "without this a model produces generic design advice, which is
              worthless as a session critique"
    (t/is (str/includes? srev/review-system "Cite evidence"))
    (t/is (str/includes? srev/review-system "Generic design advice"))))

(t/deftest the-review-system-prompt-explains-the-actor-vocabulary
  (t/testing "user vs agent is the whole point of the recording; a reviewer that
              does not understand the notation cannot critique the collaboration"
    (t/is (str/includes? srev/review-system "user"))
    (t/is (str/includes? srev/review-system "agent"))))

(t/deftest an-empty-session-is-reported-not-reviewed
  (t/testing "a recording that captured nothing is a valid, honest outcome and
              must not cost a model call"
    (let [seen (atom [])
          ev   (srev/review-session (assoc session :events [])
                                    (fn [e] (swap! seen conj e)))]
      (ptk/watch ev {} (rx/empty))
      (t/is (= [:note] (mapv first @seen)))
      (t/is (str/includes? (second (first @seen)) "captured no interactions")))))
