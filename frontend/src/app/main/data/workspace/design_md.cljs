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

;; ---- token references

(def ^:private token-ref-re #"\{([^{}]+)\}")

(defn- token-ref-target
  "The value a `{path.to.token}` reference points at, or nil when dangling."
  [fm path]
  (get-in fm (str/split path ".")))

;; ---- display model (what the panel's token summary renders)

(def ^:private typography-prop-order
  ["fontFamily" "fontSize" "fontWeight" "lineHeight"])

(defn typography-summary
  "One glanceable line for a typography token's props — the spec's known
  props first, anything else after, alphabetically."
  [props]
  (let [known (keep #(get props %) typography-prop-order)
        extra (->> (sort-by key props)
                   (keep (fn [[k v]]
                           (when-not (some #{k} typography-prop-order) v))))]
    (str/join " · " (map str (concat known extra)))))

(defn- resolve-swatch
  "The paintable value behind `v`: a `{path.to.token}` alias resolved against
  `fm`, any direct value as-is."
  [fm v]
  (if-let [[_ path] (re-matches token-ref-re v)]
    (token-ref-target fm path)
    v))

(defn display-model
  "Parsed frontmatter → `{:name :description :colors :typography :rounded
  :spacing}` with ordered row seqs, or nil when there is no frontmatter.
  Malformed entries are skipped, not rendered broken — `problems` is where
  they get REPORTED; this is the read path and read paths stay calm."
  [fm]
  (when (map? fm)
    {:name        (get fm "name")
     :description (get fm "description")
     :colors      (when (map? (get fm "colors"))
                    (vec (for [[k v] (get fm "colors")
                               :when (string? v)]
                           {:name k :value v :swatch (resolve-swatch fm v)})))
     :typography  (when (map? (get fm "typography"))
                    (vec (for [[k v] (get fm "typography")
                               :when (map? v)]
                           {:name k :summary (typography-summary v)})))
     :rounded     (when (map? (get fm "rounded"))
                    (vec (for [[k v] (get fm "rounded")
                               :when (or (string? v) (number? v))]
                           {:name k :value (str v)})))
     :spacing     (when (map? (get fm "spacing"))
                    (vec (for [[k v] (get fm "spacing")
                               :when (or (string? v) (number? v))]
                           {:name k :value (str v)})))}))

;; ---- validation

(def ^:private color-value-re
  ;; hex, a css color function, or a bare keyword-ish name (`ivory`,
  ;; `transparent`). Deliberately loose — the gate is "the form editor and a
  ;; swatch can consume it", not CSS-spec pedantry.
  #"(?i)^(#[0-9a-f]{3,8}|[a-z][a-z-]*(\([^)]*\))?)$")

(defn- scalar? [v] (or (string? v) (number? v)))

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
      (concat
       (when (some (fn [[k _]] (str/blank? k)) m)
         [(str section " has a token with no name")])
       (for [[k v] m :when (not (valid? v))]
         (str section "." k " — expected " expects))))))

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

;; ---- edit model (the structured editor's writable twin of display-model)

(defn edit-model
  "Frontmatter → what the form editor binds to: name/description strings,
  row VECTORS per token section (stable indices for input paths), the spec's
  typography props broken out per role, and `:extra` carrying everything the
  form does not edit (version, components, unknown keys) through untouched."
  [fm]
  (let [fm (or fm {})]
    {:name        (str (get fm "name" ""))
     :description (str (get fm "description" ""))
     :colors      (vec (for [[k v] (get fm "colors") :when (string? v)]
                         {:name k :value v}))
     :typography  (vec (for [[k v] (get fm "typography") :when (map? v)]
                         {:name        k
                          :family      (str (get v "fontFamily" ""))
                          :size        (str (get v "fontSize" ""))
                          :weight      (str (get v "fontWeight" ""))
                          :line-height (str (get v "lineHeight" ""))
                          :extra       (into {} (remove #(some #{(key %)} typography-prop-order) v))}))
     :rounded     (vec (for [[k v] (get fm "rounded") :when (scalar? v)]
                         {:name k :value (str v)}))
     :spacing     (vec (for [[k v] (get fm "spacing") :when (scalar? v)]
                         {:name k :value (str v)}))
     :extra       (dissoc fm "name" "description" "colors" "typography"
                          "rounded" "spacing")}))

(defn- coerce-scalar
  "Numeric-looking strings back to numbers, so an untouched doc round-trips
  cleanly (YAML wrote 600, the input made it \"600\", 600 goes back out)."
  [s]
  (let [t (str/trim (str s))]
    (if (re-matches #"-?\d+(\.\d+)?" t) (js/parseFloat t) t)))

(defn- named-rows->map
  "Form rows → ordered string map. A fully blank row is an abandoned form row
  and is dropped; a value with a blank name is KEPT (under \"\") so `problems`
  flags it instead of the save silently discarding user input."
  [rows coerce?]
  (let [entries (for [{:keys [name value]} rows
                      :let [n (str/trim (str (or name "")))
                            v (str/trim (str (or value "")))]
                      :when (or (seq n) (seq v))]
                  [n (if coerce? (coerce-scalar v) v)])]
    (when (seq entries)
      (into {} entries))))

(defn- typography-row->props
  [{:keys [family size weight line-height extra]}]
  (let [set-prop (fn [m k v coerce?]
                   (let [v (str/trim (str (or v "")))]
                     (if (str/blank? v)
                       m
                       (assoc m k (if coerce? (coerce-scalar v) v)))))]
    (-> (or extra {})
        (set-prop "fontFamily" family false)
        (set-prop "fontSize" size false)
        (set-prop "fontWeight" weight true)
        (set-prop "lineHeight" line-height true))))

(defn edit-model->frontmatter
  "The inverse of `edit-model`. Returns nil when everything is empty — a doc
  with no tokens serializes as plain markdown, not as empty frontmatter."
  [{:keys [name description colors typography rounded spacing extra]}]
  (let [name        (str/trim (str (or name "")))
        description (str/trim (str (or description "")))
        colors-m    (named-rows->map colors false)
        rounded-m   (named-rows->map rounded true)
        spacing-m   (named-rows->map spacing true)
        typ-entries (for [{:keys [name] :as row} typography
                          :let [n     (str/trim (str (or name "")))
                                props (typography-row->props row)]
                          :when (or (seq n) (seq props))]
                      [n props])
        typ-m       (when (seq typ-entries) (into {} typ-entries))
        fm          (cond-> (or extra {})
                      (seq name)        (assoc "name" name)
                      (seq description) (assoc "description" description)
                      (some? colors-m)  (assoc "colors" colors-m)
                      (some? typ-m)     (assoc "typography" typ-m)
                      (some? rounded-m) (assoc "rounded" rounded-m)
                      (some? spacing-m) (assoc "spacing" spacing-m))]
    (when (seq fm) fm)))

(defn empty-scaffold
  "A blank edit model to hang a legacy doc's first tokens on — version only;
  `problems` insists on a name before it can save."
  []
  {:name "" :description "" :colors [] :typography []
   :rounded [] :spacing [] :extra {"version" "alpha"}})
