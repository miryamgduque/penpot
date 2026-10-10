;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.screenshot-url-test
  "A screenshot is not an export, but it takes a browser from the same bounded
  pool, so it is admitted by the same scheduler. These cover the seam: the
  caller still gets bytes back, the job is a real tracked record, and one
  client cannot hold more browsers than its per-profile cap allows."
  (:require
   [app.common.uuid :as uuid]
   [app.handlers.screenshot-url :as screenshot-url]
   [app.jobs :as jobs]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

(def ^:private png (js/Buffer.from #js [1 2 3 4]))

(defn- gate
  "A promise and the fn that settles it, standing in for a capture in flight."
  []
  (let [resolve* (volatile! nil)
        pending  (p/create (fn [resolve _] (vreset! resolve* resolve)))]
    [pending (fn [] (@resolve* png))]))

(t/deftest the-caller-gets-the-bytes-back
  (t/testing "the job wrapper is transparent: what capture promises is what resolves"
    (t/async done
      (p/let [buffer (screenshot-url/run-tracked! (uuid/next) "example.com"
                                                  (fn [_] (p/resolved png)))]
        (t/is (= png buffer))
        (done)))))

(t/deftest a-screenshot-runs-as-a-real-browser-job
  (t/testing "it is a tracked job while it runs, not an untracked browser lease"
    ;; only the in-flight record is observable: the scheduler releases the
    ;; runtime entry as soon as the job settles, and `fetch` needs the redis
    ;; store the suite has no connection to.
    (t/async done
      (let [profile-id (uuid/next)
            seen       (atom nil)]
        (p/let [_ (screenshot-url/run-tracked! profile-id "example.com"
                                               (fn [job]
                                                 (reset! seen (jobs/lookup (:id job)))
                                                 (p/resolved png)))]
          (let [job @seen]
            (t/is (= :screenshot-url (:cmd job)))
            (t/is (= "browser" (:backend job)))
            (t/is (= "example.com" (:name job)))
            (t/is (= profile-id (:profile-id job)))
            (t/is (= "running" (:state job)))
            ;; nothing is stored, so there is no resource to point at
            (t/is (nil? (:resource-id job))))
          (done))))))

(t/deftest a-failed-capture-rejects-the-caller
  (t/testing "the job wrapper does not swallow the cause"
    (t/async done
      (let [boom (ex-info "navigation failed" {})]
        (->> (screenshot-url/run-tracked! (uuid/next) "example.com"
                                          (fn [_] (p/rejected boom)))
             (p/merr (fn [cause]
                       (t/is (= boom cause))
                       (done)
                       (p/resolved nil))))))))

(t/deftest one-profile-cannot-hold-more-browsers-than-its-cap
  (t/testing "the third concurrent screenshot from one profile stays queued"
    (t/async done
      (let [profile-id      (uuid/next)
            [first* open1]  (gate)
            [second* open2] (gate)
            started         (atom [])
            shoot           (fn [tag pending]
                              (screenshot-url/run-tracked!
                               profile-id "example.com"
                               (fn [_] (swap! started conj tag) pending)))
            p1              (shoot :one first*)
            p2              (shoot :two second*)
            p3              (shoot :three (p/resolved png))]
        (p/do
          (p/delay 20)
          ;; max-jobs-per-profile defaults to 2
          (t/is (= [:one :two] @started))
          (open1)
          (open2)
          (p/all [p1 p2 p3])
          (t/is (= [:one :two :three] @started))
          (done))))))
