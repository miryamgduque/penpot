;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.changes-provenance-test
  "Pins commit provenance (phase 02 of the design-session-recording plan): a
  commit must say WHO made it, for local and remote changes alike.

  This is the phase the whole recording feature rests on. If attribution is
  wrong, a session critique blames the wrong person — worse than having no
  recording at all. So these tests pin the two ends:

  - a LOCAL commit is stamped with this browser's profile and session;
  - a REMOTE commit carries the ORIGINATING profile and session, which
    `handle-file-change` used to discard even though its own schema requires
    them.

  Note what is deliberately NOT tested: \"is this commit our own echo?\". The
  backend's `:subscribe-file` handler filters messages from the subscriber's own
  session (`backend/src/app/http/websocket.clj:153-155`), so a self-echo cannot
  reach the client. `session-id` is per browser TAB, though, so the same person
  in two tabs is two sessions — attribution is the (profile-id, session-id)
  pair, never profile alone."
  (:require
   [app.main.data.changes :as dch]
   [app.main.data.workspace.notifications :as dwn]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private file-id (uuid "00000000-0000-0000-0000-0000000000f1"))
(def ^:private page-id (uuid "00000000-0000-0000-0000-0000000000aa"))
(def ^:private shape-id (uuid "00000000-0000-0000-0000-000000000001"))

(def ^:private local-profile-id (uuid "00000000-0000-0000-0000-00000000c001"))
(def ^:private local-session-id (uuid "00000000-0000-0000-0000-00000000c002"))
(def ^:private other-profile-id (uuid "00000000-0000-0000-0000-00000000d001"))
(def ^:private other-session-id (uuid "00000000-0000-0000-0000-00000000d002"))

(def ^:private a-change
  {:type :mod-obj
   :id shape-id
   :page-id page-id
   :operations [{:type :set :attr :x :val 10}]})

(def ^:private state
  {:current-file-id file-id
   :current-page-id page-id
   :profile-id local-profile-id
   :session-id local-session-id
   :permissions {:can-edit true}
   :features #{}
   :files {file-id {:id file-id :revn 3 :vern 0 :data {:pages-index {}}}}})

;; --- the commit map itself

(t/deftest commit-carries-explicit-attribution
  (t/testing "commit passes provenance through into the deref-able commit map"
    (let [commit (deref (dch/commit {:file-id file-id
                                     :redo-changes [a-change]
                                     :undo-changes []
                                     :profile-id other-profile-id
                                     :session-id other-session-id}))]
      (t/is (= other-profile-id (:profile-id commit)))
      (t/is (= other-session-id (:session-id commit))))))

(t/deftest commit-keeps-attribution-keys-present-when-unknown
  (t/testing "keys exist even with nothing to put in them, so consumers need no
              nil-punning to tell \"unattributed\" from \"key absent\""
    (let [commit (deref (dch/commit {:file-id file-id
                                     :redo-changes [a-change]
                                     :undo-changes []}))]
      (t/is (contains? commit :profile-id))
      (t/is (contains? commit :session-id))
      (t/is (nil? (:profile-id commit))))))

(t/deftest commit-map-keeps-its-pre-existing-shape
  (t/testing "widening the commit map must stay purely additive — four existing
              subscribers read it (persistence x2, workspace undo + text)"
    (let [commit (deref (dch/commit {:file-id file-id
                                     :redo-changes [a-change]
                                     :undo-changes []
                                     :file-revn 3
                                     :undo-group nil
                                     :tags #{:some-tag}}))]
      (t/are [k] (contains? commit k)
        :id :created-at :source :origin :features :file-id :file-revn
        :file-vern :changes :redo-changes :undo-changes :save-undo?
        :undo-group :tags :stack-undo? :ignore-wasm? :selected-before)
      (t/is (= :local (:source commit)) "source still defaults to :local")
      (t/is (= #{:some-tag} (:tags commit))))))

;; --- local commits get stamped from state

(t/deftest commit-changes-stamps-the-local-actor
  (t/async
    done
    (let [event  (dch/commit-changes {:redo-changes [a-change] :undo-changes []})
          result (ptk/watch event state (rx/empty))]
      (->> result
           (rx/subs!
            (fn [evt]
              (let [commit (deref evt)]
                (t/is (= local-profile-id (:profile-id commit))
                      "the local profile is stamped from app state")
                (t/is (= local-session-id (:session-id commit))
                      "the local session (per browser tab) is stamped too")
                (t/is (= :local (:source commit)))))
            (fn [err]
              (done)
              (js/console.error err)
              (t/do-report {:type :error :message "Stream error" :actual err}))
            (fn [_] (done)))))))

;; --- remote commits carry the originating actor

(t/deftest handle-file-change-forwards-remote-attribution
  (t/testing "the inbound schema has always REQUIRED profile-id/session-id;
              handle-file-change used to destructure neither, so a
              collaborator's change reached the stream anonymous"
    (t/async
      done
      (let [msg    {:type :file-change
                    :profile-id other-profile-id
                    :session-id other-session-id
                    :file-id file-id
                    :revn 4
                    :vern 0
                    :changes [a-change]}
            event  (dwn/handle-file-change msg)
            result (ptk/watch event state (rx/empty))]
        (->> result
             (rx/subs!
              (fn [evt]
                (let [commit (deref evt)]
                  (t/is (= other-profile-id (:profile-id commit))
                        "the ORIGINATING profile, not the local one")
                  (t/is (= other-session-id (:session-id commit))
                        "the ORIGINATING session, not the local one")
                  (t/is (= :remote (:source commit)))
                  (t/is (false? (:save-undo? commit))
                        "a remote change must not enter our undo stack")))
              (fn [err]
                (done)
                (js/console.error err)
                (t/do-report {:type :error :message "Stream error" :actual err}))
              (fn [_] (done))))))))

(t/deftest handle-file-change-attribution-is-distinguishable-from-local
  (t/testing "the point of the phase: a remote commit is not confusable with ours"
    (t/async
      done
      (let [msg    {:type :file-change
                    :profile-id other-profile-id
                    :session-id other-session-id
                    :file-id file-id
                    :revn 4
                    :vern 0
                    :changes [a-change]}
            result (ptk/watch (dwn/handle-file-change msg) state (rx/empty))]
        (->> result
             (rx/subs!
              (fn [evt]
                (let [commit (deref evt)]
                  (t/is (not= local-profile-id (:profile-id commit)))
                  (t/is (not= local-session-id (:session-id commit)))))
              (fn [err]
                (done)
                (js/console.error err)
                (t/do-report {:type :error :message "Stream error" :actual err}))
              (fn [_] (done))))))))
