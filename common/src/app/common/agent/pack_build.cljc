;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.pack-build
  "Token packs as the agent's tools: which there are, one made from a few
  choices, and one applied to the file. See `app.common.agent.pack` for the
  format."
  (:require
   [app.common.agent.media :as cam]
   [app.common.agent.pack :as cak]
   [app.common.agent.pack-make :as cakm]
   [app.common.agent.packs :as caks]
   [app.common.agent.recipe :as car]
   [app.common.agent.tokens :as cato]
   [app.common.agent.write :as caw]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.json :as json]
   [app.common.types.tokens-lib :as ctob]
   [cuerdas.core :as str]))

;; --- What a pack is like, in a few lines

(def ^:private shown
  "The roles that tell one pack from another at a glance."
  ["color.background" "color.surface" "color.text" "color.primary" "color.on-primary"
   "font-family.heading" "font-family.body" "radius.md" "border-width.sm" "spacing.lg"])

(defn- first-family
  [value]
  (if (string? value) (str/trim (first (str/split value #","))) value))

(defn summary
  "A pack in a few lines: what it is for and, theme by theme, the values that
  give it its look."
  [pack]
  {:name (:name pack)
   :title (:title pack)
   :purpose (:purpose pack)
   :fonts (mapv :family (:fonts pack))
   :default (:default pack)
   :themes (into {}
                 (map (fn [theme]
                        (let [values (cak/theme-values pack theme)]
                          [(:name theme)
                           (into {}
                                 (map (fn [role]
                                        (let [value (get values role)]
                                          [role (if (str/starts-with? role "font-family.") (first-family value) value)])))
                                 shown)])))
                 (:themes pack))})

(defn- file-state
  "What the file has to build on already."
  [{:keys [file-id data libraries]}]
  (let [lib    (:tokens-lib data)
        sets   (some->> lib ctob/get-sets (mapv ctob/get-name))
        themes (some->> lib
                        ctob/get-themes
                        (remove #(= ctob/hidden-theme-name (ctob/get-name %)))
                        (mapv ctob/get-name))
        tokens (count (some-> lib ctob/get-all-tokens-map))
        linked (count (remove #(= file-id %) (keys libraries)))]
    {:tokens tokens
     :sets (or sets [])
     :themes (or themes [])
     :linkedLibraries linked
     :hasSystem (or (pos? tokens) (pos? linked))}))

(defn list-packs
  "list_token_packs: the starter packs, and what the file has already."
  [view _ _]
  {:result {:packs (mapv summary caks/all)
            :file (file-state view)
            :note (str "Apply one with apply_token_pack {pack: \"<name>\"}, or make one that fits "
                       "the project with generate_token_pack. A pack is tokens only: recipes "
                       "build the components, bound to its roles.")}})

;; --- Reading the pack a call names

(defn- written-out
  "A pack that arrived as text: a model asked for \"a name or a whole pack\"
  often sends the whole pack as a string of JSON."
  [pack]
  (if (and (string? pack) (str/starts-with? (str/trim pack) "{"))
    (try
      (let [parsed (json/decode pack :key-fn keyword)]
        (if (map? parsed) parsed pack))
      (catch #?(:clj Throwable :cljs :default) _ pack))
    pack))

(defn- library-pack
  "The tokens of a linked library, as a pack to apply: its sets with their
  tokens as they are, its themes, and the theme that is on in it. A library
  with no themes gives one, of the sets that are on."
  [library]
  (let [lib    (-> library :data :tokens-lib)
        sets   (mapv (fn [token-set]
                       {:name (ctob/get-name token-set)
                        :typed (into {}
                                     (map (fn [[token-name token]]
                                            [token-name {:type (:type token) :value (:value token)}]))
                                     (ctob/get-tokens lib (ctob/get-id token-set)))})
                     (ctob/get-sets lib))
        themes (->> (ctob/get-themes lib)
                    (remove #(= ctob/hidden-theme-name (ctob/get-name %)))
                    (mapv (fn [theme] {:name (ctob/get-name theme)
                                       :group (:group theme)
                                       :sets (vec (:sets theme))
                                       :on (ctob/theme-active? lib (ctob/get-id theme))})))
        themes (if (seq themes)
                 themes
                 [{:name "Default" :group "" :sets (vec (ctob/get-active-themes-set-names lib)) :on true}])]
    {:name (:name library)
     :from-library true
     :sets sets
     :themes themes
     :default (:name (or (first (filter :on themes)) (first themes)))}))

(defn- library-of
  "→ `{:pack}` or `{:problem}`: the tokens of the linked library `fileId`."
  [tool {:keys [file-id libraries]} fileId]
  (let [id      (when (string? fileId) (parse-uuid fileId))
        library (get libraries id)]
    (cond
      (or (nil? id) (= id file-id) (nil? library))
      {:problem (dm/str tool ": `library` is the fileId of a library LINKED to this file — "
                        "list_shared_libraries says which are, link_library links one")}

      (zero? (count (some-> library :data :tokens-lib ctob/get-all-tokens-map)))
      {:problem (dm/str tool ": the library \"" (:name library) "\" has no tokens to take")}

      :else
      {:pack (library-pack library)})))

(defn- pack-of
  "→ `{:pack}` or `{:problem}`: a starter by name, a pack written out, or
  the one `choices` make (as generate_token_pack would)."
  [tool pack choices]
  (cond
    (and (nil? pack) (map? choices))
    (if-let [problem (cakm/choices-problem choices)]
      {:problem (dm/str tool ": choices " problem)}
      (pack-of tool (cakm/make choices) nil))

    (not= pack (written-out pack))
    (pack-of tool (written-out pack) nil)

    (string? pack)
    (if-let [found (get caks/builtin pack)]
      {:pack found}
      {:problem (dm/str tool ": no starter pack is called \"" pack "\" — there are: "
                        (str/join ", " (map :name caks/all))
                        ". Pass a whole pack (as generate_token_pack answers one) for any other.")})

    (map? pack)
    (if-let [problem (cak/problem pack)]
      {:problem (dm/str tool ": the pack " problem)}
      (if-let [unread (seq (cak/contrast-problems pack))]
        {:problem (dm/str tool ": the pack has text that cannot be read — "
                          (str/join "; "
                                    (map (fn [{:keys [theme text ground ratio]}]
                                           (dm/str text " on " ground " in " theme " is "
                                                   (if ratio (dm/str ratio " to 1") "not an opaque color")))
                                         (take 6 unread)))
                          ". Each pair needs " cak/min-contrast " to 1.")}
        {:pack pack}))

    :else
    {:problem (dm/str tool ": pass `pack`: the name of a starter (" (str/join ", " (map :name caks/all))
                      ") or a whole pack — or `choices`: what you gave generate_token_pack")}))

;; --- Making one

(defn generate-pack
  "generate_token_pack: a whole pack from a few choices. It changes nothing:
  what it answers is for the user to see, and for apply_token_pack to take."
  [_ input _]
  (if-let [problem (cakm/choices-problem input)]
    {:problem (dm/str "generate_token_pack: " problem)}
    (let [pack    (cakm/make input)
          problem (or (cak/problem pack)
                      (when-let [unread (first (cak/contrast-problems pack))]
                        (dm/str (:text unread) " on " (:ground unread) " in " (:theme unread)
                                " cannot be read")))]
      (if (some? problem)
        {:problem (dm/str "generate_token_pack: these choices do not make a pack — " problem
                          ". Try another primary color.")}
        {:result {:pack pack
                  :summary (summary pack)
                  :note (str "NOTHING is applied yet. Show the user what this is — the primary, the "
                             "grounds and the text of both themes, the fonts, the corners — and, once "
                             "they agree, call apply_token_pack with `choices`: the same input you "
                             "gave here (it makes the same pack; no need to send it back). The brand color "
                             "may have been moved a little so text on it can be read: say so if the "
                             "summary's primary is not the one you gave.")}}))))

;; --- Applying

(defn- written
  "A pack's value as the token library takes it."
  [type value]
  (cato/stored-value type (if (number? value) (cato/number-text (double value)) value)))

(defn- remove-all
  [changes lib]
  (as-> changes $
    (reduce (fn [changes theme] (cb/set-token-theme changes (ctob/get-id theme) nil))
            $
            (remove #(= ctob/hidden-theme-name (ctob/get-name %)) (ctob/get-themes lib)))
    (reduce (fn [changes token-set] (cb/set-token-set changes (ctob/get-id token-set) nil))
            $
            (ctob/get-sets lib))))

(defn- add-sets
  "The pack's sets and tokens. A set of that name already there is kept and
  takes the pack's tokens, a token of that name in it the pack's value."
  [changes lib pack]
  (reduce
   (fn [changes token-set]
     (let [found  (some #(when (= (:name token-set) (ctob/get-name %)) %) (some-> lib ctob/get-sets))
           made   (when (nil? found) (ctob/make-token-set {:name (:name token-set)}))
           set-id (ctob/get-id (or found made))
           held   (if found (ctob/get-tokens lib set-id) {})]
       (reduce (fn [changes [token-name {:keys [type value]}]]
                 ;; a library's tokens come typed and stored already
                 (let [prev  (get held token-name)
                       token (ctob/make-token (cond-> {:type type :name token-name :value (written type value)}
                                                (some? prev) (assoc :id (:id prev))))]
                   (cb/set-token changes set-id (:id token) token)))
               (cond-> changes (some? made) (cb/set-token-set set-id made))
               (sort-by key (or (:typed token-set) (cak/set-tokens token-set))))))
   changes
   (:sets pack)))

(defn- add-themes
  "The pack's themes, each with `kept`, the sets that stay on whatever the
  theme. → `[changes path-of-the-theme-to-turn-on]`."
  [changes lib pack kept on]
  (reduce
   (fn [[changes path] {:keys [name group sets]}]
     (let [group (or group "")
           found (some #(when (and (= name (ctob/get-name %)) (= group (:group %))) %) (some-> lib ctob/get-themes))
           theme (ctob/make-token-theme (cond-> {:name name :group group :sets (into (set kept) sets)}
                                          (some? found) (assoc :id (ctob/get-id found))))]
       [(cb/set-token-theme changes (ctob/get-id theme) theme)
        (if (= name on) (ctob/get-theme-path theme) path)]))
   [changes nil]
   (:themes pack)))

(defn- fonts-of
  [fonts pack]
  (mapv (fn [{:keys [family]}]
          {:family family :available (some? (cam/find-font-family fonts family))})
        (:fonts pack)))

(def ^:private existing-choices #{"merge" "replace" "cancel"})

(defn apply-pack
  "apply_token_pack: gives the file a pack's sets, tokens and themes and
  turns one theme on. A file that has tokens already is not touched until
  the call says what to do with them."
  [{:keys [data fonts] :as view} {:keys [pack choices library theme onExisting]} engine]
  (let [{:keys [pack problem]} (if (some? library)
                                 (library-of "apply_token_pack" view library)
                                 (pack-of "apply_token_pack" pack choices))
        lib      (:tokens-lib data)
        state    (file-state view)
        on       (or theme (:default pack))]
    (cond
      (some? problem)
      {:problem problem}

      (not (some #(= on (:name %)) (:themes pack)))
      {:problem (dm/str "apply_token_pack: the pack has no theme called \"" on "\" — it has: "
                        (str/join ", " (map :name (:themes pack))))}

      (not (or (nil? onExisting) (contains? existing-choices onExisting)))
      {:problem "apply_token_pack: onExisting is merge, replace or cancel"}

      (= "cancel" onExisting)
      {:result {:applied false :note "nothing changed"}}

      (and (pos? (:tokens state)) (nil? onExisting))
      {:result {:applied false
                :file (select-keys state [:tokens :sets :themes])
                :note (str "This file has tokens already, so NOTHING changed. Ask the user which they want "
                           "and call again with onExisting: "
                           "\"merge\" keeps their sets and tokens on and adds the pack's — where both "
                           "name a token, the pack's value wins; "
                           "\"replace\" removes their sets and themes and leaves only the pack's; "
                           "\"cancel\" leaves the file as it is. Do not choose for them.")}}

      :else
      (let [replace? (= "replace" onExisting)
            lib'     (when-not replace? lib)
            ;; merging: what is on now stays on under every theme of the pack
            ;; (but not a set the pack has too: its themes say when that one is on)
            kept     (if (and lib' (pos? (:tokens state)))
                       (apply disj (set (ctob/get-active-themes-set-names lib')) (map :name (:sets pack)))
                       #{})
            [changes path]
            (-> (cato/library-changes data)
                (cond-> (and replace? (some? lib)) (remove-all lib))
                (add-sets lib' pack)
                (add-themes lib' pack kept on))
            made     (-> changes (cb/set-active-token-themes #{path}) (caw/redo))
            out      (cato/rebind-pages data made nil (assoc engine :fonts fonts))]
        (if-not (contains? out :changes)
          out
          {:changes (:changes out)
           :result {:applied true
                    :pack (:name pack)
                    :theme on
                    :themes (mapv :name (:themes pack))
                    :sets (mapv :name (:sets pack))
                    :tokens (transduce (map #(count (or (:typed %) (:tokens %)))) + (:sets pack))
                    :roles (count car/roles)
                    :restyled (:shapes out)
                    :fonts (fonts-of fonts pack)
                    :was (cond replace? "replaced" (pos? (:tokens state)) "merged" :else "empty")
                    :fromLibrary (true? (:from-library pack))
                    :note (str "The file has a design system now: every recipe you build takes its look "
                               "from these tokens, and so should what you build by hand — bind to the "
                               "role tokens (color.primary, spacing.md, radius.md, font-family.body…), "
                               "never a literal. To change the look, send a token again with its new "
                               "value or switch theme with activate_theme; do not restyle shapes.")}})))))
