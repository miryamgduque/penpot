;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.handlers.agent-tool
  "`POST /agent-tool`: runs one design agent tool that changes a page.

  Called by the agent service, not by a browser, and carries no session. What
  it is allowed to do is decided by the turn token in the request: the worker
  hands it to the backend, which answers only for the profile and file the
  token names."
  (:require
   ["undici" :as http]
   [app.common.agent.tools :as cat]
   [app.common.exceptions :as ex]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.handlers.screenshot-url :as screenshot]
   [app.wasm.pool :as pool]
   [promesa.core :as p]))

;; --- screenshot_page
;;
;; Not a page change: it shows the model a web page, through the browser pool
;; this process already keeps. The turn token says for whom.

(def ^:private http-url-re #"(?i)^https?://\S+$")

;; Budget for one screenshot in a tool result: a viewport shot is 100-500k of
;; base64; a photographic full-page capture is what trips this.
(def ^:private max-screenshot-b64-chars 1600000)

(def ^:private screenshot-error-hints
  {:blocked-host "the host is private or blocked by this Penpot instance"
   :invalid-url "the url must be absolute http(s)"
   :unable-to-load-page "the page could not be loaded"
   :timeout "the screenshot service is busy — try again shortly"
   :browser-not-ready "the screenshot service is busy — try again shortly"
   ;; a screenshot is admitted by the same scheduler as an export, so a busy
   ;; exporter turns it away rather than queueing it behind every export
   :queue-full "the export service is saturated — try again shortly"})

(defn- turn-profile
  "Asks the backend whose turn the token is → promise of the profile id."
  [token]
  (let [uri (-> (cf/get-internal-uri)
                (u/ensure-path-slash)
                (u/join "api/rpc/command/run-agent-tool")
                (str))]
    (->> (p/do (http/fetch uri #js {:method "POST"
                                    :headers #js {"content-type" "application/transit+json"
                                                  "accept" "application/transit+json"}
                                    :body (t/encode-str {:token token :tool "turn_profile"})}))
         (p/mcat (fn [^js response]
                   (if (= 200 (.-status response))
                     (.text response)
                     (ex/raise :type :validation
                               :code :invalid-turn-token
                               :hint "the agent turn token is not valid"))))
         (p/fmap (fn [body]
                   (-> (t/decode-str body) :content js/JSON.parse (unchecked-get "profileId")))))))

(defn- screenshot-page
  [token input]
  (let [{:keys [url fullPage]} (js->clj (js/JSON.parse input) :keywordize-keys true)
        refuse (fn [message] {:content message :isError true})]
    (if-not (and (string? url) (re-matches http-url-re url))
      (p/resolved (refuse (str "screenshot_page: url must be an absolute http(s) "
                               "URL, e.g. https://example.com")))
      (->> (turn-profile token)
           (p/mcat (fn [profile-id] (screenshot/screenshot! profile-id url (boolean fullPage))))
           (p/fmap (fn [^js buffer]
                     (let [b64 (.toString buffer "base64")]
                       (if (> (count b64) max-screenshot-b64-chars)
                         (refuse (str "screenshot_page: the capture is too large to attach — retry "
                                      "without fullPage: true, or screenshot a more specific url"))
                         {:content (js/JSON.stringify
                                    #js {:url url
                                         :fullPage (boolean fullPage)
                                         :note (str "screenshot attached — visible this round only; "
                                                    "call again to look again")})
                          :isError false
                          :images [{:mediaType "image/png" :data b64}]}))))
           (p/merr (fn [cause]
                     (let [code (:code (ex-data cause))]
                       (if (= :invalid-turn-token code)
                         (p/rejected cause)
                         (p/resolved
                          (refuse (str "screenshot_page: "
                                       (or (get screenshot-error-hints code)
                                           (str "the capture failed" (when code (str " (" (name code) ")"))))
                                       ".")))))))))))

(def tools
  "Every tool this service runs: the ones that change a page, the render of
  a board for a turn with no workspace, and the screenshot of a web page."
  (conj (set (keys cat/tools)) "render_board" "screenshot_page"))


(defn handler
  [{:keys [:request/params] :as exchange}]
  (let [{:keys [token tool input context]} params]
    (when-not (and (string? token) (contains? tools tool))
      (ex/raise :type :validation
                :code :invalid-agent-tool-request
                :hint "expected a turn token and a tool this service runs"))
    (->> (cond
           (= "screenshot_page" tool)
           (screenshot-page token (if (string? input) input "{}"))

           (zero? (pool/capacity))
           ;; no engine here: the caller runs the tool where it used to
           (p/resolved {:fallback "the render engine is not enabled on this exporter"})

           :else
           (pool/run-tool {:token token
                           :tool tool
                           :input (if (string? input) input "{}")
                           :context (if (string? context) context "{}")}))
         (p/fmap (fn [result]
                   (-> exchange
                       (assoc :response/status 200)
                       (assoc :response/headers {"content-type" "application/json"})
                       (assoc :response/body (js/JSON.stringify (clj->js result)))))))))
