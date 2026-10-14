;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.tokens
  "The design agent's token tools as pure functions; see
  `app.common.agent.write` for what a tool takes and answers."
  (:require
   #?(:cljs [app.common.fonts :as cfnt])
   [app.common.agent.media :as cam]
   [app.common.agent.token-status :as tst]
   [app.common.agent.write :as caw]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as cfc]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.files.tokens :as cfo]
   [app.common.logic.tokens :as clt]
   [app.common.types.fills :as types.fills]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.stroke :as ctss]
   [app.common.types.text :as txt]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.typography :as cty]
   [app.common.uuid :as uuid]
   [clojure.set :as set]
   [cuerdas.core :as str]))

;; Composite types carry structured values (typography is a map of font
;; attributes, shadow a vector of shadow maps). `:value` is `::sm/any`, so
;; `make-token` would accept a plain string for one and author something
;; malformed without complaint — better to not offer them than to offer a lie.
(def ^:private composite-token-types #{:typography :shadow})

(def token-type-names
  "The DTCG type names `create_token` offers, derived from
  `cto/token-type->dtcg-token-type` rather than retyped — a hand-copied list
  drifts the next time Penpot adds a type. Public because the tool
  declarations read it for their enum."
  (->> cto/token-type->dtcg-token-type
       (remove (fn [[k _]] (contains? composite-token-types k)))
       (map second)
       (sort)
       (vec)))

;; --- Token tools (the safe coloring path)

(defn token-type
  "The internal token type for a public DTCG name, or nil. Accepts the
  back-compat singular aliases (`fontSize`, `fontWeight`, …) that
  `cto/dtcg-token-type->token-type` already knows."
  [name]
  (get cto/dtcg-token-type->token-type name))

(declare number-text)

;; --- Shadows
;;
;; A shadow token's value is a list of shadows. It is written with numbers
;; and the names CSS and the tokens file format use; the library stores
;; strings under its own keys.

(def ^:private shadow-names
  {:offsetX :offset-x :x :offset-x :offset-x :offset-x
   :offsetY :offset-y :y :offset-y :offset-y :offset-y
   :blur :blur :spread :spread :color :color :inset :inset})

(defn- one-shadow
  [shadow]
  (when (map? shadow)
    (let [shadow (into {} (keep (fn [[k v]] (when-let [k (get shadow-names (keyword k))] [k v]))) shadow)
          number (fn [k] (let [v (get shadow k 0)]
                           (cond (number? v) v
                                 (string? v) (:value (cfo/parse-token-value v)))))
          parts  (mapv number [:offset-x :offset-y :blur :spread])
          inset  (get shadow :inset false)]
      (when (and (every? number? parts)
                 (not (neg? (nth parts 2)))
                 (string? (:color shadow))
                 (boolean? inset))
        (-> (zipmap [:offset-x :offset-y :blur :spread] (map number-text parts))
            (assoc :color (:color shadow) :inset inset))))))

(defn shadow-value
  "A shadow token's value as the library stores it, or nil when it is not a
  list of shadows: each `{offsetX offsetY blur spread color inset?}`."
  [value]
  (when (and (sequential? value) (seq value))
    (let [shadows (mapv one-shadow value)]
      (when (every? some? shadows)
        shadows))))

(defn- css-color
  "`{:color \"#rrggbb\" :opacity n}` of a hex or an rgb()/rgba() color, or nil."
  [color]
  (when (string? color)
    (let [color (str/trim color)]
      (if-let [[_ r g b a] (re-matches #"(?i)rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)" color)]
        (let [hex (fn [n] (let [h #?(:clj (Integer/toHexString (int (min 255 (parse-long n))))
                                     :cljs (.toString (min 255 (parse-long n)) 16))]
                            (if (= 1 (count h)) (dm/str "0" h) h)))]
          {:color (dm/str "#" (hex r) (hex g) (hex b))
           :opacity (if a (min 1 (parse-double a)) 1)})
        (if-let [[_ rgb alpha] (re-matches #"(?i)#([0-9a-f]{6})([0-9a-f]{2})" color)]
          {:color (str/lower (dm/str "#" rgb))
           :opacity (/ #?(:clj (Integer/parseInt alpha 16) :cljs (js/parseInt alpha 16)) 255.0)}
          (when-let [hex (caw/normalize-hex color)]
            {:color hex :opacity 1}))))))

(defn token-problem
  "Why `create_token` cannot author this token, or nil. Pure."
  [{:keys [type name value]}]
  (let [t (token-type type)]
    (cond
      (or (not (string? name)) (str/blank? name))
      "create_token: name is required, e.g. \"spacing.md\" or \"color.brand.primary\""

      ;; not `blank?`: "0" is a legitimate spacing value
      (or (nil? value) (and (string? value) (empty? value)))
      "create_token: value is required, e.g. \"16\", \"#6366f1\" or \"{color.blue.500}\""

      (nil? t)
      (dm/str "create_token: \"" type "\" is not a token type — use one of: "
              (str/join ", " token-type-names))

      (= :shadow t)
      (when (nil? (shadow-value value))
        (dm/str "create_token: a shadow's value is a list of shadows, each "
                "{offsetX, offsetY, blur, spread, color, inset?} with numbers and a "
                "color like \"rgba(0, 0, 0, 0.12)\"; blur cannot be negative"))

      (contains? composite-token-types t)
      (dm/str "create_token: " type " tokens need a structured value, which this"
              " tool cannot express yet — author it in the tokens panel")

      :else nil)))

;; --- Token sets and themes
;;
;; `penpot-foundations`' whole method: primitives in one set, semantics duplicated
;; across `modes/light` / `modes/dark`, and a theme per mode toggling the matching
;; set. "/" is the group separator, which is what makes `modes/*` a group.

(defn normalize-set
  "A set name as the library stores it — `\"modes / dark\"` → `\"modes/dark\"`."
  [name]
  (ctob/normalize-set-name (str name)))

(defn token-set-problem
  "Why a token cannot target this set, or nil. `nil` means Phase 08's default
  (the library's existing set), which stays untouched.

  Note the param is `set-name`, not `set`: naming it `set` shadows
  `clojure.core/set`, and `(set set-names)` then calls a string."
  [set-names set-name]
  (when (some? set-name)
    (let [want (normalize-set set-name)]
      (cond
        (empty? set-names)
        (dm/str "set \"" want "\" does not exist — this file has no token sets yet;"
                " create one with create_token_set")

        (not (contains? (into #{} set-names) want))
        (dm/str "set \"" want "\" does not exist — this file has: "
                (str/join ", " (sort set-names))
                " (create another with create_token_set)")))))

(defn new-set-problem
  "Why `create_token_set` cannot make this set, or nil."
  [set-names name]
  (let [want (normalize-set name)]
    (cond
      (or (not (string? name)) (str/blank? name))
      "create_token_set: name is required, e.g. \"modes/dark\" (\"/\" groups sets)"

      ;; create-token-set commits over an existing set — which is exactly how the
      ;; library got wiped in Phase 08. Never silently replace one.
      (contains? (into #{} set-names) want)
      (dm/str "create_token_set: a set named \"" want "\" already exists"
              " — name a token's set with create_token's `set` param instead"))))

(defn theme-problem
  "Why `create_token_theme` cannot make this theme, or nil."
  [set-names {:keys [name sets]}]
  (let [known (into #{} set-names)
        want  (mapv normalize-set sets)
        gone  (remove known want)]
    (cond
      (or (not (string? name)) (str/blank? name))
      "create_token_theme: name is required, e.g. \"Dark\""

      (empty? sets)
      (dm/str "create_token_theme: a theme needs at least one set to enable"
              " — a theme that enables nothing would activate and change nothing")

      (seq gone)
      (dm/str "create_token_theme: no set named " (str/join ", " (map #(dm/str "\"" % "\"") gone))
              " — this file has: " (str/join ", " (sort set-names))))))

;; --- Looking at the library

(defn- set-names
  [lib]
  (some->> lib (ctob/get-sets) (mapv ctob/get-name)))

(defn- set-id-by-name
  [lib name]
  (let [want (normalize-set name)]
    (some->> lib
             (ctob/get-sets)
             (filter #(= want (ctob/get-name %)))
             (first)
             (ctob/get-id))))

(defn- first-set-id
  "The id of a set already in the library, or nil when it has none."
  [lib]
  (some-> lib (ctob/get-sets) (first) (ctob/get-id)))

(defn library-changes
  [data]
  (-> (cb/empty-changes)
      (cb/with-library-data data)))

(defn- add-first-set
  "A library with no set gets one, and it is on, so a token authored into
  it is live. → `[changes set-id]`."
  [changes]
  (let [token-set (ctob/make-token-set :name "Global")]
    [(-> changes
         (cb/set-token-set (ctob/get-id token-set) token-set)
         (cb/set-tokens-status (tst/only-sets #{(ctob/get-id token-set)})))
     (ctob/get-id token-set)]))

(defn stored-value
  "The value as the token forms store it. A font family is a list of families:
  kept as the one string the model wrote, the workspace reads it as no font at
  all. A reference to another token stays a string."
  [kind value]
  (cond
    (and (= :font-family kind)
         (string? value)
         (empty? (cto/find-token-value-references value)))
    (cto/split-font-family value)

    (and (= :shadow kind) (sequential? value))
    (or (shadow-value value) value)

    :else
    value))

(defn- held
  "The token named `token-name` that set `set-id` holds already, or nil."
  [lib set-id token-name]
  (when (and lib set-id)
    (get (ctob/get-tokens lib set-id) token-name)))

(defn- changes-held?
  "Whether `entry` gives a token that set `set-id` holds already another
  value or type."
  [lib set-id {:keys [type name value]}]
  (when-let [prev (held lib set-id name)]
    (let [kind (token-type type)]
      (or (not= kind (:type prev))
          (not= (stored-value kind value) (:value prev))))))

(defn- add-token
  "Writes the token into the set. A token of that name already there keeps
  its id and takes the new value: to the model, creating it again is how a
  token is changed."
  [changes lib set-id {:keys [type name value]}]
  (let [kind  (token-type type)
        prev  (held lib set-id name)
        token (ctob/make-token (cond-> {:type kind :name name :value (stored-value kind value)}
                                 (some? prev) (assoc :id (:id prev))))]
    (cb/set-token changes set-id (:id token) token)))

(declare follow)

;; --- create_token / create_tokens

(defn create-token
  ;; `:set` is destructured as `target-set`: binding it to `set` would shadow
  ;; clojure.core/set for the whole fn
  [{:keys [data fonts]} {:keys [type name value] target-set :set :as input} engine]
  (let [lib (:tokens-lib data)]
    (if-let [problem (or (token-problem input)
                         (some->> (token-set-problem (set-names lib) target-set)
                                  (dm/str "create_token: ")))]
      {:problem problem}
      (let [changes (library-changes data)
            [changes set-id]
            (if-let [set-id (if target-set (set-id-by-name lib target-set) (first-set-id lib))]
              [changes set-id]
              ;; no sets at all: this is the case that legitimately makes one
              (add-first-set changes))]
        (follow data fonts
                (caw/redo (add-token changes lib set-id input))
                (when (changes-held? lib set-id input) [name])
                engine
                (cond-> {:name name :type type :value value
                         :note "token created — bind it to shapes with apply_tokens"}
                  target-set (assoc :set (normalize-set target-set))))))))

(defn create-tokens
  "Many tokens in ONE call. All-or-nothing: every entry validates (including
  its target set existing) before any token is created."
  [{:keys [data fonts]} {:keys [tokens] default-set :set} engine]
  (let [lib   (:tokens-lib data)
        names (set-names lib)]
    (if-not (and (sequential? tokens) (seq tokens))
      {:problem (str "create_tokens: pass tokens — an array of "
                     "{name, type, value, set?}; a top-level `set` is "
                     "the default for entries without one")}
      (let [entries  (mapv (fn [t] (update t :set #(or % default-set))) tokens)
            problems (into []
                           (keep-indexed
                            (fn [i t]
                              (when-let [p (or (token-problem t)
                                               (some->> (token-set-problem names (:set t))
                                                        (dm/str "create_tokens: ")))]
                                (dm/str "tokens[" i "]"
                                        (when (:name t) (dm/str " (" (:name t) ")"))
                                        ": " p))))
                           entries)]
        (if (seq problems)
          {:problem (str/join " | " problems)}
          (let [[changes default-id]
                (if-let [set-id (first-set-id lib)]
                  [(library-changes data) set-id]
                  ;; one set for every entry that names none
                  (if (some #(nil? (:set %)) entries)
                    (add-first-set (library-changes data))
                    [(library-changes data) nil]))
                set-of (fn [{target-set :set}]
                         (if target-set (set-id-by-name lib target-set) default-id))]
            (follow data fonts
                    (caw/redo
                     (reduce (fn [changes entry] (add-token changes lib (set-of entry) entry))
                             changes
                             entries))
                    (into [] (comp (filter #(changes-held? lib (set-of %) %)) (map :name)) entries)
                    engine
                    {:created (count entries)
                     :note (str "tokens created — bind them to shapes with "
                                "apply_tokens; values referencing other tokens "
                                "(\"{color.blue.500}\") resolve lazily")})))))))

;; --- create_token_set / create_token_theme

(defn create-token-set
  [{:keys [data]} {:keys [name]} _]
  (if-let [problem (new-set-problem (set-names (:tokens-lib data)) name)]
    {:problem problem}
    (let [nm        (normalize-set name)
          token-set (ctob/make-token-set {:name nm})]
      {:changes (-> (library-changes data)
                    (cb/set-token-set (ctob/get-id token-set) token-set)
                    (caw/redo))
       :result {:set nm
                :note (str "set created — aim tokens at it with create_token's `set` "
                           "param. A set is only live when an active theme enables "
                           "it: pair modes/* sets with a theme each.")}})))

(defn create-token-theme
  [{:keys [data]} {:keys [name group sets] :as input} _]
  (let [lib (:tokens-lib data)]
    (if-let [problem (theme-problem (set-names lib) input)]
      {:problem problem}
      (let [theme (ctob/make-token-theme
                   (cond-> {:name name :sets (into #{} (map normalize-set) sets)}
                     group (assoc :group group)))]
        {:changes (-> (library-changes data)
                      (cb/set-token-theme (ctob/get-id theme) theme)
                      (caw/redo))
         :result {:theme name
                  :note (str "theme created — it is not active yet; activate_theme "
                             "switches which of its sets resolve")}}))))

;; --- Which properties a token can bind to
;;
;; The workspace keeps this per token type next to its forms
;; (`app.main.data.workspace.tokens.application/token-properties`); this is the
;; part of it the tools read: the properties a token of each type binds to by
;; default, and all of the ones it may bind to.

(def token-type-attrs
  {:border-radius   {:attributes cto/border-radius-keys}
   :shadow          {:attributes cto/shadow-keys}
   :color           {:attributes #{:fill} :all-attributes cto/color-keys}
   :font-size       {:attributes cto/font-size-keys}
   :letter-spacing  {:attributes cto/letter-spacing-keys}
   :font-family     {:attributes cto/font-family-keys}
   :text-case       {:attributes cto/text-case-keys}
   :font-weight     {:attributes cto/font-weight-keys}
   :typography      {:attributes cto/typography-token-keys}
   :text-decoration {:attributes cto/text-decoration-keys}
   :stroke-width    {:attributes cto/stroke-width-keys}
   :sizing          {:attributes #{:width :height} :all-attributes cto/sizing-keys}
   :dimensions      {:attributes #{:width :height}
                     :all-attributes (set/union cto/spacing-keys
                                                cto/sizing-keys
                                                cto/border-radius-keys
                                                cto/axis-keys
                                                cto/stroke-width-keys)}
   :opacity         {:attributes cto/opacity-keys}
   :number          {:attributes cto/rotation-keys :all-attributes cto/number-keys}
   :rotation        {:attributes cto/rotation-keys}
   :spacing         {:attributes #{:column-gap :row-gap} :all-attributes cto/spacing-keys}})

(def ^:private attr-alias
  "The plugin API's semantic names for Penpot's positional attr keys, copied
  deliberately from `app.plugins.tokens`: the skills already speak these names
  (`applyToken(t, [\"columnGap\"])`), and `:p1` means nothing without this
  table."
  {:r1 :border-radius-top-left
   :r2 :border-radius-top-right
   :r3 :border-radius-bottom-right
   :r4 :border-radius-bottom-left
   :p1 :padding-top
   :p2 :padding-right
   :p3 :padding-bottom
   :p4 :padding-left
   :m1 :margin-top
   :m2 :margin-right
   :m3 :margin-bottom
   :m4 :margin-left})

(def token-attr-universe
  "Every shape attribute a token can bind to (`cto/all-keys`)."
  cto/all-keys)

(defn public-attr-name
  "The camelCase name the agent uses for an internal attr keyword."
  [k]
  (str/camel (name (get attr-alias k k))))

(def ^:private public->attrs
  "Public name → the attrs it binds. One each, but for a stroke's width: it
  is one per side now, and \"strokeWidth\" binds all four."
  (into {"stroke"      #{:stroke-color} ;; legacy: the shipped spec offers "stroke"
         "strokeWidth" cto/per-side-stroke-width-keys}
        (map (fn [k] [(public-attr-name k) #{k}]))
        token-attr-universe))

(defn token-attrs
  "The internal attr keywords for a public name, or nil."
  [name]
  (get public->attrs name))

(defn token-attr
  "Whether a public name is a property a token binds to: the first of its
  attrs, or nil."
  [name]
  (first (token-attrs name)))

(defn- type-attrs
  [token-type]
  (let [{:keys [attributes all-attributes]} (get token-type-attrs token-type)]
    (or all-attributes attributes)))

(defn application-problem
  "Why this token cannot bind to these properties, or nil. Pure."
  [token properties]
  (let [valid (type-attrs (:type token))]
    (some (fn [p]
            (if-let [ks (token-attrs p)]
              (when (and (seq valid) (not-every? #(contains? valid %) ks))
                (dm/str "apply_tokens: token \"" (:name token) "\" is a "
                        (some-> (:type token) name) " token and cannot bind to "
                        p " — it can bind to: "
                        (str/join ", " (sort (map public-attr-name valid)))))
              (dm/str "apply_tokens: \"" p "\" is not a property a token can bind"
                      " to — \"" (:name token) "\" can bind to: "
                      (str/join ", " (sort (map public-attr-name valid))))))
          properties)))

(defn attr-set
  "The internal attrs for the requested public property names, defaulting to the
  token's own default (fill for a color) when none are given."
  [token properties]
  (if (seq properties)
    (into #{} (mapcat token-attrs) properties)
    (or (some-> (get token-type-attrs (:type token)) :attributes) #{:fill})))

;; --- What a token is worth
;;
;; The workspace resolves tokens with a JavaScript library that follows
;; references, does arithmetic and takes composite values apart. A host that
;; has it hands it over as `:resolve-tokens` in the options: `(fn [tokens])` →
;; token name → `{:resolved-value …}` (with `:color` for a color, `:error` in
;; place of the value when the token does not resolve), or nil when it has not
;; resolved those tokens yet. Resolving is asynchronous and a tool is not, so
;; on nil the tool answers `{:pending {:tokens tokens}}`: the host resolves
;; them and runs the tool again.
;;
;; A host without it gets the plain cases — a literal, or an alias of one —
;; and a `{:fallback}` for the rest.

(def ^:private number-types
  #{:border-radius :spacing :sizing :dimensions :stroke-width :rotation :number
    :font-size :letter-spacing :opacity})

(def ^:private text-cases #{"none" "uppercase" "lowercase" "capitalize"})

(defn- literal-value
  "The value a token holds once its aliases are followed, or `::unresolved`."
  [tokens token seen]
  (let [value (:value token)
        alias (when (string? value)
                (second (re-matches #"^\s*\{([^{}]+)\}\s*$" value)))]
    (cond
      (nil? alias)
      value

      (or (contains? seen alias) (nil? (get tokens alias)))
      ::unresolved

      :else
      (recur tokens (get tokens alias) (conj seen alias)))))

(defn resolved-value
  "What `token` resolves to among the active `tokens` without the resolver
  library — a number, a string or `{:color :opacity}` — or `::unresolved`."
  [tokens token]
  (let [value (literal-value tokens token #{(:name token)})
        kind  (:type token)]
    (cond
      (= ::unresolved value)
      ::unresolved

      (and (string? value) (seq (cto/find-token-value-references value)))
      ::unresolved

      (= :color kind)
      (if-let [hex (caw/normalize-hex value)]
        {:color hex :opacity 1}
        ::unresolved)

      (contains? number-types kind)
      (let [{:keys [value unit]} (cfo/parse-token-value value)]
        (if (and (number? value) (or (nil? unit) (= "px" unit)))
          value
          ::unresolved))

      (= :text-case kind)
      (let [value (when (string? value) (str/lower (str/trim value)))]
        (if (contains? text-cases value) value ::unresolved))

      (= :text-decoration kind)
      (or (when (string? value) (cto/valid-text-decoration value)) ::unresolved)

      ;; "700", "bold", "600 italic": what a font weight token may say
      (= :font-weight kind)
      (if (some? (cto/valid-font-weight-variant (dm/str value))) (dm/str value) ::unresolved)

      ;; a list of families, as the token forms store one
      (= :font-family kind)
      (let [families (if (string? value) (cto/split-font-family value) value)]
        (if (and (sequential? families) (seq families) (every? string? families))
          (vec families)
          ::unresolved))

      (= :shadow kind)
      (let [shadows (some->> (shadow-value value)
                             (mapv (fn [shadow]
                                     (when-let [color (css-color (:color shadow))]
                                       (-> (into {} (map (fn [k] [k (parse-double (get shadow k))]))
                                                 [:offset-x :offset-y :blur :spread])
                                           (assoc :color color :inset (:inset shadow)))))))]
        (if (and (seq shadows) (every? some? shadows))
          shadows
          ::unresolved))

      :else
      ::unresolved)))

(defn- plain-worth
  [active]
  (into {}
        (map (fn [[token-name token]]
               (let [value (resolved-value active token)]
                 [token-name (cond
                               (= ::unresolved value) {:unresolved true}
                               (map? value)           {:resolved-value (:color value) :color value}
                               :else                  {:resolved-value value})])))
        active))

(defn- worth
  "What the `active` tokens are worth → `{:resolved map}`, or `{:pending}`
  when the host has to resolve them first."
  [active {:keys [resolve-tokens]}]
  (if (nil? resolve-tokens)
    {:resolved (plain-worth active)}
    (if-let [resolved (resolve-tokens active)]
      {:resolved resolved}
      {:pending {:tokens active}})))

(defn- token-value
  "The value to write for `token`: nil when it is not active or does not
  resolve, `::unresolved` when this host cannot tell."
  [resolved token]
  (let [entry (get resolved (:name token))]
    (cond
      (nil? entry)             nil
      (:unresolved entry)      ::unresolved
      (some? (:error entry))   nil
      (= :color (:type token)) (:color entry)
      :else                    (:resolved-value entry))))

;; --- Giving a shape a token's value
;;
;; One function per kind of property, as in the workspace
;; (`app.main.data.workspace.tokens.application`).

(def ^:private quiet {:ignore-touched true})

(defn- text-node?
  [node]
  (or (txt/is-text-node? node) (txt/is-paragraph-node? node)))

(defn- fill-of
  [{:keys [color opacity]}]
  (types.fills/check-fill {:fill-color color :fill-opacity opacity}))

(defn- set-fill
  [changes ids color]
  (let [fill    (fill-of color)
        objects (cb/lookup-objects changes)
        text?   #(cfh/text-shape? (get objects %))]
    (-> changes
        (cb/update-shapes (remove text? ids)
                          #(update % :fills types.fills/assoc 0 fill)
                          quiet)
        ;; a text keeps its color in its content, run by run
        (cb/update-shapes (filter text? ids)
                          (fn [shape]
                            (-> shape
                                (dissoc :fills)
                                (d/update-when :content
                                               #(txt/transform-nodes
                                                 text-node?
                                                 (fn [node] (update node :fills types.fills/assoc 0 fill))
                                                 %))))
                          quiet))))

(defn- stroke-style
  [stroke]
  (-> (select-keys stroke [:stroke-style :stroke-alignment :stroke-width
                           :stroke-cap-start :stroke-cap-end])
      (update :stroke-width #(or % 1))
      (update :stroke-style #(or % :solid))
      (update :stroke-alignment #(or % :inner))
      (d/without-nils)))

(defn- set-stroke-color
  [changes ids {:keys [color opacity]}]
  (cb/update-shapes changes ids
                    (fn [shape]
                      (update shape :strokes
                              (fn [strokes]
                                (let [stroke (-> (stroke-style (get strokes 0))
                                                 (assoc :stroke-color color :stroke-opacity opacity))]
                                  (if (nil? strokes) [stroke] (assoc strokes 0 stroke))))))
                    quiet))

(defn- set-stroke-width
  "The sides `sides` of each shape's first stroke get `value`, as the
  workspace does it: the other sides keep their width, every side is
  written out, and `:stroke-width` stays the top's."
  [changes ids sides value]
  (cb/update-shapes changes ids
                    (fn [shape]
                      (let [stroke (ctss/materialize-stroke-side-widths
                                    (first (:strokes shape)) sides (max 0 value))]
                        (if (seq (:strokes shape))
                          (update shape :strokes #(into [stroke] (rest %)))
                          (assoc shape :strokes [stroke]))))
                    quiet))

(defn- set-geometry
  "Moves, sizes or turns each shape, through the engine."
  [changes ids attrs value engine]
  (reduce (fn [changes id]
            (caw/modify-into changes (into {:shapeId (dm/str id)} (map (fn [k] [k value])) attrs) engine))
          changes
          ids))

(defn- set-text-attrs
  "Merges text attributes into every run and paragraph of the texts among
  `ids`. `attrs` is a map, or a function of the run that gives one."
  [changes ids attrs resize? engine]
  (let [objects  (cb/lookup-objects changes)
        ids      (filter #(cfh/text-shape? (get objects %)) ids)
        attrs-of (if (fn? attrs) attrs (constantly attrs))
        changes  (cb/update-shapes changes ids
                                   (fn [shape]
                                     (txt/update-text-content
                                      shape text-node?
                                      (fn [node _] (-> node (d/txt-merge (attrs-of node)) (cty/remove-typography-from-node)))
                                      nil))
                                   quiet)]
    (if resize?
      (reduce #(caw/fit-text %1 engine %2) changes ids)
      changes)))

(defn number-text
  "A number as a text attribute holds it: `24`, not `24.0`."
  [value]
  #?(:cljs (str value)
     :clj  (if (== value (long value)) (str (long value)) (str value))))

(defn- font-family-attrs
  "The text attributes for a font family token's value: the font of that
  family when the file can use one, the bare family name otherwise."
  [fonts value]
  (when-let [family (some-> (first value) (str/replace #"^[\s\"']+|[\s\"']+$" ""))]
    (if-let [font (cam/find-font-family fonts family)]
      {:font-id (:id font) :font-family (:family font)}
      {:font-id (dm/str uuid/zero) :font-family family})))

(defn- closest-variant
  "The variant of `font` nearest to a weight and style. The matching lives
  with the font catalog, which is JavaScript."
  [font weight style]
  #?(:cljs (cfnt/closest-variant (:variants font []) weight style)
     :clj  (do font weight style nil)))

(defn- font-weight-attrs
  "A function of a text run → the attributes that give it the weight a font
  weight token's value names, in the variant of the run's own font that is
  nearest to it."
  [fonts value]
  (when-let [variant (cto/valid-font-weight-variant value)]
    (let [attrs (assoc variant
                       :font-weight (:weight variant)
                       :font-style (:style variant))]
      (fn [node]
        (let [found (closest-variant (get fonts (:font-id node)) (:weight variant) (:style variant))]
          (cond-> attrs
            (:id found) (assoc :font-variant-id (:id found))))))))

(defn- shadows-of
  [value]
  (mapv (fn [{:keys [offset-x offset-y blur spread color inset]}]
          {:id (uuid/next)
           :hidden false
           :offset-x offset-x
           :offset-y offset-y
           :blur blur
           ;; a resolver answers a CSS color; a shape holds color and opacity
           :color (if (map? color) color (or (css-color color) {:color "#000000" :opacity 0.2}))
           :spread spread
           :style (if (true? inset) :inner-shadow :drop-shadow)})
        value))

(defn- write-text-value
  "One text property, by the kind of token that carries it."
  [changes ids kind value {:keys [fonts] :as engine}]
  (case kind
    :font-size
    (cond-> changes
      (number? value) (set-text-attrs ids {:font-size (number-text value)} true engine))

    :letter-spacing
    (cond-> changes
      (number? value) (set-text-attrs ids {:letter-spacing (number-text value)} true engine))

    :line-height
    (cond-> changes
      (number? value) (set-text-attrs ids {:line-height value} true engine))

    :font-family
    (if-let [attrs (when (sequential? value) (font-family-attrs fonts value))]
      (set-text-attrs changes ids attrs true engine)
      changes)

    :font-weight
    (if-let [attrs-of (when (some? value) (font-weight-attrs fonts value))]
      (set-text-attrs changes ids attrs-of true engine)
      changes)

    :text-case
    (cond-> changes
      (string? value) (set-text-attrs ids {:text-transform value} false engine))

    :text-decoration
    (if-let [valid (when (string? value) (cto/valid-text-decoration value))]
      (set-text-attrs changes ids
                      {:text-decoration (if (= "strike-through" valid) "line-through" valid)}
                      false engine)
      changes)

    changes))

(def ^:private paint-attrs
  "The token properties that change how a shape is painted and not where
  anything is."
  #{:fill :stroke-color :opacity :shadow :r1 :r2 :r3 :r4})

(defn- painted
  "`shape` with the resolved `value` on the paint properties `attrs`: what
  `write-value` does for them, to one shape."
  [shape attrs value]
  (let [color  (when (and (map? value) (contains? value :color)) value)
        number (when (number? value) value)
        radii  (not-empty (set/intersection attrs #{:r1 :r2 :r3 :r4}))]
    (cond-> shape
      (and color (:fill attrs) (cfh/text-shape? shape))
      (-> (dissoc :fills)
          (d/update-when :content
                         #(txt/transform-nodes
                           text-node?
                           (fn [node] (update node :fills types.fills/assoc 0 (fill-of color)))
                           %)))

      (and color (:fill attrs) (not (cfh/text-shape? shape)))
      (update :fills types.fills/assoc 0 (fill-of color))

      (and color (:stroke-color attrs))
      (update :strokes
              (fn [strokes]
                (let [stroke (-> (stroke-style (get strokes 0))
                                 (assoc :stroke-color (:color color) :stroke-opacity (:opacity color)))]
                  (if (nil? strokes) [stroke] (assoc strokes 0 stroke)))))

      (and number radii)
      (ctsr/set-radius-for-corners radii (max 0 number))

      (and number (:opacity attrs) (<= 0 number 1))
      (assoc :opacity number)

      (and (sequential? value) (:shadow attrs))
      (assoc :shadow (shadows-of value)))))

(def ^:private typography-order
  [:font-size :font-family :font-weight :letter-spacing :text-case :text-decoration :line-height])

(defn- write-value
  "Appends to `changes` what gives shapes `ids` the resolved `value` on the
  token properties `attrs`."
  [changes ids attrs value engine]
  (let [objects  (cb/lookup-objects changes)
        some-of  (fn [ks] (not-empty (set/intersection attrs ks)))
        layouts  (filter #(ctl/any-layout? (get objects %)) ids)
        children (filter #(ctl/any-layout-immediate-child? objects (get objects %)) ids)
        patch    (fn [changes ids attrs] (cb/update-shapes changes ids (d/patch-object attrs) quiet))
        number   (when (number? value) value)
        color    (when (and (map? value) (contains? value :color)) value)
        text     (fn [changes kind] (write-text-value changes ids kind value engine))]
    (cond-> changes
      (and number (some-of #{:r1 :r2 :r3 :r4}))
      (cb/update-shapes ids #(ctsr/set-radius-for-corners % (some-of #{:r1 :r2 :r3 :r4}) (max 0 number)) quiet)

      (and color (:fill attrs))
      (set-fill ids color)

      (and color (:stroke-color attrs))
      (set-stroke-color ids color)

      (and number (seq (set/intersection attrs cto/per-side-stroke-width-keys)))
      (set-stroke-width ids (set/intersection attrs cto/per-side-stroke-width-keys) number)

      (and number (:opacity attrs) (<= 0 number 1))
      (cb/update-shapes ids #(assoc % :opacity number) quiet)

      (and (sequential? value) (:shadow attrs))
      (cb/update-shapes ids #(assoc % :shadow (shadows-of value)) quiet)

      (and number (some-of #{:p1 :p2 :p3 :p4}))
      (patch layouts {:layout-padding (zipmap (some-of #{:p1 :p2 :p3 :p4}) (repeat number))})

      (and number (some-of #{:m1 :m2 :m3 :m4}))
      (patch children {:layout-item-margin (zipmap (some-of #{:m1 :m2 :m3 :m4}) (repeat number))})

      (and number (some-of #{:row-gap :column-gap}))
      (patch layouts {:layout-gap (zipmap (some-of #{:row-gap :column-gap}) (repeat number))})

      (and number (some-of #{:layout-item-min-w :layout-item-min-h :layout-item-max-w :layout-item-max-h}))
      (patch ids (zipmap (some-of #{:layout-item-min-w :layout-item-min-h :layout-item-max-w :layout-item-max-h})
                         (repeat number)))

      (:font-size attrs)       (text :font-size)
      (:letter-spacing attrs)  (text :letter-spacing)
      (:line-height attrs)     (text :line-height)
      (:font-family attrs)     (text :font-family)
      (:font-weight attrs)     (text :font-weight)
      (:text-case attrs)       (text :text-case)
      (:text-decoration attrs) (text :text-decoration)

      ;; a composite: each of its parts, as the token of that part would
      (and (map? value) (:typography attrs))
      (as-> changes
            (reduce (fn [changes kind]
                      (if-some [part (get value kind)]
                        (write-text-value changes ids kind part engine)
                        changes))
                    changes
                    typography-order))

      (and number (some-of #{:width :height}))
      (set-geometry ids (some-of #{:width :height}) number engine)

      (and number (some-of #{:x :y}))
      (set-geometry ids (some-of #{:x :y}) number engine)

      (and number (:rotation attrs))
      (set-geometry ids #{:rotation} number engine)

      ;; paint moves nothing: only what changes a size or a gap lays out again
      (seq (set/difference attrs paint-attrs))
      (caw/reflow-all (:propagate engine)
                      (into (vec ids) (keep #(cfh/get-parent-id objects %)) ids)))))

(def ^:private text-measuring-attrs
  #{:font-size :letter-spacing :line-height :font-family :font-weight :typography :width :height})

(defn- needs-text-measure?
  [objects ids attrs]
  (and (some #(cfh/text-shape? (get objects %)) ids)
       (some attrs text-measuring-attrs)))

(defn- write-fallback
  "Why this host cannot write `attrs` on shapes `ids` → `{:fallback}`, or nil."
  [objects ids attrs engine]
  (cond
    (and (nil? (:measure-text engine)) (needs-text-measure? objects ids attrs))
    {:fallback "text cannot be measured on the server without the render engine"}

    (and (nil? (:fonts engine)) (some attrs #{:font-family :font-weight :typography}))
    {:fallback "the font list is not available on the server here"}

    #?@(:clj [(some attrs #{:font-weight :typography})
              {:fallback "font weights are matched to a font's variants in JavaScript only"}])))

(defn- binds?
  "Whether token properties `attrs` of a `token-type` token apply to `shape`."
  [objects shape attrs token-type]
  (or (and (ctl/any-layout-immediate-child? objects shape)
           (some cto/spacing-margin-keys attrs))
      (and (cto/any-appliable-attr-for-shape? attrs (:type shape) (:layout shape))
           (set/subset? attrs (type-attrs token-type)))))

(defn- bind-into
  "Binds `token` to `attrs` of shape `id`. → the changes, or `{:fallback}`."
  [changes resolved token attrs id engine]
  (let [objects (cb/lookup-objects changes)
        shape   (get objects id)
        value   (token-value resolved token)
        ;; an atomic typography token replaces a composite one, and back
        unbind  (cond
                  (contains? cto/typography-token-keys (:type token)) cto/typography-keys
                  (contains? cto/typography-keys (:type token)) cto/typography-token-keys)]
    (cond
      (or (nil? shape) (not (binds? objects shape attrs (:type token))))
      changes

      (= ::unresolved value)
      {:fallback "this token's value needs the workspace's resolver"}

      :else
      (or (when (some? value) (write-fallback objects [id] attrs engine))
          (-> changes
              (cb/update-shapes [id] (fn [shape]
                                       (-> shape
                                           (update :applied-tokens #(apply dissoc % unbind))
                                           (update :applied-tokens merge (cfo/attributes-map attrs token)))))
              ;; a token no active set holds, or one that does not resolve,
              ;; is bound and worth nothing yet
              (cond-> (some? value)
                (write-value [id] attrs value engine)))))))

(defn apply-tokens
  [{:keys [page-id objects data fonts]} {:keys [applications]} engine]
  (if (empty? applications)
    {:problem "apply_tokens: no applications given"}
    (let [lib    (:tokens-lib data)
          all    (some-> lib ctob/get-all-tokens-map)
          active (tst/active-tokens data)
          engine (assoc engine :fonts fonts)
          {:keys [resolved pending]} (worth active engine)]
      (if (some? pending)
        {:pending pending}
        (let [run (reduce
                   (fn [{:keys [changes] :as run} {:keys [shapeId tokenName properties]}]
                     (let [id      (when (string? shapeId) (parse-uuid shapeId))
                           token   (get all tokenName)
                           problem (when token (application-problem token properties))
                           row     {:shapeId shapeId :token tokenName}]
                       (if (and id token (nil? problem))
                         (let [next (bind-into changes resolved token (attr-set token properties) id engine)]
                           (if (contains? next :fallback)
                             (reduced next)
                             (-> run
                                 (assoc :changes next)
                                 (update :results conj (assoc row :ok true)))))
                         (update run :results conj
                                 (assoc row :ok false
                                        :error (cond
                                                 (nil? id) "invalid shapeId"
                                                 (nil? token) (dm/str "no token named \"" tokenName
                                                                      "\" — create it with create_token")
                                                 :else problem))))))
                   {:changes (caw/page-changes page-id objects) :results []}
                   applications)]
          (if (contains? run :fallback)
            run
            {:changes (caw/redo (:changes run))
             :result {:results (:results run)
                      :note "tokens resolve asynchronously — verify with read_design or audit_file"}}))))))

;; --- activate_theme

(defn- dependents
  "`names` and every active token whose value refers to one of them, however
  far."
  [active names]
  (let [refers (fn [names token]
                 (->> (tree-seq coll? seq (:value token))
                      (filter string?)
                      (mapcat cto/find-token-value-references)
                      (some names)))]
    (loop [names (set names)]
      (let [more (into names (keep (fn [[token-name token]] (when (refers names token) token-name))) active)]
        (if (= more names)
          names
          (recur more))))))

(defn- page-rebind*
  [page-id objects active resolved names only engine]
  ;; shapes are written together by token and properties: one layout pass for
  ;; all the shapes a token sizes, not one for each. And paint — fills,
  ;; strokes, corners, shadows — moves nothing, so all of it goes in one pass
  ;; over the page: a switch of theme is mostly paint.
  (let [groups (reduce
                (fn [groups [id shape]]
                  (->> (when (or (nil? only) (contains? only id)) (:applied-tokens shape))
                       (filter (fn [[_ token-name]] (or (nil? names) (contains? names token-name))))
                       (group-by val)
                       (reduce (fn [groups [token-name entries]]
                                 (update groups [token-name (into #{} (map key) entries)] (fnil conj []) id))
                               groups)))
                {}
                objects)
        valued (keep (fn [[[token-name attrs] ids]]
                       (when-let [value (some->> (get active token-name) (token-value resolved))]
                         {:attrs attrs :ids ids :value value}))
                     groups)
        paint? (fn [{:keys [attrs value]}]
                 (and (not= ::unresolved value) (empty? (set/difference attrs paint-attrs))))
        paints (reduce (fn [paints {:keys [attrs ids value]}]
                         (reduce (fn [paints id] (update paints id (fnil conj []) [attrs value])) paints ids))
                       {}
                       (filter paint? valued))]
    (reduce
     (fn [changes {:keys [attrs ids value]}]
       (if (= ::unresolved value)
         (reduced {:fallback "a bound token's value needs the workspace's resolver"})
         (if-let [fallback (write-fallback objects ids attrs engine)]
           (reduced fallback)
           (write-value changes ids attrs value engine))))
     (cond-> (caw/page-changes page-id objects)
       (seq paints)
       (cb/update-shapes (keys paints)
                         (fn [shape]
                           (reduce (fn [shape [attrs value]] (painted shape attrs value))
                                   shape
                                   (get paints (:id shape))))
                         quiet))
     (remove paint? valued))))

(defn- page-rebind
  "The changes that give every token-bound shape of a page the value its
  tokens have now → the changes, or `{:fallback}`. With `names`, only what
  is bound to those tokens."
  ([page-id objects active resolved names engine]
   (page-rebind page-id objects active resolved names nil engine))
  ([page-id objects active resolved names only engine]
   (page-rebind* page-id objects active resolved names only engine)))

(defn rebind-pages
  "After `made`, changes to the tokens ready to send: gives the shapes bound
  to a token their new value, on every page → `{:changes :shapes}`, the
  changes with the shapes' following and how many shapes that was, or
  `{:pending}`, or `{:fallback}`. With `names`, only what is bound to those
  tokens or to a token that refers to one."
  [data made names engine]
  (let [before (tst/active-tokens data)
        data   (cfc/process-changes data made false)
        active (or (tst/active-tokens data) {})
        ;; a token worth what it was worth before changes nothing on a shape:
        ;; a switch of theme that keeps the type sizes measures no text again
        same?  (fn [token-name]
                 (let [was (some->> (get before token-name) (resolved-value before))
                       now (resolved-value active (get active token-name))]
                   (and (some? was) (not= ::unresolved was) (= was now))))
        names  (into #{}
                     (remove same?)
                     (if (some? names) (dependents active names) (keys active)))
        bound? (fn [shape]
                 (some (fn [[_ token-name]] (contains? names token-name))
                       (:applied-tokens shape)))
        pages  (keep (fn [page-id]
                       (let [objects (get-in data [:pages-index page-id :objects])
                             shapes  (count (filter bound? (vals objects)))]
                         (when (pos? shapes)
                           {:page-id page-id :objects objects :shapes shapes})))
                     (:pages data))]
    (if (empty? pages)
      {:changes made :shapes 0}
      (let [{:keys [resolved pending]} (worth active engine)]
        (if (some? pending)
          {:pending pending}
          (let [out (reduce
                     (fn [made {:keys [page-id objects]}]
                       (let [changes (page-rebind page-id objects active resolved names engine)]
                         (if (contains? changes :fallback)
                           (reduced changes)
                           (caw/then-changes made (caw/redo changes)))))
                     made
                     pages)]
            (if (map? out)
              out
              {:changes (caw/compact out) :shapes (transduce (map :shapes) + pages)})))))))

(defn rebind-shapes
  "Gives the shapes `ids` of a page the values the file's tokens have now,
  whatever they held → `{:changes}`, `{:pending}`, `{:fallback}`, or nil
  when none of them is bound to a token the file has."
  [data page-id objects ids engine]
  (let [active (tst/active-tokens data)
        ids    (into #{}
                     (filter (fn [id] (some #(contains? active %) (vals (:applied-tokens (get objects id))))))
                     ids)]
    (when (seq ids)
      (let [{:keys [resolved pending]} (worth active engine)]
        (if (some? pending)
          {:pending pending}
          (let [changes (page-rebind page-id objects active resolved nil ids engine)]
            (if (contains? changes :fallback)
              changes
              {:changes (caw/compact (caw/redo changes)) :shapes (count ids)})))))))

(defn reapply-tokens
  "reapply_tokens: gives every shape bound to a token the value the token
  has now, on every page. For when values drifted from their tokens: after
  a library update wrote the library's values back on its copies."
  [{:keys [data fonts]} _ engine]
  (let [engine (assoc engine :fonts fonts)
        active (tst/active-tokens data)]
    (if (empty? active)
      {:problem "reapply_tokens: this file has no tokens that are on"}
      (let [{:keys [resolved pending]} (worth active engine)]
        (if (some? pending)
          {:pending pending}
          (let [out (reduce
                     (fn [{:keys [changes shapes]} page-id]
                       (let [objects (get-in data [:pages-index page-id :objects])
                             bound   (count (filter #(seq (:applied-tokens %)) (vals objects)))
                             page    (when (pos? bound) (page-rebind page-id objects active resolved nil engine))]
                         (cond
                           (nil? page)                (cond-> {:changes changes :shapes shapes})
                           (contains? page :fallback) (reduced page)
                           :else                      {:changes (caw/then-changes changes (caw/redo page))
                                                       :shapes (+ shapes bound)})))
                     {:changes (caw/redo (library-changes data)) :shapes 0}
                     (:pages data))]
            (if (contains? out :fallback)
              out
              {:changes (caw/compact (:changes out))
               :result {:restyled (:shapes out)
                        :note "every bound shape has its token's value again — verify with render_board"}})))))))

(defn- follow
  "The answer of a tool that wrote tokens with `made`. When it gave the
  tokens named `changed` another value, what is bound to them follows: a
  token's value changed on the server reaches no shape by itself."
  [data fonts made changed engine result]
  (if (empty? changed)
    {:changes made :result result}
    (let [out (rebind-pages data made changed (assoc engine :fonts fonts))]
      (if (contains? out :changes)
        {:changes (:changes out)
         :result (assoc result
                        :changed (vec changed)
                        :restyled (:shapes out)
                        :note (str "these tokens were there already and have their new value; every "
                                   "shape bound to them, or to a token that refers to them, was "
                                   "given it, in mains and copies alike — nothing needs re-applying. "
                                   "Verify with render_board."))}
        out))))

(defn activate-theme
  "Toggles a theme, then gives every shape bound to a token its new value, on
  every page."
  [{:keys [data fonts]} {:keys [name]} engine]
  (let [lib    (:tokens-lib data)
        themes (some->> lib
                        (ctob/get-themes)
                        (remove #(= ctob/hidden-theme-name (ctob/get-name %))))
        match  (first (filter #(= name (ctob/get-name %)) themes))]
    (if (nil? match)
      {:problem (dm/str "activate_theme: no theme named \"" name "\""
                        (when (seq themes)
                          (dm/str " — this file has: "
                                  (str/join ", " (map ctob/get-name themes))))
                        " (create one with create_token_theme)")}
      (let [toggle (-> (library-changes data)
                       (clt/generate-toggle-theme (tst/status data) lib (ctob/get-id match))
                       (caw/redo))
            out    (rebind-pages data toggle nil (assoc engine :fonts fonts))]
        (if (contains? out :changes)
          {:changes (:changes out)
           :result {:theme name
                    :note (str "theme toggled — shapes bound to a token NAME re-resolve "
                               "automatically, so nothing needs re-applying. Verify with "
                               "read_design.")}}
          out)))))
