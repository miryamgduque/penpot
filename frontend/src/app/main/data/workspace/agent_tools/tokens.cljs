;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.tokens
  "Design-token tools: create/apply tokens, sets and themes."
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-tools.common :as atc]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
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

(defn existing-token-set-id
  "The id of a set already in the library, or nil when it genuinely has none.

  This exists because `dwtl/create-token`'s 1-arity resolves its target through
  `lookup-token-set`, which reads `[:workspace-tokens :selected-token-set-id]` —
  the *UI selection*, not the library. Nobody has opened the Tokens panel in an
  agent session, so that is nil, and the nil branch runs `create-token-with-set`,
  which builds a fresh \"Global\" set and replaces the existing one — silently
  wiping every token in it. The first authored token after any page load would
  destroy the file's token library.

  So the set is resolved from the library and passed explicitly; the
  set-creating branch is left for the case it is actually named for."
  [state]
  (some-> (dsh/lookup-file-data state)
          :tokens-lib
          (ctob/get-sets)
          (first)
          (ctob/get-id)))

(defn- set-names
  [state]
  (some->> (atc/tokens-lib state) (ctob/get-sets) (mapv ctob/get-name)))

(defn- set-id-by-name
  [state name]
  (let [want (normalize-set name)]
    (some->> (atc/tokens-lib state)
             (ctob/get-sets)
             (filter #(= want (ctob/get-name %)))
             (first)
             (ctob/get-id))))

(defn create-token
  ;; `:set` is destructured as `target-set`: binding it to `set` would shadow
  ;; clojure.core/set for the whole fn — which is exactly how token-set-problem
  ;; broke ((set xs) called a string).
  [{:keys [type name value] target-set :set :as input}]
  (let [state (deref st/state)]
    (if-let [problem (or (token-problem input)
                         (some->> (token-set-problem (set-names state) target-set)
                                  (dm/str "create_token: ")))]
      (rx/throw (ex-info problem {}))
      (let [set-id (if target-set
                     (set-id-by-name state target-set)
                     (existing-token-set-id state))
            token  (ctob/make-token {:type (token-type type) :name name :value value})]
        (st/emit! (if set-id
                    (dwtl/create-token set-id token)
                    ;; no sets at all: this is the branch that legitimately makes one
                    (dwtl/create-token token)))
        (rx/of (cond-> {:name name :type type :value value
                        :note "token created — bind it to shapes with apply_tokens"}
                 target-set (assoc :set (normalize-set target-set))))))))

(defn create-tokens
  "create_tokens: many tokens in ONE call — the NYT session spent 20 rounds on
  what this does in one. All-or-nothing: every entry validates (including its
  target set existing) before any token is created."
  [{:keys [tokens] default-set :set}]
  (let [state (deref st/state)
        names (set-names state)]
    (if-not (and (sequential? tokens) (seq tokens))
      (rx/throw (ex-info (str "create_tokens: pass tokens — an array of "
                              "{name, type, value, set?}; a top-level `set` is "
                              "the default for entries without one")
                         {}))
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
          (rx/throw (ex-info (str/join " | " problems) {}))
          (do
            (atc/interrupt!)
            (run! (fn [{:keys [type name value] target-set :set}]
                    (let [set-id (if target-set
                                   (set-id-by-name state target-set)
                                   (existing-token-set-id state))
                          token  (ctob/make-token {:type (token-type type)
                                                   :name name :value value})]
                      (st/emit! (if set-id
                                  (dwtl/create-token set-id token)
                                  (dwtl/create-token token)))))
                  entries)
            (rx/of {:created (count entries)
                    :note (str "tokens created — bind them to shapes with "
                               "apply_tokens; values referencing other tokens "
                               "(\"{color.blue.500}\") resolve lazily")})))))))

(defn create-token-set
  [{:keys [name]}]
  (let [state (deref st/state)]
    (if-let [problem (new-set-problem (set-names state) name)]
      (rx/throw (ex-info problem {}))
      (let [nm (normalize-set name)]
        (st/emit! (dwtl/create-token-set (ctob/make-token-set {:name nm})))
        (rx/of {:set nm
                :note (str "set created — aim tokens at it with create_token's `set` "
                           "param. A set is only live when an active theme enables "
                           "it: pair modes/* sets with a theme each.")})))))

