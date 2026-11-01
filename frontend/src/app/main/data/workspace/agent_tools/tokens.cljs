;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.tokens
  "Design-token tools: create/apply tokens, sets and themes."
  (:require
   [app.common.agent.read :as car]
   [app.common.agent.tokens :as cato]
   [app.common.data.macros :as dm]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-tools.common :as atc]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

;; --- Shared with the server
;;
;; The pure halves of the token tools live in `app.common.agent.tokens`,
;; shared with the server-side tools. They keep their names here.

(def token-type-names cato/token-type-names)

(def token-type cato/token-type)

(def token-problem cato/token-problem)

(def normalize-set cato/normalize-set)

(def token-set-problem cato/token-set-problem)

(def new-set-problem cato/new-set-problem)

(def theme-problem cato/theme-problem)

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

(defn- write-token!
  "Writes a token into the set. A token of that name already there is
  changed, as on the server (`app.common.agent.tokens`): it keeps its id and
  takes the new value. → whether a token that was there changed."
  [state set-id {:keys [type name value]}]
  (let [kind  (token-type type)
        value (cato/stored-value kind value)
        prev  (when set-id
                (some-> (atc/tokens-lib state) (ctob/get-tokens set-id) (get name)))]
    (cond
      (nil? prev)
      (do (st/emit! (if set-id
                      (dwtl/create-token set-id (ctob/make-token {:type kind :name name :value value}))
                      ;; no sets at all: this is the branch that legitimately makes one
                      (dwtl/create-token (ctob/make-token {:type kind :name name :value value}))))
          false)

      (and (= kind (:type prev)) (= value (:value prev)))
      false

      :else
      (do (st/emit! (dwtl/update-token set-id (:id prev) {:type kind :value value}))
          true))))

(def ^:private changed-note
  (str "these tokens were there already and have their new value; every shape "
       "bound to them, or to a token that refers to them, follows — nothing "
       "needs re-applying. Verify with render_board."))

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
            changed? (write-token! state set-id input)]
        (when changed?
          (st/emit! (dwtp/propagate-workspace-tokens)))
        (rx/of (cond-> {:name name :type type :value value
                        :note "token created — bind it to shapes with apply_tokens"}
                 changed?   (assoc :changed [name] :note changed-note)
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
            (let [changed (into []
                                (keep (fn [{target-set :set :as entry}]
                                        (when (write-token! state
                                                            (if target-set
                                                              (set-id-by-name state target-set)
                                                              (existing-token-set-id state))
                                                            entry)
                                          (:name entry))))
                                entries)]
              (when (seq changed)
                (st/emit! (dwtp/propagate-workspace-tokens)))
              (rx/of (cond-> {:created (count entries)
                              :note (str "tokens created — bind them to shapes with "
                                         "apply_tokens; values referencing other tokens "
                                         "(\"{color.blue.500}\") resolve lazily")}
                       (seq changed) (assoc :changed changed :note changed-note))))))))))

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
  "The library's sets (with active flag) and themes; see
  `app.common.agent.read/sets-and-themes`."
  [state]
  (car/sets-and-themes (dsh/lookup-file-data state)))

;; --- Token application
;;
;; `dwta/toggle-token` takes an arbitrary `attrs` set and the valid universe is
;; `cto/all-keys` — the color-only ceiling was entirely this file's own helper,
;; which mapped every request onto :fill or :stroke-color.

(def token-attr-universe cato/token-attr-universe)
(def public-attr-name cato/public-attr-name)
(def token-attr cato/token-attr)
(def application-problem cato/application-problem)
(def attr-set cato/attr-set)

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
