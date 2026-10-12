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
   [app.browser :as bw]
   [app.common.agent.document :as cad]
   [app.common.agent.tools :as cat]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.handlers.screenshot-url :as screenshot]
   [app.wasm.backend :as backend]
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

;; --- generate_code
;;
;; The handoff code generators render the workspace's own components, so they
;; run in the frontend's render page, in a browser of the pool: this side
;; reads the page with the turn token, decides which shapes are meant, and
;; hands them to that page. The page fetches nothing and gets no cookie.

;; A tool result is capped like the workspace caps it: an oversized result is
;; replaced by a small, valid object saying so, never cut mid-token.
(def ^:private max-result-chars 20000)

(defn- capped
  [content]
  (if (> (count content) max-result-chars)
    (js/JSON.stringify
     #js {:truncated true
          :chars (count content)
          :limit max-result-chars
          :error (str "This result was " (count content) " characters, over the "
                      max-result-chars " limit, so none of it was returned "
                      "— a partial result would be indistinguishable from a "
                      "complete one. Narrow the request and try again: "
                      "read_design with a smaller depth, or find_shapes with a "
                      "name/type query.")})
    content))

(defn- generate-in-render-page
  "→ promise of `{:markup :style}` for the shapes of `plan`."
  [objects {:keys [type roots shapes]} team-fonts]
  (let [uri     (-> (cf/get-internal-uri)
                    (u/ensure-path-slash)
                    (u/join "render.html")
                    (assoc :query (u/map->query-string {:route "codegen"})))
        payload (t/encode-str {:objects objects
                               :root-ids (mapv :id roots)
                               :shape-ids (mapv :id shapes)
                               :type type
                               :team-fonts team-fonts})]
    (bw/exec! #js {:viewport #js {:width bw/default-viewport-width
                                  :height bw/default-viewport-height}
                   :userAgent bw/default-user-agent}
              (fn [page]
                (p/do
                  (bw/nav! page (str uri))
                  ;; a function, not a string: only a function is called
                  ;; with the argument. Built from source so the import is
                  ;; the page's, not something this build tries to resolve.
                  (->> (.evaluate ^js page
                                  (js/Function. "payload"
                                                "return import('./js/render.js').then((m) => m.generateCode(payload));")
                                  payload)
                       (p/fmap (fn [json]
                                 (js->clj (js/JSON.parse json) :keywordize-keys true)))))))))

(defn- generate-code
  [token input context]
  (let [input (js->clj (js/JSON.parse input) :keywordize-keys true)
        ctx   (js->clj (js/JSON.parse context) :keywordize-keys true)]
    (->> (backend/command! "get-agent-tool-page" {:token token :context context})
         (p/mcat
          (fn [{:keys [data page-id team-fonts]}]
            (let [objects  (get-in data [:pages-index page-id :objects])
                  selected (into [] (keep #(when (string? %) (parse-uuid %))) (:selectedIds ctx))
                  plan     (cad/code-plan objects selected input)]
              (if-let [problem (:problem plan)]
                (p/resolved {:content problem :isError true})
                (->> (generate-in-render-page objects plan team-fonts)
                     (p/fmap (fn [{:keys [markup style]}]
                               {:content (capped (js/JSON.stringify
                                                  (clj->js (cad/code-result markup style (:shapes plan)))))
                                :isError false})))))))
         ;; the browser could not do it: a workspace still can
         (p/merr (fn [cause]
                   (if (= :agent-backend-refused (:code (ex-data cause)))
                     (p/rejected cause)
                     (do
                       (l/warn :hint "agent tool: code generation failed in the render page"
                               :detail (ex-message cause))
                       (p/resolved {:fallback "the code generator could not run on the server"}))))))))

(def tools
  "Every tool this service runs: the ones that change a page, the render of
  a board for a turn with no workspace, handoff code, and the screenshot of
  a web page."
  (conj (set (keys cat/tools)) "render_board" "generate_code" "screenshot_page"))


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

           (= "generate_code" tool)
           (generate-code token
                          (if (string? input) input "{}")
                          (if (string? context) context "{}"))

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