(defn create-token-theme
  [{:keys [name group sets] :as input}]
  (let [state (deref st/state)]
    (if-let [problem (theme-problem (set-names state) input)]
      (rx/throw (ex-info problem {}))
      (let [theme (ctob/make-token-theme
                   (cond-> {:name name :sets (into #{} (map normalize-set) sets)}
                     group (assoc :group group)))]
        (st/emit! (dwtl/create-token-theme theme))
        (rx/of {:theme name
                :note (str "theme created — it is not active yet; activate_theme "
                           "switches which of its sets resolve")})))))

(defn activate-theme
  [{:keys [name]}]
  (let [state  (deref st/state)
        lib    (atc/tokens-lib state)
        themes (some->> lib (ctob/get-themes) (remove #(= ctob/hidden-theme-name (ctob/get-name %))))
        match  (first (filter #(= name (ctob/get-name %)) themes))]
    (cond
      (nil? match)
      (rx/throw (ex-info (dm/str "activate_theme: no theme named \"" name "\""
                                 (when (seq themes)
                                   (dm/str " — this file has: "
                                           (str/join ", " (map ctob/get-name themes))))
                                 " (create one with create_token_theme)")
                         {}))

      :else
      (do
        (st/emit! (dwtl/toggle-token-theme-active (ctob/get-id match)))
        (rx/of {:theme name
                :note (str "theme toggled — shapes bound to a token NAME re-resolve "
                           "automatically, so nothing needs re-applying. Verify with "
                           "read_design.")})))))

(defn sets-and-themes
  "The library's sets (with active flag) and themes — `read_design` flattens
  tokens into active sets only, so without this the agent cannot see that an
  inactive `modes/dark` exists at all."
  [state]
  (let [lib (atc/tokens-lib state)]
    (when lib
      (let [active (ctob/get-active-themes-set-names lib)
            themes (->> (ctob/get-themes lib)
                        (remove #(= ctob/hidden-theme-name (ctob/get-name %)))
                        (mapv (fn [t] (cond-> {:name (ctob/get-name t)
                                               :sets (vec (:sets t))}
                                        (ctob/theme-active? lib (ctob/get-id t))
                                        (assoc :active true)))))
            sets   (->> (ctob/get-sets lib)
                        (mapv (fn [s] (cond-> {:name (ctob/get-name s)}
                                        (contains? active (ctob/get-name s))
                                        (assoc :active true)))))]
        (cond-> {}
          (seq sets)   (assoc :sets sets)
          (seq themes) (assoc :themes themes))))))

;; --- Token application
;;
;; `dwta/toggle-token` takes an arbitrary `attrs` set and the valid universe is
;; `cto/all-keys` — the color-only ceiling was entirely this file's own helper,
;; which mapped every request onto :fill or :stroke-color.

(def ^:private attr-alias
  "The plugin API's semantic names for Penpot's positional attr keys, copied
  deliberately from `app.plugins.tokens`. This is the one place where taking the
  plugin's naming is right: we take its translation layer with it, and the skills
  already speak these names (`applyToken(t, [\"columnGap\"])`). `:p1` means
  nothing without this table."
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

(def ^:private public->attr
  (into {"stroke" :stroke-color} ;; legacy: the shipped spec offers "stroke"
        (map (fn [k] [(public-attr-name k) k]))
        token-attr-universe))

(defn token-attr
  "The internal attr keyword for a public name, or nil."
  [name]
  (get public->attr name))

(defn- type-attrs
  "The attrs a token of this type may bind to — the same source `toggle-token`
  itself consults (`dwta/token-properties`), so anything we accept, it accepts."
  [token-type]
  (let [{:keys [attributes all-attributes]} (get dwta/token-properties token-type)]
    (or all-attributes attributes)))

(defn application-problem
  "Why this token cannot bind to these properties, or nil. Pure."
  [token properties]
  (let [valid (type-attrs (:type token))]
    (some (fn [p]
            (if-let [k (token-attr p)]
              (when (and (seq valid) (not (contains? valid k)))
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
    (into #{} (keep token-attr) properties)
    (or (some-> (get dwta/token-properties (:type token)) :attributes) #{:fill})))

(defn apply-tokens
  [{:keys [applications]}]
  (if (empty? applications)
    (rx/throw (ex-info "apply_tokens: no applications given" {}))
    (let [all-tokens (some-> (dsh/lookup-file-data @st/state) :tokens-lib ctob/get-all-tokens-map)]
      (atc/interrupt!)
      (let [results
            (mapv (fn [{:keys [shapeId tokenName properties]}]
                    (let [id      (some-> shapeId parse-uuid)
                          token   (get all-tokens tokenName)
                          problem (when token (application-problem token properties))]
                      (if (and id token (nil? problem))
                        ;; apply-token-from-input, not toggle-token: this tool
                        ;; means "bind", and it must be idempotent. toggle-token
                        ;; UNBINDS a token already applied to the shape (its UI
                        ;; role), so a retry or a batch that re-asserts an
                        ;; existing binding would silently remove it while still
                        ;; reporting ok. apply-token-from-input shares the same
                        ;; spacing/on-update-shape resolution but only ever
                        ;; applies.
                        (do (st/emit! (dwta/apply-token-from-input
                                       {:token token
                                        :attrs (attr-set token properties)
                                        :shape-ids [id]
                                        :expand-with-children false}))
                            {:shapeId shapeId :token tokenName :ok true})
                        {:shapeId shapeId :token tokenName :ok false
                         :error (cond
                                  (nil? id) "invalid shapeId"
                                  (nil? token) (dm/str "no token named \"" tokenName
                                                       "\" — create it with create_token")
                                  :else problem)})))
                  applications)]
        (rx/of {:results results
                :note "tokens resolve asynchronously — verify with read_design or audit_file"})))))
