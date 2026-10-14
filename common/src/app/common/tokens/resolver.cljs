;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.tokens.resolver
  "What design tokens are worth, resolved with StyleDictionary: references
  followed, arithmetic done, composite values taken apart.

  This is the workspace's resolver (`app.main.data.style-dictionary`) for
  hosts that are not the workspace: the same library, set up the same way,
  and the same reading of each token type's value. It leaves out what only a
  UI needs — translated error messages, interactive previews — and says why
  a token did not resolve with a plain keyword. The frontend's tests hold the
  two to the same answers.

  A host needs the npm packages `style-dictionary`,
  `@tokens-studio/sd-transforms` and `tinycolor2`."
  (:require
   ["@tokens-studio/sd-transforms" :as sd-transforms]
   ["style-dictionary$default" :as sd]
   ["tinycolor2$default" :as tinycolor]
   [app.common.files.tokens :as cfo]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [cuerdas.core :as str]
   [promesa.core :as p]))

;; --- StyleDictionary, set up as the workspace sets it up

(defonce ^:private style-dictionary
  (do
    (sd-transforms/register sd)
    (.registerTransformGroup sd #js {:name "penpot-resolver"
                                     :transforms
                                     ;; sd-transforms without the typography
                                     ;; shorthand: a typography stays a map
                                     (.concat (sd-transforms/getTransforms)
                                              #js ["ts/color/css/hexrgba"
                                                   "ts/color/modifiers"
                                                   "color/css"])})
    (.registerFormat sd #js {:name "penpot-resolver/json"
                             :format (fn [^js res]
                                       (.. res -dictionary -tokens))})
    sd))

(def ^:private config
  {:platforms {:json
               {:transformGroup "penpot-resolver"
                :files [{:format "penpot-resolver/json" :destination "penpot"}]}}
   :preprocessors ["tokens-studio"]
   :log {:verbosity "silent"
         :warnings "silent"
         :errors {:brokenReferences "console"}}})

;; --- Colors

(defn- valid-color
  "The parsed color when `value` is a CSS color, as the workspace judges it:
  a hex has to carry its #."
  [value]
  (let [^js tc (tinycolor value)]
    (when (and (.isValid tc)
               (.getFormat tc)
               (if (str/starts-with? (.getFormat tc) "hex")
                 (str/starts-with? (str (.getOriginalInput tc)) "#")
                 true))
      tc)))

(defn color
  "A color value as a shape holds it — `{:color \"#rrggbb\" :opacity n}` —
  or nil when it is not a color."
  [value]
  (when-let [^js tc (valid-color value)]
    {:color (.toHexString tc) :opacity (.getAlpha tc)}))

;; --- Reading a resolved value, type by type
;;
;; Each answers `{:value …}` (with `:unit` for numbers) or `{:error code}`.

(defn- references
  [value]
  (when (string? value)
    (seq (cto/find-token-value-references value))))

(defn- failed
  [value code]
  {:error (if (references value) :missing-reference code)})

(defn- out-of-bounds?
  [{:keys [value]}]
  (and (number? value)
       (or (>= value sm/max-safe-int) (<= value sm/min-safe-int))))

(defn- read-general
  [value]
  (let [parsed (cfo/parse-token-value value)]
    (cond
      (and parsed (not (out-of-bounds? parsed))) parsed
      (out-of-bounds? parsed)                    {:error :number-too-large}
      :else                                      (failed value :invalid-value))))

