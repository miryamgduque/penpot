;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.session-actor-test
  "Pins the acting-agent marker (phase 03 of the design-session-recording plan).

  The agent has no mutation funnel — ~22 write sites across the tool families
  using nine different write paths — so an agent-driven change is identified by
  an ambient marker set around tool execution and read where the commit map is
  built.

  Two properties are load-bearing and both are pinned here:

  - **The marker outlives the tool by a grace window.** Writes settle after a
    tool's observable completes: `reflow-parent!` emits a `:layout/update` that
    `shape-layout.cljs` buffers by 100ms, so `create_shape`'s reposition commits
    land after the tool is done. Clearing on completion would attribute the
    agent's own reflow to whoever edits next.
  - **The marker EXPIRES on its own.** A turn cancelled mid-tool never runs its
    cleanup (`rx/take-until` unsubscribes), and a marker stuck on `:agent` would
    misattribute every subsequent human edit for the rest of the session. So
    expiry is a property of the marker itself, not of cleanup running."
  (:require
   [app.main.data.session-actor :as sa]
   [cljs.test :as t :include-macros true]))

(t/use-fixtures :each
  {:before (fn [] (sa/reset-actor!))
   :after  (fn [] (sa/reset-actor!))})

(def ^:private settings
  {:provider "anthropic" :model "claude-opus-4-8"})

;; --- the default is a human

(t/deftest no-actor-means-a-human-is-editing
  (t/testing "nothing marked = a person at the keyboard, the honest default"
    (t/is (nil? (sa/current-actor 1000)))))

;; --- marking an agent action

(t/deftest begin-marks-the-agent-with-its-model
  (let [_ (sa/begin-agent-action! settings 1000)]
    (t/is (= {:who :agent :provider "anthropic" :model "claude-opus-4-8"}
             (sa/current-actor 1000)))))

(t/deftest the-marker-survives-the-tool-by-a-grace-window
  (t/testing "a write that settles after the tool completes is still the agent's"
    (let [gen (sa/begin-agent-action! settings 1000)]
      (sa/end-agent-action! gen 1000)
      (t/is (some? (sa/current-actor 1000))
            "immediately after the tool: still the agent")
      (t/is (some? (sa/current-actor (+ 1000 (dec sa/grace-ms))))
            "inside the grace window: still the agent — this is the 100ms
             layout-reflow buffer plus margin")
      (t/is (nil? (sa/current-actor (+ 1000 sa/grace-ms 1)))
            "past the grace window: back to human"))))

(t/deftest the-grace-window-clears-the-known-async-tail
  (t/testing "grace must cover shape-layout's 100ms :layout/update buffer, which
              is what makes create_shape's reflow land after the tool returns"
    (t/is (> sa/grace-ms 100))))

;; --- expiry is intrinsic, not dependent on cleanup

(t/deftest a-marker-expires-even-if-cleanup-never-runs
  (t/testing "a cancelled turn unsubscribes without running end-agent-action!;
              the marker must heal itself or every later human edit is
              misattributed to the agent for the rest of the session"
    (sa/begin-agent-action! settings 1000)
    (t/is (some? (sa/current-actor 1000)))
    (t/is (nil? (sa/current-actor (+ 1000 sa/max-action-ms 1)))
          "the ceiling releases a leaked marker")))

(t/deftest reset-actor-clears-immediately
  (sa/begin-agent-action! settings 1000)
  (sa/reset-actor!)
  (t/is (nil? (sa/current-actor 1000))))

;; --- generations: a finished tool must not disturb a newer one

(t/deftest a-stale-end-does-not-shorten-a-newer-marker
  (t/testing "tools can overlap (run-tool runs under rx/mapcat, which
              interleaves); tool A finishing must not start the grace countdown
              on tool B's marker"
    (let [gen-a (sa/begin-agent-action! settings 1000)
          _     (sa/begin-agent-action! settings 1000)]
      ;; A finishes late, after B started
      (sa/end-agent-action! gen-a 1000)
      (t/is (some? (sa/current-actor (+ 1000 sa/grace-ms 1)))
            "B is still running, so the marker must NOT have entered grace"))))

(t/deftest a-newer-begin-replaces-the-previous-marker
  (let [gen-a (sa/begin-agent-action! settings 1000)
        gen-b (sa/begin-agent-action! {:provider "anthropic" :model "claude-haiku-4-5"} 1000)]
    (t/is (not= gen-a gen-b) "each action gets its own generation")
    (t/is (= "claude-haiku-4-5" (:model (sa/current-actor 1000)))
          "the most recent action's model is what a commit reports")))

(t/deftest ending-the-current-action-does-enter-grace
  (let [_     (sa/begin-agent-action! settings 1000)
        gen-b (sa/begin-agent-action! settings 1000)]
    (sa/end-agent-action! gen-b 1000)
    (t/is (nil? (sa/current-actor (+ 1000 sa/grace-ms 1)))
          "the newest generation ending does start the countdown")))
