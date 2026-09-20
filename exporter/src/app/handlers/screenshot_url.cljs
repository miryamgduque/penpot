;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.handlers.screenshot-url
  "Screenshots an EXTERNAL page on the pooled browser, for the design agent's
  screenshot_page tool.

  Everything about this handler assumes the URL is attacker-controlled:

  - the caller's auth token must validate against the backend first (the
    export handlers get that implicitly by fetching resources with the token;
    here nothing else would check it and an unauthenticated screenshot
    endpoint is an open proxy);
  - the target host must resolve public (app.util.netguard) BEFORE navigation,
    and every request the page makes — redirects included — re-passes the same
    guard through route interception;
  - the browser context gets NO cookies: reusing the Penpot session cookie
    against an external site would hand the session to whoever controls it.

  Known residual risk (documented, accepted for the prototype): DNS rebinding
  between our lookup and Chromium's own resolution of the same hostname."
  (:require
   [app.auth :as auth]
   [app.browser :as bwr]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.jobs :as jobs]
   [app.jobs.scheduler :as scheduler]
   [app.util.netguard :as ng]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(s/def ::url ::us/string)
(s/def ::full-page ::us/boolean)

(s/def ::params
  (s/keys :req-un [::url]
          :opt-un [::full-page]))

(def ^:private viewport-width 1280)
(def ^:private viewport-height 800)
(def ^:private max-page-height 2400)
(def ^:private nav-timeout 15000)

(defn- guard-context-routes!
  "Re-checks EVERY request the page issues (subresources, redirect hops)
  against the netguard; anything private or non-http(s) is aborted. This is
  what stops a public page from bouncing the browser into the internal
  network after the top-level check passed."
  [context]
  (.route ^js context "**/*"
          (fn [route request]
            (let [{:keys [scheme host]} (u/uri (.url ^js request))]
              (if-not (contains? #{"http" "https"} scheme)
                (.abort ^js route "blockedbyclient")
                (-> (ng/assert-public-host! host)
                    (p/then (fn [_] (.continue ^js route)))
                    (p/catch (fn [_] (.abort ^js route "blockedbyclient")))))))))

(defn- navigate!
  "Navigates best-effort: `networkidle` is the quality bar, but pages that
  poll forever never reach it — on timeout we screenshot what rendered.
  A navigation aborted by the route guard surfaces as `:blocked-host`."
  [page url]
  (-> (bwr/nav! page url {:wait-until "networkidle" :timeout nav-timeout})
      (p/catch
       (fn [cause]
         (let [msg (or (ex-message cause) "")]
           (cond
             (str/includes? msg "ERR_BLOCKED_BY_CLIENT")
             (p/rejected (ex/error :type :validation
                                   :code :blocked-host
                                   :hint "the page redirected to a private or blocked host"))

             (str/includes? msg "Timeout")
             (p/resolved nil)

             :else
             (p/rejected (ex/error :type :validation
                                   :code :unable-to-load-page
                                   :hint (str "the page could not be loaded: " msg)))))))))

(defn- shoot!
  "Viewport screenshot by default; `full-page?` captures the page surface
  clipped at `max-page-height` so a pathological page cannot balloon the
  response."
  [page full-page?]
  (if-not full-page?
    (bwr/screenshot page {:full-page? false})
    (p/let [height (bwr/eval! page "() => document.documentElement.scrollHeight")]
      (.screenshot ^js page
                   #js {:type "png"
                        :clip #js {:x 0 :y 0
                                   :width viewport-width
                                   :height (-> (or height viewport-height)
                                               (max viewport-height)
                                               (min max-page-height))}}))))

(defn- capture!
  [url full-page]
  (bwr/exec! #js {:viewport #js {:width viewport-width
                                 :height viewport-height}
                  :deviceScaleFactor 1
                  :userAgent bwr/default-user-agent}
             (fn [page]
               (p/let [context (.context ^js page)
                       _       (guard-context-routes! context)
                       _       (navigate! page url)
                       shot    (shoot! page (boolean full-page))]
                 shot))))

(defn run-tracked!
  "Runs `capture` (a fn of the job, promising the PNG bytes) as a real job, so
  the screenshot answers to the same admission control as an export. Takes the
  fn rather than calling `capture!` itself so a test can exercise the admission
  path without a browser; it is handed the job so a capture can reach
  `jobs/cancel-signal`. Public for tests.

  A screenshot is not an export — it has no resource to hand back, so it
  cannot use the export pipeline's prepare/resource machinery and answers
  with the PNG inline instead. But it does take a browser from the same
  bounded pool, and without a job it took one invisibly: an agent looping
  `screenshot_page` could hold every browser while real exports waited on
  `.acquire`, with nothing in the job API to show why. Going through the
  scheduler gets the per-profile cap, the queue bound (`:queue-full` is
  already a 429 upstream in `on-error`) and a cancellable, inspectable
  record, while the caller still gets bytes."
  [profile-id name capture]
  (->> (jobs/create! {:profile-id profile-id
                      :cmd :screenshot-url
                      :backend "browser"
                      :total 1
                      :name name
                      :resource-id nil}
                     (fn [job]
                       (->> (capture job)
                            (p/fmap (fn [buffer]
                                      ;; no uri/filename: nothing was stored,
                                      ;; the bytes went straight to the caller
                                      (jobs/complete! job {:mtype "image/png"
                                                           :size (.-length ^js buffer)})
                                      buffer))
                            (p/merr (fn [cause]
                                      (->> (jobs/fail! job cause)
                                           (p/mcat (fn [_] (p/rejected cause)))))))))
       (p/mcat scheduler/submit!)))

(defn handler
  [{:keys [:request/auth-token] :as exchange} {:keys [url full-page]}]
  (let [{:keys [scheme host]} (u/uri url)]
    (when-not (contains? #{"http" "https"} scheme)
      (ex/raise :type :validation
                :code :invalid-url
                :hint "url must be absolute http(s)"))
    (->> (auth/require-profile-id auth-token)
         (p/mcat (fn [profile-id]
                   (p/do! (ng/assert-public-host! host)
                          (run-tracked! profile-id host
                                        (fn [_job] (capture! url full-page))))))
         (p/fmap (fn [buffer]
                   (-> exchange
                       (assoc :response/status 200)
                       (assoc :response/body buffer)
                       (assoc :response/headers {"content-type" "image/png"})))))))
