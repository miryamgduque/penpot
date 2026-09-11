;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.agent-web-test
  "Pure extraction tests for the agent's web-fetch primitive. The network path
  (SSRF, redirects, caps) rides `app.http.client`, which is exercised
  elsewhere; what this namespace guards is that an adversarial or sloppy page
  still yields clean text and correctly-resolved metadata."
  (:require
   [app.rpc.commands.agent-web :as aweb]
   [clojure.string :as str]
   [clojure.test :as t]))

(def ^:private page
  "<html><head>
     <title>Acme — pricing</title>
     <meta property=\"og:title\" content=\"Acme\">
     <meta property=\"og:description\" content=\"Rockets etc.\">
     <meta property=\"og:image\" content=\"/img/social.png\">
     <meta name=\"theme-color\" content=\"#6366f1\">
     <link rel=\"icon\" href=\"assets/fav.svg\">
     <style>body { color: red }</style>
   </head><body>
     <script>var secret = \"SCRIPT-LEAK\";</script>
     <h1>Plans</h1>
     <p>From   $9/mo</p>
     <svg><text>SVG-LEAK</text></svg>
   </body></html>")

(t/deftest extracts-the-title
  (t/is (= "Acme — pricing" (:title (aweb/extract-page page "https://acme.test/pricing")))))

(t/deftest text-is-collapsed-and-script-free
  (let [text (:text (aweb/extract-page page "https://acme.test/pricing"))]
    (t/is (str/includes? text "Plans"))
    (t/is (str/includes? text "From $9/mo"))
    (t/is (not (str/includes? text "SCRIPT-LEAK")))
    (t/is (not (str/includes? text "SVG-LEAK")))
    (t/is (not (str/includes? text "color: red")))))

(t/deftest relative-meta-urls-resolve-against-the-page
  (let [meta (:meta (aweb/extract-page page "https://acme.test/pricing"))]
    (t/is (= "https://acme.test/img/social.png" (:og-image meta)))
    (t/is (= "https://acme.test/assets/fav.svg" (:favicon meta)))))

(t/deftest theme-color-and-og-fields-come-through
  (let [meta (:meta (aweb/extract-page page "https://acme.test/"))]
    (t/is (= "#6366f1" (:theme-color meta)))
    (t/is (= "Acme" (:og-title meta)))
    (t/is (= "Rockets etc." (:og-description meta)))))

(t/deftest favicon-falls-back-to-the-root-ico
  (let [meta (:meta (aweb/extract-page "<html><body>hi</body></html>"
                                       "https://acme.test/deep/path"))]
    (t/is (= "https://acme.test/favicon.ico" (:favicon meta)))))

(t/deftest description-meta-is-the-og-description-fallback
  (let [html "<html><head><meta name=\"description\" content=\"plain desc\"></head><body></body></html>"
        meta (:meta (aweb/extract-page html "https://acme.test/"))]
    (t/is (= "plain desc" (:og-description meta)))))

(t/deftest blank-title-reads-as-absent
  (t/is (nil? (:title (aweb/extract-page "<html><head><title>  </title></head><body>x</body></html>"
                                         "https://acme.test/")))))

(t/deftest charset-parses-from-content-type
  (t/is (= "ISO-8859-1" (aweb/charset-of "text/html; charset=ISO-8859-1")))
  (t/is (= "utf-8" (aweb/charset-of "text/html;charset=\"utf-8\"")))
  (t/is (= "UTF-8" (aweb/charset-of "text/html")))
  (t/is (= "UTF-8" (aweb/charset-of nil))))

(t/deftest text-is-capped-with-a-truncation-flag
  (let [[text truncated?] (aweb/capped-text (apply str (repeat 150000 "x")))]
    (t/is (= 100000 (count text)))
    (t/is (true? truncated?)))
  (let [[text truncated?] (aweb/capped-text "short")]
    (t/is (= "short" text))
    (t/is (false? truncated?))))
