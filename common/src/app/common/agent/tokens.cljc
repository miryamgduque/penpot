;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.tokens
  "The design agent's token tools as pure functions; see
  `app.common.agent.write` for what a tool takes and answers."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
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

(defn- library-changes
  [data]
  (-> (cb/empty-changes)
      (cb/with-library-data data)))

(defn- add-first-set
  "A library with no set gets one, enabled by the hidden theme, so a token
  authored into it is live. → `[changes set-id]`."
  [changes]
  (let [token-set (ctob/make-token-set :name "Global")
        theme     (-> (ctob/make-hidden-theme)
                      (ctob/enable-set "Global"))]
    [(-> changes
         (cb/set-token-set (ctob/get-id token-set) token-set)
         (cb/set-token-theme (ctob/get-id theme) theme)
         (cb/set-active-token-themes #{ctob/hidden-theme-path}))
     (ctob/get-id token-set)]))

(defn- add-token
  [changes set-id {:keys [type name value]}]
  (let [token (ctob/make-token {:type (token-type type) :name name :value value})]
    (cb/set-token changes set-id (:id token) token)))

;; --- create_token / create_tokens

(defn create-token
  ;; `:set` is destructured as `target-set`: binding it to `set` would shadow
  ;; clojure.core/set for the whole fn
  [{:keys [data]} {:keys [type name value] target-set :set :as input} _]
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
        {:changes (:redo-changes (add-token changes set-id input))
         :result (cond-> {:name name :type type :value value
                          :note "token created — bind it to shapes with apply_tokens"}
                   target-set (assoc :set (normalize-set target-set)))}))))

(defn create-tokens
  "Many tokens in ONE call. All-or-nothing: every entry validates (including
  its target set existing) before any token is created."
  [{:keys [data]} {:keys [tokens] default-set :set} _]
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
                    [(library-changes data) nil]))]
            {:changes (:redo-changes
                       (reduce (fn [changes {target-set :set :as entry}]
                                 (add-token changes
                                            (if target-set (set-id-by-name lib target-set) default-id)
                                            entry))
                               changes
                               entries))
             :result {:created (count entries)
                      :note (str "tokens created — bind them to shapes with "
                                 "apply_tokens; values referencing other tokens "
                                 "(\"{color.blue.500}\") resolve lazily")}}))))))

;; --- create_token_set / create_token_theme

(defn create-token-set
  [{:keys [data]} {:keys [name]} _]
  (if-let [problem (new-set-problem (set-names (:tokens-lib data)) name)]
    {:problem problem}
    (let [nm        (normalize-set name)
          token-set (ctob/make-token-set {:name nm})]
      {:changes (-> (library-changes data)
                    (cb/set-token-set (ctob/get-id token-set) token-set)
                    :redo-changes)
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
                      :redo-changes)
         :result {:theme name
                  :note (str "theme created — it is not active yet; activate_theme "
                             "switches which of its sets resolve")}}))))
