;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.agent-file-switch-test
  "The per-file agent switch (#87): the file setting wins over the account
  one, panels close only where the agent ends up off, and no event that
  calls a model sends a request while the agent is off in the file."
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.agent-access :as agent-access]
   [app.main.data.agent-switch :as agent-switch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.ai-panel :as ai-panel]
   [app.main.data.workspace.session-review :as srev]
   [app.main.data.workspace.user-skills :as user-skills]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.agent-opt-out :as opt-out]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(def ^:private file-id (uuid/next))
(def ^:private other-id (uuid/next))

(defn- with-agent-flag
  [f]
  (with-redefs [cf/flags (conj cf/flags :design-agent)]
    (f)))

(defn- state-with
  "A user in `file-id` with the account switch `account` (:on or :off) and
  `file` as their loaded setting for that file: true, false, nil when it
  follows the account, or `:unknown` when it has not loaded."
  [account file]
  (cond-> {:profile {:id (uuid/next)
                     :props {:agent-disabled (= :off account)}}
           :current-file-id file-id}
    (not= :unknown file)
    (assoc-in [:agent-file-state file-id] (if (nil? file) :account file))))

(defn- emitted
  "Events the `ev` watch emits synchronously for `state`."
  [ev state]
  (let [out (atom [])]
    (opt-out/with-store-state
      state
      (fn []
        (rx/sub! (ptk/watch ev state (rx/empty)) (fn [e] (swap! out conj e)))))
    @out))

(defn- model-calls
  "Run `f` with the repo stubbed and return the model-proxy commands that
  were sent."
  [f]
  (let [calls (atom [])]
    (with-redefs [rp/cmd! (mock/stub (fn [cmd & _]
                                       (swap! calls conj cmd)
                                       (rx/empty)))]
      (f))
    (filterv #{:ai-agent-round :agent-helper-round :app.util.sse/agent-service-turn}
             @calls)))

;; --- Resolver

