;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.components.markdown
  "Renders markdown as elements — never as an HTML string.

  `marked`'s lexer hands back a token tree and we build the elements
  ourselves, so no HTML is ever produced and injection is structurally
  impossible. That is the whole point of using `lexer` over `marked.parse`:
  there is nothing to sanitize, so we need no sanitizer (the repo has none).

  The lexer does leave one hole: it passes hrefs through verbatim, including
  `javascript:`, and React renders those without complaint. Hence `safe-href`.

  Note every branch below builds its element with `mf/html`: the macro only
  compiles *literal* hiccup, so a bare vector returned from a runtime function
  would reach React as a vector-of-keyword and throw.

  Intended for untrusted model output — see the agent chat panel."
  (:require
   ["marked" :as marked]
   [app.common.data.macros :as dm]
   [app.main.ui.components.code-block :refer [code-block*]]
   [rumext.v2 :as mf]))

;; Anything not matched renders as plain text rather than a link.
(def ^:private schema-allowlist-re #"(?i)^(?:https?://|mailto:).+")

(defn- safe-href
  [href]
  (when (and (string? href)
             (some? (re-matches schema-allowlist-re href)))
    href))

(declare render-inline)

(defn- inline-children
  "A `text` token is a leaf inline run, except inside list items where it
  carries nested inline tokens of its own."
  [^js token]
  (if-let [children (seq (.-tokens token))]
    (render-inline children)
    (.-text token)))

(defn- render-link
  [i ^js token]
  (if-let [href (safe-href (.-href token))]
    (mf/html [:a {:key i
                  :href href
                  :target "_blank"
                  ;; the panel lives in the workspace — never hand a
                  ;; model-supplied link our `window.opener`
                  :rel "noopener noreferrer"}
              (render-inline (.-tokens token))])
    ;; unsafe scheme: keep the words, drop the link
    (mf/html [:span {:key i} (.-text token)])))

(defn- render-inline
  [tokens]
  (into []
        (map-indexed
         (fn [i ^js token]
           (case (.-type token)
             "text"     (inline-children token)
             "strong"   (mf/html [:strong {:key i} (render-inline (.-tokens token))])
             "em"       (mf/html [:em {:key i} (render-inline (.-tokens token))])
             "del"      (mf/html [:del {:key i} (render-inline (.-tokens token))])
             "codespan" (mf/html [:code {:key i} (.-text token)])
             "br"       (mf/html [:br {:key i}])
             "link"     (render-link i token)
             ;; `escape`, and anything the lexer adds later, as plain text
             (.-text token))))
        tokens))

(declare render-block)

(defn- render-heading
  "Clamped: a message in a side panel must not open at h1."
  [i ^js token]
  (let [children (render-inline (.-tokens token))]
    (case (min 6 (+ 2 (.-depth token)))
      3 (mf/html [:h3 {:key i} children])
      4 (mf/html [:h4 {:key i} children])
      5 (mf/html [:h5 {:key i} children])
      (mf/html [:h6 {:key i} children]))))

(defn- render-list
  [i ^js token]
  (let [items (map-indexed
               (fn [j ^js item]
                 (mf/html [:li {:key j} (render-block (.-tokens item))]))
               (.-items token))]
    (if ^boolean (.-ordered token)
      (mf/html [:ol {:key i} items])
      (mf/html [:ul {:key i} items]))))

(defn- render-block
  [tokens]
  (into []
        (keep-indexed
         (fn [i ^js token]
           (case (.-type token)
             "space"      nil
             "heading"    (render-heading i token)
             "paragraph"  (mf/html [:p {:key i} (render-inline (.-tokens token))])
             "text"       (mf/html [:span {:key i} (inline-children token)])
             ;; reuses the shared component, which lazy-loads highlight.js via
             ;; `modules/load-fn` — requiring `code-highlight` directly here
             ;; would drag it out of its lazy module into `:shared`
             "code"       (mf/html [:> code-block*
                                    {:key i
                                     :code (.-text token)
                                     :type (when (seq (.-lang token))
                                             (dm/str "language-" (.-lang token)))}])
             "blockquote" (mf/html [:blockquote {:key i} (render-block (.-tokens token))])
             "hr"         (mf/html [:hr {:key i}])
             "list"       (render-list i token)
             ;; raw HTML is shown as text, never parsed
             (mf/html [:p {:key i} (.-raw token)])))
         tokens)))

(mf/defc markdown*
  "Renders `text` as markdown elements. Safe for untrusted input."
  [{:keys [text]}]
  (let [tokens (mf/with-memo [text]
                 (when (seq text)
                   (marked/lexer text)))]
    (when (some? tokens)
      (mf/html [:* (render-block tokens)]))))