(defn- read-number
  [value]
  (let [parsed (cfo/parse-token-value value)]
    (cond
      (and parsed
           (not (out-of-bounds? parsed))
           (or (number? value)
               (and (string? value) (re-matches #"^-?\d+(\.\d+)?$" value))))
      parsed

      (out-of-bounds? parsed) {:error :number-too-large}
      :else                   (failed value :invalid-value))))

(defn- read-color
  [value]
  (if-let [^js tc (valid-color value)]
    ;; a color's unit is the notation it was written in
    {:value value :unit (.getFormat tc)}
    (failed value :invalid-color)))

(defn- read-opacity
  [value]
  (let [parsed (cfo/parse-token-value value)]
    (cond
      (and parsed (<= 0 (:value parsed) 1)) parsed
      (references value)                    {:error :missing-reference}
      :else                                 {:error :invalid-opacity})))

(defn- read-stroke-width
  [value]
  (let [parsed (cfo/parse-token-value value)]
    (cond
      (and parsed (>= (:value parsed) 0)) parsed
      (references value)                  {:error :missing-reference}
      :else                               {:error :invalid-stroke-width})))

(defn- read-letter-spacing
  [value]
  (let [parsed (read-general value)]
    (if (= "%" (:unit parsed))
      {:error :value-with-percent}
      parsed)))

(defn- read-text-case
  [value]
  (let [normalized (when (string? value) (str/lower (str/trim value)))]
    (if (contains? #{"none" "uppercase" "lowercase" "capitalize"} normalized)
      {:value normalized}
      (failed value :invalid-text-case))))

(defn- read-text-decoration
  [value]
  (if-let [valid (when (string? value) (cto/valid-text-decoration value))]
    {:value valid}
    (failed value :invalid-text-decoration)))

(defn- read-font-weight
  [value]
  (if (and (some? value) (cto/valid-font-weight-variant value))
    {:value value}
    (failed value :invalid-font-weight)))

(defn- read-font-family
  [value]
  (let [value (flatten (if (sequential? value) value [value]))]
    (cond
      (not (every? string? value))  {:error :invalid-font-family}
      (some references value)       {:error :missing-reference}
      :else                         {:value (vec value)})))

(defn- read-atomic-typography
  [token-type value]
  (case token-type
    :font-size       (read-general value)
    :font-family     (read-font-family value)
    :font-weight     (read-font-weight value)
    :letter-spacing  (read-letter-spacing value)
    :text-case       (read-text-case value)
    :text-decoration (read-text-decoration value)
    nil))

(defn- read-line-height
  "A composite typography's line height, as a multiple of its font size."
  [line-height font-size]
  (cond
    (references line-height)
    {:error :missing-reference}

    (not (number? font-size))
    {:error :line-height-needs-font-size}

    :else
    (let [{:keys [unit value]} (cfo/parse-token-value line-height)
          multiple (case unit
                     "%"  (/ value 100)
                     "px" (/ value font-size)
                     nil  value
                     nil)]
      (if (number? multiple)
        {:value multiple}
        {:error :invalid-value}))))

(defn- read-typography
  [value]
  (cond
    (references value) {:error :missing-reference}
    (not (map? value)) {:error :invalid-typography}
    :else
    (let [fields (reduce-kv
                  (fn [acc k v]
                    (let [{:keys [error value]} (or (read-atomic-typography k v) {:value v})]
                      (if (some? error)
                        (update acc :errors conj [k error])
                        (assoc-in acc [:value k] (if (some? value) value v)))))
                  {:value {} :errors []}
                  (dissoc value :line-height))
          fields (if-let [line-height (:line-height value)]
                   (let [{:keys [error value]} (read-line-height line-height (get-in fields [:value :font-size]))]
                     (if (some? error)
                       (update fields :errors conj [:line-height error])
                       (assoc-in fields [:value :line-height] value)))
                   fields)]
      (cond-> {:value (:value fields)}
        (seq (:errors fields)) (assoc :error :invalid-typography :errors (:errors fields))))))

(defn- read-shadow
  [value]
  (cond
    (references value)        {:error :missing-reference}
    (not (sequential? value)) {:error :invalid-shadow}
    :else
    (let [readers {:offset-x read-general
                   :offset-y read-general
                   :blur     #(let [parsed (read-general %)]
                                (if (and (:value parsed) (>= (:value parsed) 0)) parsed {:error :invalid-shadow-blur}))
                   :spread   read-general
                   :color    read-color
                   :inset    #(if (boolean? %) {:value %} (failed % :invalid-shadow-type))}
          shadows (mapv (fn [shadow]
                          (reduce-kv
                           (fn [acc k v]
                             (let [{:keys [error value]} ((get readers k) v)]
                               (if (some? error)
                                 (update acc :errors conj [k error])
                                 (assoc-in acc [:value k] (if (some? value) value v)))))
                           {:value {} :errors []}
                           (merge {:offset-x nil :offset-y nil :blur nil :spread nil :color nil :inset false}
                                  (select-keys shadow (keys readers)))))
                        value)]
      (cond-> {:value (mapv :value shadows)}
        (some (comp seq :errors) shadows) (assoc :error :invalid-shadow)))))

(defn- read-value
  [token-type value]
  (or (read-atomic-typography token-type value)
      (case token-type
        :typography   (read-typography value)
        :shadow       (read-shadow value)
        :color        (read-color value)
        :opacity      (read-opacity value)
        :stroke-width (read-stroke-width value)
        :number       (read-number value)
        (read-general value))))

;; --- Resolving

(defn- resolved-token
  [token value]
  (let [value (js->clj value :keywordize-keys true)
        {:keys [error unit] :as parsed} (read-value (:type token) value)]
    (cond-> {:name (:name token) :type (:type token)}
      (some? error)
      (assoc :error error)

      (nil? error)
      (assoc :resolved-value (:value parsed))

      (some? unit)
      (assoc :unit unit)

      ;; a color also as a shape holds it, so a host needs no color parser
      (and (nil? error) (= :color (:type token)))
      (assoc :color (color (:value parsed)))

      (and (nil? error) (= :shadow (:type token)))
      (update :resolved-value (fn [shadows] (mapv #(update % :color color) shadows))))))

(defn resolve-tokens
  "`tokens` is token name → token (`:name :type :value`), as the tokens of a
  library's active sets are. → promise of name → `{:name :type
  :resolved-value}`, with `:unit` for a number, `:color` for a color, or
  `:error` in place of the value when the token does not resolve."
  [tokens]
  (let [valid   (into {} (filter (fn [[_ token]] (some? (:value token)))) tokens)
        missing (into {}
                      (comp (remove (fn [[_ token]] (some? (:value token))))
                            (map (fn [[k token]] [k {:name (:name token) :type (:type token) :error :empty-value}])))
                      tokens)]
    (if (empty? valid)
      (p/resolved missing)
      (->> (-> (new style-dictionary (clj->js (assoc config :tokens (ctob/tokens-tree valid))))
               (.buildAllPlatforms "json")
               (p/then #(.getPlatformTokens ^js % "json"))
               (p/then #(.-allTokens ^js %)))
           (p/fmap (fn [sd-tokens]
                     (let [resolved (reduce
                                     (fn [acc ^js sd-token]
                                       ;; group nodes come along with the
                                       ;; tokens and have no token behind
                                       (if-let [token (get valid (some-> sd-token .-original .-name))]
                                         (assoc acc (:name token) (resolved-token token (.-value sd-token)))
                                         acc))
                                     missing
                                     sd-tokens)]
                       ;; a token whose name is a prefix of another's is
                       ;; dropped by the tree: it is there, and unresolved
                       (reduce-kv (fn [acc k token]
                                    (cond-> acc
                                      (not (contains? acc k))
                                      (assoc k {:name (:name token) :type (:type token) :error :name-collision})))
                                  resolved
                                  valid))))))))
