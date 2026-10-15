;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.agent-opt-out-test
  "The agent opt-out audit (#88). Every way the browser reaches a model
  provider is run against each account/file combination, and must send a
  request only where the agent is on. The first two tests name the two
  combinations the epic cares about; the rest close the gaps the audit
  found.

  These tests prove the gates in the browser. They say nothing about the
  server, which does not read the opt-out."
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.agent-access :as agent-access]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent :as agent]
   [app.main.data.workspace.agent-remote :as agent-remote]
   [app.main.data.workspace.ai-panel :as ai-panel]
   [app.main.data.workspace.session-review :as srev]
   [app.main.data.workspace.skill-gen :as sg]
   [app.main.data.workspace.user-skills :as user-skills]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.agent-opt-out :as opt-out]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(def ^:private file-id (uuid/next))

(def ^:private settings {:provider "anthropic" :model "claude-haiku-4-5-20251001"})

(def ^:private model-commands
  #{:ai-agent-round :agent-helper-round :app.util.sse/agent-service-turn})

(defn- with-agent-flag
  [f]
  (with-redefs [cf/flags (conj cf/flags :design-agent)]
    (f)))

(defn- state-with
  "A user in `file-id`. `account` is :on or :off. `file` is true, false,
  nil (follows the account) or :unknown (not loaded yet)."
  [account file]
  (cond-> {:profile {:id (uuid/next)
                     :props {:agent-disabled (= :off account)}}
           :current-file-id file-id}
    (not= :unknown file)
    (assoc-in [:agent-file-state file-id] (if (nil? file) :account file))))

(def ^:private shape-id (uuid/next))

(defn- panel-state
  "`state` with everything the entry points need to reach a provider if
  the agent is on: a history, an open panel, a paused turn, an edited
  shape and a connected provider for the observer's model."
  [state]
  (let [with-panel (-> state
                       ;; an editor: someone who may only view the file has
                       ;; no observer skill the semantic tick runs
                       (assoc :permissions {:can-edit true})
                       (assoc-in [:ai-panel file-id :open?] true)
                       (assoc-in [:ai-panel file-id :dirty-ids] #{shape-id})
                       (assoc-in [:ai-panel file-id :history]
                                 [{:role :user :text "hello"}
                                  {:role :assistant :text "hi"}])
                       (assoc-in [:ai-panel file-id :checkpoint]
                                 {:settings settings :history []}))
        model      (some :model (#'ai-panel/model-detect-skills
                                 (assoc-in with-panel [:agent-file-state file-id] true)))]
    (assoc with-panel :ai-providers
           {:anthropic {:provider "anthropic" :enabled-models [model]}})))

(defn- run
  "The model requests sent while `ev` runs (its watch phase, synchronously)
  against `state`."
  [ev state]
  (let [calls (atom [])]
    (with-redefs [rp/cmd! (mock/stub (fn [cmd & _]
                                       (swap! calls conj cmd)
                                       (rx/empty)))
                  dsh/lookup-page-objects
                  (mock/stub (fn [_] {shape-id {:name "Rectangle" :type :rect}}))]
      (opt-out/with-store-state
        state
        #(rx/sub! (ptk/watch ev state (rx/empty)) (fn [_]) (fn [_]))))
    (filterv model-commands @calls)))

(def ^:private entry-points
  "Every event that can end in a model request."
  {:send-message   #(ai-panel/send-message settings "hi" nil)
   :continue-turn  #(ai-panel/continue-turn)
   :summarize      #(ai-panel/summarize-into-new-chat)
   :semantic-tick  #(#'ai-panel/run-semantic-tick)
   :session-review #(srev/review-session {:id (uuid/next)
                                          :events [{:t 0 :type "select"}]}
                                         (fn [_]))
   :skill-creation #(user-skills/create-from-answers
                     settings {:what "Check copy" :trigger "Check" :reactive "on-call"}
                     {})})

(def ^:private combinations
  [{:account :on  :file nil      :on? true}
   {:account :on  :file true     :on? true}
   {:account :on  :file false    :on? false}
   {:account :on  :file :unknown :on? false}
   {:account :off :file nil      :on? false}
   {:account :off :file true     :on? true}
   {:account :off :file false    :on? false}
   {:account :off :file :unknown :on? false}])

(t/deftest every-entry-point-follows-the-effective-setting
  (with-agent-flag
    (fn []
      (doseq [{:keys [account file on?]} combinations
              [entry make] entry-points]
        (let [state (panel-state (state-with account file))
              calls (run (make) state)]
          (t/is (= on? (agent-access/agent-enabled? state file-id))
                (str "resolver, account " account ", file " file))
          (t/is (= on? (boolean (seq calls)))
                (str entry ", account " account ", file " file
                     ", sent " calls)))))))

(t/deftest account-off-and-file-on-works-only-in-that-file
  (with-agent-flag
    (fn []
      (let [state (panel-state (state-with :off true))]
        (t/is (true? (agent-access/agent-enabled? state file-id)))
        (t/is (true? (agent-access/panel-open? state)))
        (t/is (false? (agent-access/agent-enabled? state)))
        (t/is (seq (run (ai-panel/send-message settings "hi" nil) state)))
        (t/testing "another file follows the account"
          (let [other (assoc-in state [:agent-file-state (uuid/next)] :account)]
            (t/is (false? (agent-access/agent-enabled? other (uuid/next))))))))))

(t/deftest account-on-and-file-off-sends-nothing
  (with-agent-flag
    (fn []
      (let [state (panel-state (state-with :on false))]
        (t/is (false? (agent-access/agent-enabled? state file-id)))
        (t/is (not (agent-access/panel-open? state)))
        (t/is (true? (agent-access/agent-enabled? state)))
        (doseq [[entry make] entry-points]
          (t/is (empty? (run (make) state)) (str entry)))))))

;; --- The guard on each transport

(defn- guarded-requests
  "Subscribe to `make` (a request) with the store holding `state` and
  return the commands that went out and the error, if any."
  [make state]
  (let [calls (atom [])
        error (atom nil)]
    (with-redefs [rp/cmd! (mock/stub (fn [cmd & _]
                                       (swap! calls conj cmd)
                                       (rx/empty)))]
      (opt-out/with-store-state
        state
        #(rx/sub! (make) (fn [_]) (fn [e] (reset! error e)))))
    {:calls (filterv model-commands @calls) :error @error}))

(t/deftest the-guard-refuses-every-transport-while-off
  (with-agent-flag
    (fn []
      (let [answers   {:what "x" :trigger "x" :reactive "on-call"}
            transport {:skill-generation #(sg/generate-skill settings answers)
                       :side-turn        #(agent/run-side-turn
                                           {:model "m" :system "s" :user-text "u"
                                            :tools nil :max-rounds 1})
                       :helper-round     #(agent/detect-round settings "system" "hi")
                       :service-turn     #(agent-remote/run-turn
                                           (assoc settings :service {:session-id (uuid/next)
                                                                     :file-id file-id})
                                           [{:role :user :text "hi"}]
                                           "system")}]
        (doseq [[name make] transport]
          (let [off (guarded-requests make (state-with :on false))
                on  (guarded-requests make (state-with :off true))]
            (t/is (empty? (:calls off)) (str name))
            (t/is (= :agent-off (:code (ex-data (:error off)))) (str name))
            (t/is (= 1 (count (:calls on))) (str name))))))))

;; --- A chain that began while the agent was on (gap found by the audit)

(t/deftest switching-off-during-a-playbook-match-sends-no-round
  ;; A first message runs a playbook match before the turn. If the user
  ;; switches the agent off while it is in flight, the turn must not send
  ;; its first round when the match comes back.
  (with-agent-flag
    (fn []
      (doseq [off-during? [true false]]
        (let [on      (assoc-in (state-with :on nil) [:ai-panel file-id :history] [])
              off     (state-with :on false)
              stream  (rx/subject)
              pending (rx/subject)
              calls   (atom [])]
          (with-redefs [rp/cmd! (mock/stub (fn [cmd & _]
                                             (swap! calls conj cmd)
                                             (if (= 1 (count @calls))
                                               pending
                                               (rx/empty))))]
            (opt-out/with-store-state
              on
              (fn []
                (rx/sub! (ptk/watch (ai-panel/send-message settings "design a page" nil)
                                    on stream)
                         (fn [_]) (fn [_]))
                (t/is (= [:agent-helper-round] @calls) "the playbook match went out")
                (when off-during?
                  (reset! st/state off))
                ;; the match fails: the turn goes on without a playbook
                (rx/error! pending (ex-info "late" {}))))
            (t/is (= (if off-during?
                       [:agent-helper-round]
                       [:agent-helper-round :app.util.sse/agent-service-turn])
                     (filterv model-commands @calls))
                  (str "switched off during the match: " off-during?))))))))
