;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.agent-web
  "Server-side web fetch for the embedded design agent.

  One primitive: `fetch-web-page` downloads an external page and returns
  readable text plus brand-relevant metadata (title, description, og:image,
  favicon, theme-color). The agent-side tool decides what to do with it —
  today a side-turn digest (the raw text never enters the main conversation)
  and brand extraction.

  Security stance: the URL is attacker-controlled by construction, so the
  fetch rides `app.http.client` with its SSRF validation ON (checked on the
  initial request and on every redirect hop — contrast `ai-providers`, whose
  endpoints are operator-known and skip it). Response size is capped before
  parsing and the extracted text capped again before returning, so an
  adversarial page cannot balloon an RPC response or a prompt."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.http.client :as http]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [cuerdas.core :as str])
  (:import
   java.io.ByteArrayOutputStream
   java.io.InputStream
   org.jsoup.Jsoup
   org.jsoup.nodes.Document))

(set! *warn-on-reflection* true)

(def ^:private max-body-bytes
  "Read cap for the raw response body; enough for any real page's HTML."
  (* 2 1024 1024))

(def ^:private max-text-chars
  "Cap for the extracted readable text returned to the client."
  100000)

(def ^:private allowed-content-types
  #{"text/html" "application/xhtml+xml" "text/plain"})

;; --- Extraction (pure; public for tests)

(defn charset-of
  "The charset named by a content-type header, defaulting to UTF-8."
  [content-type]
  (or (some-> (re-find #"(?i)charset=\"?([^;\s\"]+)" (or content-type ""))
              (second))
      "UTF-8"))

(defn capped-text
  "Caps text at `max-text-chars`. Returns [text truncated?]."
  [text]
  (if (> (count text) max-text-chars)
    [(subs text 0 max-text-chars) true]
    [text false]))

(defn- non-blank
  [s]
  (when-not (str/blank? s) s))

(defn extract-page
  "Parses HTML into {:title :text :meta}. Text is script/style-free and
  whitespace-collapsed (jsoup's `.text`); favicon/og:image come back as
  absolute URLs (resolved against `base-uri`), with /favicon.ico as the
  favicon fallback."
  [html base-uri]
  (let [^Document doc (Jsoup/parse ^String html ^String base-uri)

        content (fn [css]
                  (some-> (.selectFirst doc ^String css)
                          (.attr "content")
                          (non-blank)))
        abs-url (fn [css attr]
                  (some-> (.selectFirst doc ^String css)
                          (.absUrl ^String attr)
                          (non-blank)))

        fallback-favicon
        (try
          (str (.resolve (java.net.URI. ^String base-uri) "/favicon.ico"))
          (catch Exception _ nil))]

    (-> (.select doc "script, style, noscript, svg, template")
        (.remove))

    {:title (non-blank (.title doc))
     :text  (or (some-> (.body doc) (.text)) "")
     :meta  {:og-title (content "meta[property=og:title]")
             :og-description (or (content "meta[property=og:description]")
                                 (content "meta[name=description]"))
             :og-image (abs-url "meta[property=og:image]" "content")
             :favicon (or (abs-url "link[rel~=(?i)icon]" "href")
                          fallback-favicon)
             :theme-color (content "meta[name=theme-color]")}}))

;; --- Fetch

(defn- read-capped
  "Reads at most `limit` bytes from `input`. Returns [bytes truncated?]."
  [^InputStream input limit]
  (let [out (ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (loop []
      (let [n (.read input buf)]
        (if (neg? n)
          [(.toByteArray out) false]
          (do
            (.write out buf 0 (int (min n (- ^long limit (.size out)))))
            (if (>= (.size out) ^long limit)
              [(.toByteArray out) (nat-int? (.read input))]
              (recur))))))))

(defn- base-content-type
  [headers]
  (some-> (get headers "content-type")
          (str/split ";")
          (first)
          (str/trim)
          (str/lower)))

(defn- fetch-response!
  "GETs the url (SSRF validation ON, per hop) and returns the raw response.
  Network-level failures become one stable error code; `ex/raise`d errors
  (notably `:ssrf-blocked-target`) pass through untouched."
  [cfg url]
  (try
    (http/req-with-redirects
     cfg
     {:method :get
      :uri (str url)
      :headers {"user-agent" "Mozilla/5.0 (compatible; PenpotAgent/1.0; +https://penpot.app)"
                "accept" "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.1"}
      :timeout (ct/duration "10s")}
     {:response-type :input-stream
      :max-redirects 4})
    (catch java.io.IOException cause
      (ex/raise :type :validation
                :code :unable-to-fetch-page
                :hint "the page could not be fetched (unreachable host or timeout)"
                :cause cause))))

(defn- fetch-web-page
  [cfg url]
  (let [{:keys [status headers ^InputStream body]} (fetch-response! cfg url)
        ctype (base-content-type headers)]

    (when-not (<= 200 status 299)
      (ex/raise :type :validation
                :code :unable-to-fetch-page
                :hint (str "the page answered status " status)))

    (when-not (contains? allowed-content-types ctype)
      (ex/raise :type :validation
                :code :content-type-not-allowed
                :hint (str "the url returned " (or ctype "an unknown type")
                           " — only html and plain text pages can be fetched")))

    (let [[bytes byte-trunc?]
          (with-open [input body]
            (read-capped input max-body-bytes))

          raw  (String. ^bytes bytes ^String (charset-of (get headers "content-type")))
          page (if (= ctype "text/plain")
                 {:title nil :text raw :meta {}}
                 (extract-page raw (str url)))

          [text char-trunc?] (capped-text (:text page))]

      {:title (:title page)
       :text text
       :truncated (boolean (or byte-trunc? char-trunc?))
       :meta (:meta page)})))

;; --- Command

(def ^:private schema:fetch-web-page
  [:map {:title "fetch-web-page"}
   [:url ::sm/uri]])

(sv/defmethod ::fetch-web-page
  {::doc/added "2.13"
   ::sm/params schema:fetch-web-page}
  [cfg {:keys [url]}]
  (fetch-web-page cfg url))
