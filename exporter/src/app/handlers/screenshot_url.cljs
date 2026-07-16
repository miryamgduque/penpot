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
   ["undici" :as uhttp]
   [app.browser :as bwr]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.config :as cf]
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

(defn- assert-authenticated!
  "Validates the auth token against the backend (`get-teams` answers 200 only
  for an authenticated profile). Promise of nil; raises `:unauthorized`."
  [auth-token]
  (if (str/blank? auth-token)
    (p/rejected (ex/error :type :validation
                          :code :unauthorized
                          :hint "authentication required"))
    (let [uri (-> (cf/get-internal-uri)
                  (u/ensure-path-slash)
                  (u/join "api/rpc/command/get-teams")
                  (str))]
      (->> (uhttp/fetch uri #js {:headers #js {"cookie" (str "auth-token=" auth-token)}})
           (p/mcat (fn [response]
                     (if (= 200 (.-status ^js response))
                       (p/resolved nil)
                       (p/rejected (ex/error :type :validation
                                             :code :unauthorized
                                             :hint "authentication required")))))))))

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

(defn handler
  [{:keys [:request/auth-token] :as exchange} {:keys [url full-page]}]
  (let [{:keys [scheme host]} (u/uri url)]
    (when-not (contains? #{"http" "https"} scheme)
      (ex/raise :type :validation
                :code :invalid-url
                :hint "url must be absolute http(s)"))
    (->> (p/do!
          (assert-authenticated! auth-token)
          (ng/assert-public-host! host)
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
         (p/fmap (fn [buffer]
                   (-> exchange
                       (assoc :response/status 200)
                       (assoc :response/body buffer)
                       (assoc :response/headers {"content-type" "image/png"})))))))
