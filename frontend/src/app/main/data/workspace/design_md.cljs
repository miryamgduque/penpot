;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.design-md
  "The DESIGN.md format (github.com/google-labs-code/design.md): YAML
  frontmatter carrying machine-readable design tokens + a markdown body of
  human guidance. The vibes doc (and later every foundation) speaks it.

  This ns is deliberately PURE — no store, no plugin-data, no refs — so both
  the agent-tools layer and the panel UI can require it without joining the
  event↔refs dependency cycle that design-doc had to dodge.

  Frontmatter keys stay STRINGS throughout (token names are user data, not
  code identifiers); keywordizing them would silently corrupt names like
  `no.dots` on the way back out."
  (:require
   ["js-yaml" :as yaml]
   [cuerdas.core :as str]))

(def canonical-sections
  "The spec's `##` section names, in canonical order. Sections may be omitted,
  but the ones present should follow this order."
  ["Overview" "Colors" "Typography" "Layout" "Elevation & Depth"
   "Shapes" "Components" "Do's and Don'ts"])

(def ^:private key-order
  ["version" "name" "description" "colors" "typography" "rounded" "spacing"
   "components"])

(def ^:private frontmatter-re
  ;; opening fence at char 0, then the shortest YAML block up to a closing
  ;; fence on its own line (or at end-of-string for a body-less doc)
  #"^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)")

(defn parse
  "Splits `doc` into `{:frontmatter <string-keyed map or nil> :body <string>
  :error <string or nil>}`. A doc with no leading `---` fence is legacy plain
  markdown: whole string as body, nil frontmatter, no error. On broken YAML
  the raw doc is preserved as body so nothing is lost for hand repair."
  [doc]
  (cond
    (not (string? doc))
    {:frontmatter nil :body "" :error "the design doc must be a string"}

    :else
    (if-let [[matched yaml-src] (re-find frontmatter-re doc)]
      (let [body (str/replace (subs doc (count matched)) #"^(\r?\n)+" "")
            fm   (try
                   {:value (js->clj (yaml/load yaml-src))}
                   (catch :default cause
                     {:thrown (or (ex-message cause) (str cause))}))]
        (cond
          (contains? fm :thrown)
          {:frontmatter nil :body doc
           :error (str "invalid YAML frontmatter: " (:thrown fm))}

          (not (map? (:value fm)))
          {:frontmatter nil :body doc
           :error "the frontmatter must be a YAML mapping (key: value pairs)"}

          :else
          {:frontmatter (:value fm) :body body :error nil}))
      {:frontmatter nil :body doc :error nil})))

(defn- ordered-js-frontmatter
  "clj->js with the spec's keys first, in canonical order — hash maps above
  eight entries forget insertion order, and a stable dump is what makes the
  parse→edit→serialize round-trip diff-clean."
  [fm]
  (let [obj (js-obj)]
    (doseq [k key-order]
      (when (contains? fm k)
        (unchecked-set obj k (clj->js (get fm k)))))
    (doseq [[k v] fm]
      (when-not (some #{k} key-order)
        (unchecked-set obj (str k) (clj->js v))))
    obj))

(defn serialize
  "`{:frontmatter :body}` → the DESIGN.md string. Empty/nil frontmatter yields
  the body alone (a legacy doc keeps its exact shape)."
  [{:keys [frontmatter body]}]
  (if (seq frontmatter)
    (str "---\n"
         (yaml/dump (ordered-js-frontmatter frontmatter)
                    #js {:lineWidth -1 :noRefs true})
         "---\n\n"
         (or body ""))
    (or body "")))

;; ---- validation

(def ^:private color-value-re
  ;; hex, a css color function, or a bare keyword-ish name (`ivory`,
  ;; `transparent`). Deliberately loose — the gate is "the form editor and a
  ;; swatch can consume it", not CSS-spec pedantry.
  #"(?i)^(#[0-9a-f]{3,8}|[a-z][a-z-]*(\([^)]*\))?)$")

(defn- scalar? [v] (or (string? v) (number? v)))

(def ^:private token-ref-re #"\{([^{}]+)\}")

(defn- token-ref-target
  "The value a `{path.to.token}` reference points at, or nil when dangling."
  [fm path]
  (get-in fm (str/split path ".")))

(defn- string-leaves
  "All string values anywhere under `v` (maps of maps, any depth)."
  [v]
  (cond
    (string? v) [v]
    (map? v)    (mapcat string-leaves (vals v))
    :else       nil))

(defn- named-map-problems
  "Checks `fm[section]` is a map of token-name → value passing `valid?`;
  problem strings name the offending token."
  [fm section valid? expects]
  (when-let [m (get fm section)]
    (if-not (map? m)
      [(str section " must be a mapping of token names to " expects)]
      (for [[k v] m :when (not (valid? v))]
        (str section "." k " — expected " expects)))))

(defn problems
  "Why parsed `{:frontmatter :body}` fails the DESIGN.md alpha schema, as a
  seq of readable strings; nil/empty when it passes. A nil frontmatter (a
  legacy doc) always passes — validation gates the new format only, never
  existing content. Unknown top-level keys pass (the spec is alpha)."
  [{:keys [frontmatter]}]
  (when (seq frontmatter)
    (let [fm frontmatter]
      (seq
       (concat
        (when (str/blank? (get fm "name"))
          ["the frontmatter needs a name (the design system's title)"])
        (named-map-problems
         fm "colors"
         #(and (string? %)
               (or (re-matches color-value-re %)
                   ;; `{colors.other}` aliases are legal color values too
                   (re-matches token-ref-re %)))
         "a CSS color string")
        (named-map-problems
         fm "typography"
         #(and (map? %) (every? scalar? (vals %)))
         "a map of font properties (fontFamily, fontSize, …)")
        (named-map-problems fm "rounded" scalar? "a radius value")
        (named-map-problems fm "spacing" scalar? "a dimension value")
        (when-let [components (get fm "components")]
          (if-not (map? components)
            ["components must be a mapping of component names to token maps"]
            (for [leaf  (string-leaves components)
                  [_ path] (re-seq token-ref-re leaf)
                  :when (nil? (token-ref-target fm path))]
              (str "components references {" path "} but no such token exists")))))))))