(t/deftest the-most-specific-setting-wins
  (with-agent-flag
    (fn []
      (t/testing "no file setting: the file follows the account"
        (t/is (true? (agent-access/agent-enabled? (state-with :on nil) file-id)))
        (t/is (false? (agent-access/agent-enabled? (state-with :off nil) file-id))))

      (t/testing "the file setting wins over the account"
        (t/is (true? (agent-access/agent-enabled? (state-with :off true) file-id)))
        (t/is (false? (agent-access/agent-enabled? (state-with :on false) file-id))))

      (t/testing "a file setting does not leak to the account"
        (t/is (false? (agent-access/agent-enabled? (state-with :off true))))
        (t/is (true? (agent-access/agent-enabled? (state-with :on false)))))

      (t/testing "until a file's setting is known the agent is off there"
        (t/is (false? (agent-access/agent-enabled? (state-with :on :unknown) file-id)))
        (t/is (false? (agent-access/agent-enabled? (state-with :on false) other-id)))
        (t/is (true? (agent-access/agent-enabled? (state-with :on :unknown)))))

      (t/testing "the agent routes stay at account scope"
        (t/is (false? (agent-access/route-allowed? (state-with :off true)
                                                   :dashboard-skills))))

      (t/testing "the panel only shows where the agent is on"
        (let [open #(assoc-in % [:ai-panel file-id :open?] true)]
          (t/is (true? (agent-access/panel-open? (open (state-with :off true)))))
          (t/is (false? (agent-access/panel-open? (open (state-with :on false))))))))))

(t/deftest the-instance-flag-wins-over-a-file-setting
  (with-redefs [cf/flags (disj cf/flags :design-agent)]
    (t/is (false? (agent-access/agent-enabled? (state-with :on true) file-id)))))

(t/deftest only-true-exceptions-are-stored
  (t/testing "with the account on, enabling a file follows the account"
    (t/is (nil? (agent-switch/stored-file-value (state-with :on nil) true)))
    (t/is (false? (agent-switch/stored-file-value (state-with :on nil) false))))
  (t/testing "with the account off, disabling a file follows the account"
    (t/is (nil? (agent-switch/stored-file-value (state-with :off nil) false)))
    (t/is (true? (agent-switch/stored-file-value (state-with :off nil) true)))))

;; --- Switch events

(t/deftest the-file-switch-closes-only-this-files-panel
  (with-agent-flag
    (fn []
      (let [state (-> (state-with :on nil)
                      (assoc-in [:agent-file-state other-id] :account)
                      (assoc-in [:ai-panel file-id] {:open? true :messages [:m]})
                      (assoc-in [:ai-panel other-id] {:open? true}))
            off   (ptk/update (agent-switch/apply-file-setting file-id false) state)
            back  (ptk/update (agent-switch/apply-file-setting file-id nil) off)]
        (t/is (false? (get-in off [:agent-file-state file-id])))
        (t/is (false? (get-in off [:ai-panel file-id :open?])))
        (t/is (= [:m] (get-in off [:ai-panel file-id :messages])))
        (t/is (true? (get-in off [:ai-panel other-id :open?])))
        (t/testing "clearing the setting follows the account and opens nothing"
          (t/is (= :account (get-in back [:agent-file-state file-id])))
          (t/is (true? (agent-access/agent-enabled? back file-id)))
          (t/is (false? (get-in back [:ai-panel file-id :open?]))))))))

(t/deftest the-account-switch-keeps-panels-in-files-turned-on
  (with-agent-flag
    (fn []
      (let [state (-> (state-with :on true)
                      (assoc-in [:agent-file-state other-id] :account)
                      (assoc-in [:ai-panel file-id :open?] true)
                      (assoc-in [:ai-panel other-id :open?] true))
            off   (ptk/update (agent-switch/apply-account-setting true) state)]
        (t/is (true? (get-in off [:ai-panel file-id :open?])))
        (t/is (false? (get-in off [:ai-panel other-id :open?])))
        (t/testing "the agent stays on where the user is, so nothing stops"
          (t/is (empty? (emitted (agent-switch/apply-account-setting true) off))))))))

(t/deftest switching-off-here-stops-background-work
  (with-agent-flag
    (fn []
      (let [types #(mapv ptk/type %)]
        (t/is (= [agent-access/turned-off]
                 (types (emitted (agent-switch/apply-file-setting file-id false)
                                 (state-with :on false)))))
        (t/is (empty? (emitted (agent-switch/apply-file-setting file-id true)
                               (state-with :off true))))))))

(t/deftest turning-it-off-in-enabled-files
  (with-agent-flag
    (fn []
      (let [state (-> (state-with :off true)
                      (assoc-in [:agent-file-state other-id] false)
                      (assoc :agent-enabled-file-count 2)
                      (assoc-in [:ai-panel file-id :open?] true))
            done  (ptk/update (agent-switch/apply-enabled-files-cleared) state)]
        (t/is (= {file-id :account other-id false} (:agent-file-state done)))
        (t/is (= 0 (:agent-enabled-file-count done)))
        (t/is (false? (get-in done [:ai-panel file-id :open?])))
        (t/is (false? (agent-access/agent-enabled? done file-id)))))))

;; --- No model calls while off

(def ^:private settings {:provider "anthropic" :model "claude-haiku-4-5-20251001"})

(defn- with-history
  [state]
  (assoc-in state [:ai-panel file-id :history]
            [{:role :user :text "hello"}
             {:role :assistant :text "hi"}]))

(t/deftest no-model-calls-from-the-panel-while-off
  (with-agent-flag
    (fn []
      (let [off (with-history (state-with :on false))]
        (t/testing "a message"
          (t/is (empty? (model-calls
                         #(t/is (empty? (emitted (ai-panel/send-message settings "hi" nil)
                                                 off)))))))
        (t/testing "a checkpoint resume"
          (t/is (empty? (model-calls
                         #(emitted (ai-panel/continue-turn)
                                   (assoc-in off [:ai-panel file-id :checkpoint]
                                             {:settings settings :history []}))))))
        (t/testing "a summary into a new chat"
          (t/is (empty? (model-calls
                         #(emitted (ai-panel/summarize-into-new-chat) off)))))
        (t/testing "opening the panel does not start the watcher"
          (t/is (not-any? #(= :app.main.data.workspace.ai-panel/start-watcher (ptk/type %))
                          (emitted (ai-panel/toggle-panel)
                                   (ptk/update (ai-panel/toggle-panel) off)))))))))

(t/deftest model-calls-resume-when-switched-back-on
  (with-agent-flag
    (fn []
      (let [on (with-history (state-with :off true))]
        ;; the summary is a side job: it runs as a helper round
        (t/is (= [:agent-helper-round]
                 (model-calls #(emitted (ai-panel/summarize-into-new-chat) on))))))))

(t/deftest no-skill-generation-while-off
  (with-agent-flag
    (fn []
      (let [errors  (atom [])
            answers {:what "Check copy" :trigger "Check" :reactive "on-call"}
            ev      #(user-skills/create-from-answers
                      settings answers {:on-error (fn [e] (swap! errors conj e))})]
        (t/is (empty? (model-calls #(emitted (ev) (state-with :on false)))))
        (t/is (= [:agent-off] (mapv (comp :code ex-data) @errors)))
        (t/testing "and it calls the model again once on"
          (t/is (= [:ai-agent-round]
                   (model-calls #(emitted (ev) (state-with :off true))))))))))

(t/deftest no-session-review-while-off
  (with-agent-flag
    (fn []
      (let [seen    (atom [])
            session {:id (uuid/next) :events [{:t 0 :type "select"}]}
            ev      #(srev/review-session session (fn [e] (swap! seen conj e)))]
        (t/is (empty? (model-calls #(emitted (ev) (state-with :on false)))))
        (t/is (= [[:error :agent-off]] @seen))
        (t/testing "and it calls the model again once on"
          (t/is (= [:ai-agent-round]
                   (model-calls #(emitted (ev) (state-with :off true))))))))))

(t/deftest no-watcher-and-no-tick-while-off
  (with-agent-flag
    (fn []
      (let [shape-id (uuid/next)
            open-off (-> (state-with :on false)
                         (assoc-in [:ai-panel file-id :open?] true)
                         (assoc-in [:ai-panel file-id :dirty-ids] #{shape-id}))
            open-on  (assoc-in open-off [:agent-file-state file-id] :account)
            skills   (#'ai-panel/model-detect-skills open-on)
            model    (some :model skills)
            connect  #(assoc % :ai-providers
                             {:anthropic {:provider "anthropic"
                                          :enabled-models [model]}})
            tick     #(model-calls
                       (fn []
                         (with-redefs [dsh/lookup-page-objects
                                       (mock/stub (fn [_] {shape-id {:name "Rectangle"
                                                                     :type :rect}}))]
                           (emitted (#'ai-panel/run-semantic-tick) %))))]

        (t/testing "the watcher does not start"
          (t/is (empty? (emitted (#'ai-panel/start-watcher) open-off))))

        (t/testing "edits are not queued for the semantic tick"
          (let [track (#'ai-panel/track-dirty #{(uuid/next)})]
            (t/is (= #{shape-id}
                     (get-in (ptk/update track open-off) [:ai-panel file-id :dirty-ids])))
            (t/is (= 2 (count (get-in (ptk/update track open-on)
                                      [:ai-panel file-id :dirty-ids]))))))

        (t/testing "a panel left open in a file turned off sends no tick"
          (t/is (some? model))
          (t/is (empty? (tick (connect open-off))))
          (t/is (= [:agent-helper-round] (tick (connect open-on)))))))))

(t/deftest switching-off-stops-a-running-turn
  (with-agent-flag
    (fn []
      (let [on        (with-history (state-with :on nil))
            stream    (rx/subject)
            out       (atom [])
            done?     (atom false)
            calls     (atom [])]
        (with-redefs [rp/cmd! (mock/stub (fn [cmd & _]
                                           (swap! calls conj cmd)
                                           ;; a round that never answers
                                           (rx/subject)))]
          (opt-out/with-store-state
            on
            #(rx/sub! (ptk/watch (ai-panel/send-message settings "go on" nil) on stream)
                      (fn [ev] (swap! out conj ev))
                      (constantly nil)
                      (fn [] (reset! done? true))))
          (t/is (= 1 (count @calls)))
          (t/is (false? @done?))
          (rx/push! stream (ptk/data-event agent-access/turned-off))
          (t/is (true? @done?))
          (t/is (= 1 (count @calls)))
          (t/is (some #(= :app.main.data.workspace.ai-panel/set-busy (ptk/type %)) @out)))))))

(t/deftest saving-a-file-setting
  (with-agent-flag
    (fn []
      (let [sent (atom [])
            run  (fn [state response]
                   (with-redefs [rp/cmd! (mock/stub (fn [cmd params]
                                                      (swap! sent conj [cmd params])
                                                      response))]
                     (emitted (agent-switch/set-file-enabled file-id true) state)))]

        (t/testing "an exception is saved and told to the other tabs"
          (reset! sent [])
          (let [events (run (state-with :off nil) (rx/of {}))]
            (t/is (= [[:set-agent-file-state {:file-id file-id :enabled true}]] @sent))
            (t/is (true? (get-in (ptk/update (first events) (state-with :off nil))
                                 [:agent-file-state file-id])))
            (t/is (= 2 (count events)))))

        (t/testing "the account's own value clears the exception"
          (reset! sent [])
          (run (state-with :on false) (rx/of {}))
          (t/is (= [[:set-agent-file-state {:file-id file-id :enabled nil}]] @sent)))

        (t/testing "a failed save puts the previous setting back"
          (let [state  (state-with :off nil)
                events (run state (rx/throw (ex-info "boom" {})))
                after  (reduce (fn [st ev]
                                 (if (implements? ptk/UpdateEvent ev)
                                   (ptk/update ev st)
                                   st))
                               state
                               events)]
            (t/is (= :account (get-in after [:agent-file-state file-id])))
            (t/is (false? (agent-access/agent-enabled? after file-id)))))))))

(t/deftest loading-a-file-setting
  (with-agent-flag
    (fn []
      (let [load (fn [response]
                   (with-redefs [rp/cmd! (mock/stub (fn [_ _] response))]
                     (let [events (emitted (agent-switch/fetch-file-setting file-id)
                                           (state-with :on :unknown))]
                       (reduce #(ptk/update %2 %1) (state-with :on :unknown) events))))]
        (t/is (false? (get-in (load (rx/of {:file-id file-id :enabled false}))
                              [:agent-file-state file-id])))
        (t/is (= :account (get-in (load (rx/of {:file-id file-id :enabled nil}))
                                  [:agent-file-state file-id])))
        (t/testing "a failed load keeps the agent off in the file"
          (let [after (load (rx/throw (ex-info "offline" {})))]
            (t/is (false? (agent-access/agent-enabled? after file-id)))))))))
