;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.pack
  "The token pack format: what a pack is, and why a given one is not valid.

  A pack is a design system as plain data — maps, vectors, strings, numbers
  and booleans, the same once written as JSON. It gives a value to every
  token role a recipe may name (`app.common.agent.recipe/roles`), so any pack
  styles any recipe, and it holds no shapes: recipes build the components.

    {:pack    1                     the format version
     :name    \"neutral\"             its id, kebab-case
     :title   \"Neutral\"
     :purpose \"…\"                   what it is good for, in a sentence
     :fonts   [{:family \"Inter\"}]   optional, the families its type needs
     :sets    [set …]               the token sets
     :themes  [theme …]             which sets are on together
     :default \"Light\"}              the theme that is on once applied

  A set is `{:name \"modes/light\" :tokens {name value}}`. A token called as a
  role has that role's type and is written as its value alone:

    \"color.primary\"    \"#2563eb\"
    \"spacing.md\"       12
    \"font-family.body\" \"Inter, sans-serif\"
    \"shadow.md\"        [{:offsetX 0 :offsetY 4 :blur 12 :spread 0
                         :color \"rgba(0, 0, 0, 0.12)\"}]

  Any other token says its type: `\"blue.600\" {:type \"color\" :value
  \"#2563eb\"}`. A value may be another token of the pack in braces,
  `\"{blue.600}\"`.

  A theme is `{:name \"Dark\" :group \"Mode\" :sets [\"core\" \"modes/dark\"]}`:
  the sets that are on with it, later ones over earlier ones. Every theme
  fills every role, so switching theme never leaves a recipe without a
  value.

  Nothing here says where a pack comes from or how it reaches a file."
  (:require
   [app.common.agent.recipe :as car]
   [app.common.agent.tokens :as cato]
   [app.common.data.macros :as dm]
   [app.common.files.tokens :as cfo]
   [app.common.types.token :as cto]
   [cuerdas.core :as str]))

(def version 1)

(def max-sets 12)

(def max-themes 8)

(def max-tokens 400)

(def ^:private name-re #"^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

(def ^:private set-name-re #"^[A-Za-z0-9][A-Za-z0-9 _.-]*(/[A-Za-z0-9][A-Za-z0-9 _.-]*)*$")

(def ^:private token-name-re #"^[A-Za-z][A-Za-z0-9_-]*(\.[A-Za-z0-9][A-Za-z0-9_-]*)*$")

(def ^:private reference-re #"^\s*\{([^{}]+)\}\s*$")

(defn- text?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- listed
  [names]
  (str/join ", " (sort (map name names))))

(defn- key-problem
  [where m allowed]
  (when-let [k (first (remove allowed (keys m)))]
    (dm/str where " has \"" (name k) "\", which is not part of the format — it takes: "
            (listed allowed))))

(defn- key-name
  "A token's name, whether it came as a string or, from JSON, as a keyword."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn set-tokens
  "The tokens of a set → `{name {:type :value}}`, the type being the token
  type keyword of the library."
  [token-set]
  (into {}
        (map (fn [[k entry]]
               (let [token-name (key-name k)
                     role       (get car/roles token-name)]
                 [token-name
                  (cond
                    (some? role) {:type (:type role) :value entry}
                    (map? entry) {:type (cato/token-type (:type entry)) :value (:value entry)}
                    :else        {:type nil :value entry})])))
        (:tokens token-set)))

(defn- reference
  [value]
  (when (string? value)
    (second (re-matches reference-re value))))

;; --- Values
;;
;; Each answers nil, or what is wrong with the value.

(defn- number-like?
  [value]
  (or (number? value)
      (and (string? value) (number? (:value (cfo/parse-token-value value))))))

(defn- color?
  [value]
  (and (string? value)
       ;; longest first, and as one group: a JavaScript match stops at the first
       ;; alternative that fits, which for "#ffffff" would be "#fff"
       (some? (re-matches #"(?i)^(?:#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3})|rgba?\([\d\s.,]+\))$" (str/trim value)))))

(defn- value-problem
  [type value]
  (cond
    (some? (reference value))
    nil

    (= :color type)
    (when-not (color? value)
      "is not a color: write \"#rrggbb\" or \"rgba(r, g, b, a)\"")

    (= :font-family type)
    (when-not (text? value)
      "is not a font family: write its name, e.g. \"Inter, sans-serif\"")

    (= :font-weight type)
    (when-not (some? (cto/valid-font-weight-variant (dm/str value)))
      "is not a font weight: write 400, 500, 700 or a name like \"bold\"")

    (= :shadow type)
    (when (nil? (cato/shadow-value value))
      (dm/str "is not a shadow: write a list of {offsetX, offsetY, blur, spread, color}"
              " with numbers and a color, blur not negative"))

    (contains? #{:spacing :border-radius :stroke-width :font-size :sizing :dimensions
                 :opacity :number :letter-spacing :rotation} type)
    (when-not (number-like? value)
      "is not a number")

    :else
    (when-not (or (text? value) (number? value))
      "is not a value")))

(defn- token-problem
  [where token-name entry]
  (let [role (get car/roles token-name)]
    (cond
      (nil? (re-matches token-name-re token-name))
      (dm/str where " has a token called \"" token-name "\": a name is words joined by dots,"
              " like color.primary")

      (some? role)
      (if (map? entry)
        (dm/str where "." token-name " is a role, so it is written as its value alone, not as a map")
        (some->> (value-problem (:type role) entry)
                 (dm/str where "." token-name " ")))

      (not (map? entry))
      (dm/str where "." token-name " is not a role, so it says its type: {type, value}."
              " Roles: " (listed (keys car/roles)))

      :else
      (or (key-problem (dm/str where "." token-name) entry #{:type :value})
          (let [type (cato/token-type (:type entry))]
            (cond
              (nil? type)
              (dm/str where "." token-name " has the type \"" (:type entry)
                      "\", which is not a token type")

              (= :typography type)
              (dm/str where "." token-name " is a typography token: a pack writes type as"
                      " its parts (font-family, font-size, font-weight)")

              (not (contains? entry :value))
              (dm/str where "." token-name " has no value")

              :else
              (some->> (value-problem type (:value entry))
                       (dm/str where "." token-name " "))))))))

(defn- set-problem
  [index token-set]
  (let [where (dm/str "sets[" index "]")]
    (cond
      (not (map? token-set))
      (dm/str where " is not a set: {name, tokens}")

      :else
      (or (key-problem where token-set #{:name :tokens})
          (cond
            (not (and (string? (:name token-set)) (re-matches set-name-re (:name token-set))))
            (dm/str where " needs a `name`, like \"core\" or \"modes/dark\"")

            (not (and (map? (:tokens token-set)) (seq (:tokens token-set))))
            (dm/str where " (" (:name token-set) ") has no tokens")

            :else
            (some (fn [[k entry]] (token-problem (:name token-set) (key-name k) entry))
                  (sort-by (comp key-name key) (:tokens token-set))))))))

(defn- sets-problem
  [sets]
  (cond
    (not (and (vector? sets) (seq sets)))
    "needs `sets`: a list of {name, tokens}"

    (< max-sets (count sets))
    (dm/str "has " (count sets) " sets; a pack holds " max-sets " at most")

    :else
    (or (first (keep-indexed set-problem sets))
        (let [names (map :name sets)]
          (when-let [twice (first (keep (fn [[nm n]] (when (< 1 n) nm)) (frequencies names)))]
            (dm/str "has two sets called \"" twice "\"")))
        (let [total (transduce (map #(count (:tokens %))) + sets)]
          (when (< max-tokens total)
            (dm/str "has " total " tokens; a pack holds " max-tokens " at most"))))))

;; --- Themes

(defn theme-tokens
  "The tokens that are on with `theme` → `{name {:type :value}}`: its sets in
  order, later ones over earlier ones."
  [pack theme]
  (let [by-name (into {} (map (juxt :name identity)) (:sets pack))]
    (transduce (keep #(some-> (get by-name %) set-tokens)) merge {} (:sets theme))))

(defn- reference-problem
  "A reference that leads nowhere, or back to itself, among `tokens`."
  [tokens]
  (some (fn [[token-name {:keys [value]}]]
          (loop [value value
                 seen  #{token-name}]
            (when-let [target (reference value)]
              (cond
                (not (contains? tokens target))
                (dm/str "\"" token-name "\" refers to \"" target "\", which it does not hold")

                (contains? seen target)
                (dm/str "\"" token-name "\" refers back to itself through \"" target "\"")

                :else
                (recur (:value (get tokens target)) (conj seen target))))))
        (sort-by key tokens)))

(defn- theme-problem
  [pack index theme]
  (let [where (dm/str "themes[" index "]")
        sets  (into #{} (map :name) (:sets pack))]
    (cond
      (not (map? theme))
      (dm/str where " is not a theme: {name, sets}")

      :else
      (or (key-problem where theme #{:name :group :sets})
          (cond
            (not (text? (:name theme)))
            (dm/str where " needs a `name`, like \"Light\"")

            (not (or (nil? (:group theme)) (text? (:group theme))))
            (dm/str where " (" (:name theme) ") has a group that is not a name")

            (not (and (vector? (:sets theme)) (seq (:sets theme)) (every? string? (:sets theme))))
            (dm/str where " (" (:name theme) ") needs `sets`: the names of the sets that are on with it")

            :else
            (or (when-let [missing (first (remove sets (:sets theme)))]
                  (dm/str "theme \"" (:name theme) "\" turns on the set \"" missing
                          "\", which the pack does not have — it has: " (listed sets)))
                (let [tokens (theme-tokens pack theme)]
                  (or (when-let [unfilled (seq (remove #(contains? tokens %) (sort (keys car/roles))))]
                        (dm/str "theme \"" (:name theme) "\" leaves " (count unfilled)
                                " role(s) without a value: " (str/join ", " unfilled)
                                " — every theme fills every role"))
                      (some->> (reference-problem tokens)
                               (dm/str "in theme \"" (:name theme) "\", "))))))))))

(defn- themes-problem
  [pack]
  (let [themes (:themes pack)]
    (cond
      (not (and (vector? themes) (seq themes)))
      "needs `themes`: a list of {name, sets}, at least one"

      (< max-themes (count themes))
      (dm/str "has " (count themes) " themes; a pack holds " max-themes " at most")

      :else
      (or (first (keep-indexed (partial theme-problem pack) themes))
          (when-let [twice (first (keep (fn [[nm n]] (when (< 1 n) nm)) (frequencies (map :name themes))))]
            (dm/str "has two themes called \"" twice "\""))
          (when-let [unused (first (remove (into #{} (mapcat :sets) themes) (map :name (:sets pack))))]
            (dm/str "has the set \"" unused "\", which no theme turns on"))
          (cond
            (not (text? (:default pack)))
            (dm/str "needs a `default`: the theme that is on once applied — one of: "
                    (str/join ", " (map :name themes)))

            (not (some #(= (:default pack) (:name %)) themes))
            (dm/str "has the default \"" (:default pack) "\", which is not one of its themes: "
                    (str/join ", " (map :name themes))))))))

(defn- fonts-problem
  [fonts]
  (cond
    (nil? fonts)
    nil

    (not (vector? fonts))
    "fonts is not a list of {family}"

    :else
    (first (keep-indexed
            (fn [index font]
              (cond
                (not (map? font))
                (dm/str "fonts[" index "] is not a font: {family}")

                (not (text? (:family font)))
                (dm/str "fonts[" index "] needs a `family`, like \"Inter\"")

                :else
                (key-problem (dm/str "fonts[" index "]") font #{:family})))
            fonts))))

(defn- data?
  "Whether `value` is plain data, all the way down."
  [value]
  (cond
    (map? value)        (every? (fn [[k v]] (and (or (keyword? k) (string? k)) (data? v))) value)
    (sequential? value) (every? data? value)
    :else               (or (string? value) (number? value) (boolean? value) (nil? value))))

(def ^:private pack-keys
  #{:pack :name :title :purpose :fonts :sets :themes :default})

(defn problem
  "Why `pack` is not a valid pack — a sentence that names what is wrong and
  where — or nil."
  [pack]
  (cond
    (not (map? pack))
    "a pack is a map: {pack, name, title, purpose, sets, themes, default}"

    (not (data? pack))
    "a pack is data only: maps, lists, strings, numbers, true and false"

    :else
    (or (key-problem "the pack" pack pack-keys)
        (cond
          (not= version (:pack pack))
          (dm/str "needs `pack`: " version ", the version of the format")

          (not (and (string? (:name pack)) (re-matches name-re (:name pack))))
          "needs a `name` in kebab-case, like \"neutral\""

          (not (text? (:title pack)))
          "needs a `title`"

          (not (text? (:purpose pack)))
          "needs a `purpose`: what it is good for, in a sentence"

          :else
          (or (fonts-problem (:fonts pack))
              (sets-problem (:sets pack))
              (themes-problem pack))))))

;; --- What a theme is worth
;;
;; Read without the token library: a pack is judged before it reaches a file.

(defn- followed
  [tokens value]
  (loop [value value
         left  16]
    (let [target (reference value)]
      (if (and (some? target) (pos? left))
        (recur (:value (get tokens target)) (dec left))
        value))))

(defn theme-values
  "Role → the plain value it has with `theme`, references followed."
  [pack theme]
  (let [tokens (theme-tokens pack theme)]
    (into {}
          (keep (fn [role]
                  (when-let [token (get tokens role)]
                    [role (followed tokens (:value token))])))
          (keys car/roles))))

;; --- Contrast
;;
;; The pairs of text and ground the recipes put together. A pack whose pairs
;; cannot be read is refused before it styles anything.

(def contrast-pairs
  "`[text ground]`, each to be told apart at WCAG AA for text (4.5 to 1)."
  [["color.text" "color.background"]
   ["color.text" "color.surface"]
   ["color.text" "color.surface-muted"]
   ["color.text-muted" "color.background"]
   ["color.text-muted" "color.surface"]
   ["color.primary" "color.background"]
   ["color.primary" "color.surface"]
   ["color.on-primary" "color.primary"]
   ["color.on-primary" "color.danger"]
   ["color.on-primary" "color.success"]
   ["color.on-primary" "color.warning"]
   ["color.on-primary" "color.info"]
   ["color.danger" "color.background"]
   ["color.success" "color.background"]
   ["color.background" "color.text"]])

(def min-contrast 4.5)

(defn- channels
  "`[r g b]`, each 0–1, of a color a pack may write; nil when it is not one,
  or is not opaque enough to judge."
  [color]
  (when (string? color)
    (let [color (str/trim color)
          hex   (fn [s] #?(:clj (Integer/parseInt s 16) :cljs (js/parseInt s 16)))]
      (or (when-let [[_ r g b] (re-matches #"(?i)#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})(?:ff)?" color)]
            (mapv #(/ (hex %) 255.0) [r g b]))
          (when-let [[_ r g b] (re-matches #"(?i)#([0-9a-f])([0-9a-f])([0-9a-f])" color)]
            (mapv #(/ (hex (dm/str % %)) 255.0) [r g b]))
          (when-let [[_ r g b] (re-matches #"(?i)rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*1(?:\.0+)?\s*)?\)" color)]
            (mapv #(/ (parse-long %) 255.0) [r g b]))))))

(defn- luminance
  [color]
  (when-let [rgb (channels color)]
    (let [linear (fn [c]
                   (if (<= c 0.03928)
                     (/ c 12.92)
                     (#?(:clj Math/pow :cljs js/Math.pow) (/ (+ c 0.055) 1.055) 2.4)))
          [r g b] (map linear rgb)]
      (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b)))))

(defn contrast
  "The WCAG contrast ratio of two colors, 1 to 21, or nil when one of them
  is not an opaque color."
  [one other]
  (let [a (luminance one)
        b (luminance other)]
    (when (and a b)
      (/ (+ (max a b) 0.05) (+ (min a b) 0.05)))))

(defn contrast-problems
  "The pairs of text and ground that cannot be read in some theme of a valid
  `pack` → `[{:theme :text :ground :ratio}]`, empty when all can."
  [pack]
  (into []
        (mapcat (fn [theme]
                  (let [values (theme-values pack theme)]
                    (keep (fn [[text ground]]
                            (let [ratio (contrast (get values text) (get values ground))]
                              (when (or (nil? ratio) (< ratio min-contrast))
                                {:theme (:name theme) :text text :ground ground
                                 :ratio (some-> ratio (* 100) (+ 0.5) (int) (/ 100.0))})))
                          contrast-pairs))))
        (:themes pack)))
