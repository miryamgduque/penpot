;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.routes-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.team :as dtm]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.routes :as routes]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.agent-opt-out :as opt-out]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- workspace-match
  [team-id]
  {:data {:name :workspace}
   :params {:path {}}
   :query-params {:team-id (str team-id)}})

(t/deftest sso-check-is-cached-for-five-minutes
  (t/async done
    (let [team-id   (uuid/next)
          match     (workspace-match team-id)
          now       (atom (ct/inst "2026-08-11T10:00:00Z"))
          rpc-calls (atom 0)
          events    (atom [])]
      (mock/with-mocks
        {cf/flags (conj cf/flags :admin-console)
         ct/now   (mock/stub (fn [] @now))
         rp/cmd!  (mock/stub
                   (fn [command params]
                     (t/is (= :check-nitrate-sso command))
                     (t/is (= team-id (:team-id params)))
                     (swap! rpc-calls inc)
                     (rx/of {:authorized true})))
         st/emit! (mock/stub
                   (fn [& emitted]
                     (swap! events into emitted)))}
        (fn [done']
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (reset! now (ct/plus @now #js {:minutes 4 :seconds 59}))
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (t/is (= 1 @rpc-calls))
          (t/is (= 2 (count @events)))
          (done'))
        done))))

(t/deftest sso-check-is-refreshed-after-five-minutes
  (t/async done
    (let [team-id   (uuid/next)
          match     (workspace-match team-id)
          now       (atom (ct/inst "2026-08-11T10:00:00Z"))
          rpc-calls (atom 0)]
      (mock/with-mocks
        {cf/flags (conj cf/flags :admin-console)
         ct/now   (mock/stub (fn [] @now))
         rp/cmd!  (mock/stub
                   (fn [_ _]
                     (swap! rpc-calls inc)
                     (rx/of {:authorized true})))
         st/emit! mock/noop}
        (fn [done']
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (reset! now (ct/plus @now #js {:minutes 5}))
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (t/is (= 2 @rpc-calls))
          (done'))
        done))))

(t/deftest sso-redirect-result-is-not-cached
  (t/async done
    (let [team-id   (uuid/next)
          match     (workspace-match team-id)
          rpc-calls (atom 0)
          events    (atom [])]
      (mock/with-mocks
        {cf/flags (conj cf/flags :admin-console)
         rp/cmd!  (mock/stub
                   (fn [_ _]
                     (swap! rpc-calls inc)
                     (rx/of {:authorized false
                             :redirect-uri "https://idp.example.com/authorize"})))
         st/emit! (mock/stub
                   (fn [& emitted]
                     (swap! events into emitted)))}
        (fn [done']
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (#'routes/check-sso-and-navigate match true "https://penpot.example.com/#/workspace")
          (t/is (= 2 @rpc-calls))
          (t/is (= 2 (count @events)))
          (done'))
        done))))

;; Template links (`#?template=<url>`) predate query-string routing: the
;; params live in a bare `#?…` fragment and there is no `screen` (#12127).

(def ^:private template-url
  "https://penpot.github.io/penpot-files/tutorial-for-beginners%20v.2.0.penpot")

(def ^:private template-param
  "The query param as the router decodes it."
  "https://penpot.github.io/penpot-files/tutorial-for-beginners v.2.0.penpot")

(t/deftest legacy-query-hash-becomes-a-query-token
  (t/is (= (str "?template=" template-url)
           (#'routes/legacy-query-token (str "#?template=" template-url))))
  (t/is (nil? (#'routes/legacy-query-token "#/workspace?team-id=1")))
  (t/is (nil? (#'routes/legacy-query-token ""))))

(t/deftest query-without-screen-goes-to-dashboard-with-its-params
  (t/async done
    (let [team-id (uuid/next)
          events  (atom [])]
      (mock/with-mocks
        {rp/cmd!               (mock/stub
                                (fn [command _]
                                  (case command
                                    :get-profile (rx/of {:id (uuid/next) :default-team-id team-id})
                                    :get-teams   (rx/of [{:id team-id}]))))
         dtm/get-last-team-id  (mock/stub (fn [] nil))
         st/emit!              (mock/stub (fn [& emitted] (swap! events into emitted)))}
        (fn [done']
          (#'routes/on-query-navigate #{:dashboard-recent} (str "?template=" template-url) false)
          (let [{:keys [id params]} (some-> (first @events) deref)]
            (t/is (= :dashboard-recent id))
            (t/is (= template-param (:template params)))
            (t/is (= team-id (:team-id params))))
          (done'))
        done))))

(t/deftest query-without-screen-keeps-template-for-after-login
  (t/async done
    (let [events (atom [])]
      (mock/with-mocks
        {rp/cmd!  (mock/stub
                   (fn [command _]
                     (case command
                       :get-profile (rx/of {:id uuid/zero})
                       ;; The backend requires a session for get-teams.
                       :get-teams   (rx/throw (ex-info "authentication required"
                                                       {:type :authentication})))))
         st/emit! (mock/stub (fn [& emitted] (swap! events into emitted)))}
        (fn [done']
          (#'routes/on-query-navigate #{:auth-login} (str "?template=" template-url) false)
          (t/is (= template-param (:template @storage/session)))
          (t/is (= :auth-login (:id (some-> (first @events) deref))))
          (binding [storage/*sync* true]
            (swap! storage/session dissoc :template))
          (done'))
        done))))

;; --- The skills pages while the agent is off (#86, #88)

(def ^:private agent-router
  #{:dashboard-skills :dashboard-agent-skills :dashboard-recent})

(defn- navigate-with
  "Open `token` as a URL with the account switch `account` (:on or :off)
  and the agent flag on. Return the navigations it emitted as [screen params options]."
  [account token]
  (let [navs    (atom [])
        default (uuid/next)]
    (with-redefs [cf/flags (conj cf/flags :design-agent)
                  st/emit! (mock/stub
                            (fn [& events]
                              (doseq [e events]
                                (when (= ::rt/navigate (ptk/type e))
                                  (swap! navs conj (let [{:keys [id params options]} @e]
                                                     [id params options]))))))]
      (opt-out/with-store-state
        {:profile {:id (uuid/next)
                   :default-team-id default
                   :props {:agent-disabled (= :off account)}}}
        #(#'routes/on-query-navigate agent-router token false)))
    {:navs @navs :default default}))

(t/deftest skills-pages-by-url-redirect-while-the-agent-is-off
  (let [team-id (uuid/next)]
    (doseq [screen ["dashboard-skills" "dashboard-agent-skills"]]
      (t/testing screen
        (let [{:keys [navs]} (navigate-with :off (str "?screen=" screen "&team-id=" team-id))]
          (t/is (= [[:dashboard-recent {:team-id team-id} {::rt/replace true}]] navs)))
        (t/testing "without a team in the URL it opens the default team"
          (let [{:keys [navs default]} (navigate-with :off (str "?screen=" screen))]
            (t/is (= [[:dashboard-recent {:team-id default} {::rt/replace true}]] navs))))))))

(t/deftest skills-pages-by-url-open-while-the-agent-is-on
  (doseq [screen ["dashboard-skills" "dashboard-agent-skills"]]
    (t/is (empty? (:navs (navigate-with :on (str "?screen=" screen "&team-id=" (uuid/next)))))
          screen))
  (t/testing "other pages are never redirected"
    (t/is (empty? (:navs (navigate-with :off (str "?screen=dashboard-recent&team-id=" (uuid/next))))))))
