;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipe-build
  "The design agent's build_recipe tool as a pure function; see
  `app.common.agent.write` for what a tool takes and answers, and
  `app.common.agent.recipe` for what a recipe is.

  One call does what a recipe describes by running the single tools one
  after another, each on the file as the previous one left it:

  - every role the recipe names gets a token: the one the caller says, the
    file's own token of that name, or a new one with the role's default;
  - a recipe that is a component is built once in a file, as a main with a
    member per variant; a file or linked library that has it is left alone;
  - what was asked for is then placed: an instance of that main, or the
    recipe's tree when it is not a component."
  (:require
   [app.common.agent.components :as cac]
   [app.common.agent.compose :as cap]
   [app.common.agent.layout :as cal]
   [app.common.agent.media :as cam]
   [app.common.agent.recipe :as car]
   [app.common.agent.recipes :as cars]
   [app.common.agent.recipes.profiles :as profiles]
   [app.common.agent.structure :as cas]
   [app.common.agent.text :as catx]
   [app.common.agent.tokens :as cato]
   [app.common.agent.write :as caw]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.types.components-list :as ctkl]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(defn catalog
  "Name → recipe: the built-in ones, and over them the ones the host hands
  in as `:recipes`."
  [options]
  (merge cars/builtin (:recipes options)))

(defn profiles
  "Name → platform profile: the built-in ones, and over them the ones the
  host hands in as `:profiles`."
  [options]
  (merge profiles/builtin (:profiles options)))

(defn- component?
  [recipe]
  (or (seq (:variants recipe)) (true? (:component recipe))))

(defn- involved
  "The recipes a build of `recipe` touches, each once, a recipe after the
  ones it uses."
  [lookup recipe]
  (letfn [(walk [seen recipe]
            (if (some #(= (:name recipe) (:name %)) seen)
              seen
              (conj (reduce walk seen (keep lookup (car/uses recipe))) recipe)))]
    (walk [] recipe)))

;; --- Finding a recipe
;;
;; The agent sees the index — a line per recipe — in every turn, and asks
;; find_recipes for the ones that fit what it is about to build: by a word of
;; their name, their purpose or their keywords.

(defn index
  "The catalog as the agent always sees it: a line per recipe, by category."
  [recipes]
  (->> (vals recipes)
       (group-by :category)
       (sort-by (fn [[category _]] (.indexOf ["screen" "section" "navigation" "container" "control"] category)))
       (map (fn [[category found]]
              (dm/str "- " category ": "
                      (str/join "; " (map #(dm/str (:name %) " (" (str/lower (:title %)) ")")
                                          (sort-by :name found))))))
       (str/join "\n")))

(defn- words
  [s]
  (into [] (filter #(>= (count %) 2)) (str/split (str/lower (or s "")) #"[^a-z0-9]+")))

(defn- score
  "How well `recipe` answers the words asked for: its name and title count
  most, then its keywords, then its purpose."
  [recipe asked]
  (let [in?   (fn [text word] (str/includes? (str/lower (or text "")) word))
        named (dm/str (:name recipe) " " (:title recipe))
        keyed (str/join " " (:keywords recipe))]
    (reduce (fn [total word]
              (+ total
                 (cond
                   (in? named word)             4
                   (in? keyed word)             3
                   (in? (:category recipe) word) 2
                   (in? (:purpose recipe) word) 1
                   :else                        0)))
            0
            asked)))

(defn text-keys
  "What each text of the recipe's tree goes by in build_recipe's `texts`,
  with `{index}` where a turn of a repeat goes: its layer name, behind its
  parent's when the name is not the only one. A layer that places another
  recipe goes by its own name when that recipe has one text, and stands
  before each of its texts when it has more. `lookup` is name → recipe."
  [recipe lookup]
  (let [tree  (:tree recipe)
        names (frequencies (keep :name (tree-seq map? :children tree)))]
    (letfn [(walk [node parent-key repeats]
              (let [repeats (cond-> repeats (contains? node :repeat) inc)
                    nm      (:name node)
                    turns   (if (and (string? nm) (str/includes? nm "{index}")) 1 0)
                    key     (if (and (some? parent-key)
                                     (or (> (get names nm 0) 1) (> repeats turns)))
                              (dm/str parent-key " / " nm)
                              nm)]
                (cond
                  (= "text" (:type node))
                  [key]

                  (= "recipe" (:type node))
                  (let [inside (some-> (:use node) lookup (text-keys lookup))]
                    (if (= 1 (count inside))
                      [key]
                      (mapv #(dm/str key " / " %) inside)))

                  :else
                  (mapcat #(walk % key repeats) (:children node)))))]
      (vec (walk tree nil 0)))))

(defn- placeholder-layers
  "`[kind layer-name]` of each placeholder in the recipe's tree."
  [recipe]
  (into [] (comp (filter :placeholder) (filter :name) (map (juxt :placeholder :name)) (distinct))
        (tree-seq map? :children (:tree recipe))))

(defn- placeholder-names
  [recipe]
  (mapv second (placeholder-layers recipe)))

(defn- described
  "A recipe as find_recipes answers it: what to hand build_recipe."
  [recipe lookup]
  (let [slots (into [] (keep :slot) (tree-seq map? :children (:tree recipe)))]
    (cond-> {:name (:name recipe)
             :title (:title recipe)
             :category (:category recipe)
             :purpose (:purpose recipe)
             :placedAs (if (component? recipe) "an instance of its component" "its own tree")}
      (seq (:params recipe))
      (assoc :params (mapv #(select-keys % [:name :type :default :description :options :min :max])
                           (:params recipe)))
      (seq (:variants recipe)) (assoc :variants (:variants recipe))
      (seq slots)              (assoc :slots slots)
      (seq (text-keys recipe lookup)) (assoc :texts (text-keys recipe lookup))
      (seq (placeholder-names recipe)) (assoc :placeholders (placeholder-names recipe))
      (seq (car/uses recipe))  (assoc :uses (car/uses recipe)))))

(def ^:private max-matches 5)

(defn find-recipes
  "`:recipes` in the options are the host's own recipes, over the built-in
  ones."
  [_ {:keys [query category]} options]
  (let [recipes (vals (catalog options))
        asked   (words query)]
    (cond
      (and (some? category) (not (contains? car/categories category)))
      {:problem (dm/str "find_recipes: category \"" category "\" is not one — use one of: "
                        (str/join ", " (sort car/categories)))}

      (and (empty? asked) (nil? category))
      {:problem (str "find_recipes: pass query — a few words for what you are about to "
                     "build, e.g. \"sign up form\" — or a category")}

      :else
      (let [found (->> recipes
                       (filter #(or (nil? category) (= category (:category %))))
                       (map (fn [recipe] [(if (empty? asked) 1 (score recipe asked)) recipe]))
                       (filter (fn [[points _]] (pos? points)))
                       (sort-by (fn [[points recipe]] [(- points) (:name recipe)]))
                       (take (if (empty? asked) 100 max-matches))
                       (mapv #(described (second %) (catalog options))))]
        {:changes []
         :result {:matches found
                  :note (if (empty? found)
                          (str "no recipe fits — build it by hand with build_tree, and "
                               "use recipes for the parts that have one (button, "
                               "text-field, card…)")
                          (str "best match first. Build one with build_recipe {recipe, "
                               "params, variant, parentId, texts}: `texts` sets the words "
                               "of its layers in the same call, keyed as in `texts` here "
                               "(a number in place of {index}); a layer that places "
                               "another recipe takes its words too, e.g. \"Plan 2 / "
                               "Choose\". Then replace its placeholders and fill its "
                               "slots. If none is what you need, build by hand."))}}))))

;; --- Roles
;;
;; A role is bound to a token of the file. Which one: the token the caller
;; names for it, else the file's token of the role's own name, else a new
;; token with the role's default — or with the value a linked library gives
;; a token of that name, as a file binds to its own tokens only.
;;
;; A host that resolves tokens does it between runs of the tool, and knows
;; the tokens it resolved by what they are. A token made here is made anew on
;; every run, so it would never be one the host has resolved: what it is
;; worth is said here, where its value is known, and the host is asked about
;; the file's own tokens only.

(defn- roles-named
  [recipe]
  (into #{}
        (mapcat (fn [node]
                  (concat (vals (:tokens node))
                          (mapcat #(vals (get-in % [:set :tokens])) (:vary node)))))
        (tree-seq map? #(filter map? (:children %)) (:tree recipe))))

(defn- tokens-of
  [data]
  (some-> (:tokens-lib data) ctob/get-all-tokens-map))

(defn- worth-of
  "What a plain value is worth, as a token resolver answers it."
  [value]
  (if (map? value)
    {:resolved-value (:color value) :color value}
    {:resolved-value value}))

(defn- plain-worth
  "What `tokens` are worth without a resolver: the plain ones."
  [tokens]
  (into {}
        (map (fn [[nm token]]
               (let [value (cato/resolved-value tokens token)]
                 [nm (if (= ::cato/unresolved value) {:unresolved true} (worth-of value))])))
        tokens))

(defn- shared-value
  "What a linked library gives a token called `role` → `{:raw :value}`, the
  value as it holds it and what that is worth, or nil."
  [libraries role]
  (some (fn [tokens]
          (when-let [token (get tokens role)]
            (let [value (cato/resolved-value tokens token)]
              (when (not= ::cato/unresolved value)
                {:raw (:value token) :value value}))))
        libraries))

(defn- default-value
  "A role's default → `{:raw :value}`."
  [role {:keys [type default]}]
  {:raw default
   :value (case type
            :color {:color default :opacity 1}
            (:font-family :shadow) (cato/resolved-value {} {:name role :type type :value (cato/stored-value type default)})
            default)})

(defn- written
  "A token's value as `create_tokens` takes it."
  [type {:keys [raw value]}]
  (cond
    (= :shadow type)      raw
    (= :font-family type) (if (sequential? raw) (str/join ", " raw) raw)
    (map? value)          (:color value)
    :else                 (dm/str value)))

(defn- token-plan
  "→ `{:problem}` or `{:names role → token name, :create [token…], :worth
  token name → what a created token is worth}`."
  [{:keys [file-id data libraries]} recipes given]
  (let [given   (into {} (map (fn [[k v]] [(name k) v])) given)
        own     (tokens-of data)
        ;; of a library, the tokens that are on: with a light and a dark set
        ;; the last one written is not the one its components show
        shared  (keep (fn [[id library]]
                        (when (not= id file-id)
                          (some-> library :data :tokens-lib ctob/get-tokens-in-active-sets)))
                      libraries)
        unknown (first (remove #(contains? car/roles %) (keys given)))
        missing (first (remove #(contains? own %) (vals given)))]
    (cond
      (some? unknown)
      {:problem (dm/str "build_recipe: roles names \"" unknown "\", which is not a role"
                        " — roles are: " (str/join ", " (sort (keys car/roles))))}

      (some? missing)
      {:problem (dm/str "build_recipe: roles names the token \"" missing
                        "\", and this file has no token of that name — create it"
                        " with create_token, or leave the role out to get a default")}

      :else
      (reduce (fn [plan role]
                (cond
                  (contains? given role)
                  (-> plan
                      (assoc-in [:names role] (get given role))
                      (assoc-in [:from role] "the token you named"))

                  (contains? own role)
                  (-> plan
                      (assoc-in [:names role] role)
                      (assoc-in [:from role] "the file's token of that name"))

                  :else
                  (let [{:keys [type] :as entry} (get car/roles role)
                        lent  (shared-value shared role)
                        found (or lent (default-value role entry))]
                    (-> plan
                        (assoc-in [:names role] role)
                        (assoc-in [:from role] (if (some? lent)
                                                 "a new token, with the value a linked library gives it now"
                                                 "a new token, with the role's default"))
                        (assoc-in [:worth role] (worth-of (:value found)))
                        (update :create conj
                                {:name role
                                 :type (get cto/token-type->dtcg-token-type type)
                                 :value (written type found)})))))
              {:names {} :from {} :create [] :worth {}}
              (into (sorted-set) (mapcat roles-named recipes))))))

;; --- Mains a file already has

(defn- member-of
  [component]
  (into {} (map (juxt :name :value)) (:variant-properties component)))

;; A component is one structure. A recipe built with params that change its
;; structure — a table row of five cells and not four — is another component,
;; and goes by another name: "Table row (columns 5)".

(defn- structural-args
  "The args that change the structure of `recipe` away from its defaults."
  [recipe args]
  (let [params (into {} (map (juxt :name :default)) (:params recipe))
        named  (car/structural-params recipe)]
    (into (sorted-map)
          (comp (map (fn [[k v]] [(name k) v]))
                (filter (fn [[k v]] (and (contains? named k) (not= v (get params k))))))
          args)))

(defn- main-title
  [recipe sargs]
  (if (empty? sargs)
    (:title recipe)
    (dm/str (:title recipe) " ("
            (str/join ", " (map (fn [[k v]] (dm/str k " " (if (and (number? v) (== v (long v))) (long v) v))) sargs))
            ")")))

(defn- find-main
  "The component called `title` that is `recipe` in member `member`, in this
  file or a linked library → `{:file-id :component-id}`, or nil."
  [{:keys [file-id data libraries]} recipe member title]
  (let [variants? (boolean (seq (:variants recipe)))
        in-file   (fn [id data]
                    (let [found (->> (ctkl/components-seq data)
                                     (filter #(= title (:name %)))
                                     (filter #(= variants? (some? (:variant-id %)))))]
                      (when-let [component (if variants?
                                             (some #(when (= member (member-of %)) %) found)
                                             (first found))]
                        {:file-id id :component-id (:id component)})))]
    (or (in-file file-id data)
        (some (fn [[id library]] (when (not= id file-id) (in-file id (:data library))))
              libraries))))

;; --- One node after another
;;
;; A build is a run of `app.common.agent.compose`, with what it has made so
;; far: `:ids` (layer name → id), `:slots`, `:mains` and `:notes`.

(defn- stopped?
  [build]
  (some? (:stopped build)))

(defn- then
  "One more step, said to be `at` when it is the one that stops the build."
  [build at f]
  (let [next (cap/then build f)]
    (cond-> next
      (and (stopped? next) (not (stopped? build))) (assoc :at at))))

(defn- free-spot
  "Where something new goes on the page: right of everything on it, whatever
  it is inside of, so that it lands in no board."
  [{:keys [objects]}]
  (let [rects (keep :selrect (vals (dissoc objects uuid/zero)))]
    (if (empty? rects)
      {:x 0 :y 0}
      {:x (+ 200 (apply max (map :x2 rects)))
       :y (apply min (map :y1 rects))})))

(defn- resolver
  "The host's token resolver, asked about the file's own tokens only; see
  Roles."
  [host made]
  (fn [active]
    (let [own    (apply dissoc active (keys made))
          theirs (cond
                   (empty? own) {}
                   (nil? host)  (plain-worth own)
                   :else        (host own))]
      (when (some? theirs)
        (merge theirs (select-keys made (keys active)))))))

(defn- bind-roles
  [build at id tokens {:keys [names made] :as options}]
  (if (empty? tokens)
    build
    (then build at
          (fn [view]
            (let [answer (cato/apply-tokens
                          view
                          {:applications
                           (mapv (fn [[property role]]
                                   {:shapeId (dm/str id)
                                    :tokenName (get names role)
                                    :properties (car/token-properties (name property))})
                                 (sort-by key tokens))}
                          options)
                  failed (some #(when (false? (:ok %)) %) (-> answer :result :results))]
              (cond
                (some? failed)
                {:problem (dm/str "token \"" (:token failed) "\": " (:error failed))}

                (contains? answer :pending)
                (update-in answer [:pending :tokens] #(apply dissoc % (keys made)))

                :else
                answer))))))

(defn- unfilled
  "A shape comes with a fill of its own; a node that names no role for it
  has none."
  [build at id]
  (then build at
        (fn [{:keys [page-id objects]}]
          {:changes (-> (caw/page-changes page-id objects)
                        (cb/update-shapes [id] #(assoc % :fills []))
                        (caw/redo))})))

(defn- key-of
  "What a node goes by in `:ids`: its layer name, behind its parent's when
  the name is not the only one."
  [build node at {:keys [parent-key dupes]}]
  (let [nm  (or (:name node) at)
        ids (:ids build)
        key (if (and (some? parent-key) (or (contains? dupes nm) (contains? ids nm)))
              (dm/str parent-key " / " nm)
              nm)]
    (if (contains? ids key)
      (first (remove #(contains? ids %) (map #(dm/str key " #" %) (iterate inc 2))))
      key)))

(defn- repeated-names
  [tree]
  (into #{}
        (keep (fn [[nm n]] (when (and (some? nm) (> n 1)) nm)))
        (frequencies (map :name (tree-seq map? :children tree)))))

(declare place-recipe)

(defn- build-node
  "Creates `node` under `parent-id` (nil for the page, at `spot`), then its
  layout, its place in its parent's layout, its tokens and its children."
  [build node parent-id at spot {:keys [lookup] :as options}]
  (if (stopped? build)
    build
    (if (= "recipe" (:type node))
      (place-recipe build (lookup (:use node)) node parent-id at spot options)
      (let [kind  (:type node)
            input (cond-> (select-keys node [:name :width :height])
                    ;; a board that lays out takes its size from what it
                    ;; holds: with nothing in it, it takes no room
                    (and (= "board" kind) (some? (:layout node)) (nil? (:width node)))  (assoc :width 1)
                    (and (= "board" kind) (some? (:layout node)) (nil? (:height node))) (assoc :height 1)
                    (some? parent-id) (assoc :parentId (dm/str parent-id))
                    (nil? parent-id)  (merge spot))
            build (then build at
                        (case kind
                          "text"  #(catx/create-text % (merge input (select-keys node [:text :align])) options)
                          "image" #(cam/insert-image % (assoc input :url (:url node)) options)
                          #(caw/create-shape % (assoc input :type kind) options)))
            id    (some-> build :last :id parse-uuid)
            key   (key-of build node at options)]
        (if (stopped? build)
          build
          (as-> build build
            (update build :ids assoc key (dm/str id))
            (cond-> build
              (nil? (:root build)) (assoc :root (dm/str id)))
            (cond-> build
              (some? (:slot node)) (update :slots assoc (:slot node) (dm/str id)))
            (cond-> build
              (some? (:placeholder node)) (update-in [:placeholders (:placeholder node)] (fnil conj []) key))
            (cond-> build
              (and (contains? #{"board" "rect" "ellipse"} kind)
                   (not (contains? (:tokens node) :fill)))
              (unfilled at id))
            (cond-> build
              (some? (:layout node))
              (then at #(cal/set-layout % (assoc (:layout node) :shapeId (dm/str id)) options)))
            (cond-> build
              (some? (:layoutChild node))
              (then at #(cal/set-layout-child % (assoc (:layoutChild node) :shapeId (dm/str id)) options)))
            (bind-roles build at id (:tokens node) options)
            (reduce (fn [build [i child]]
                      (build-node build child id (dm/str at ".children[" i "]") nil
                                  (assoc options :parent-key key)))
                    build
                    (map-indexed vector (:children node)))))))))

;; --- Mains

(defn- member-name
  [recipe title member]
  (str/join " / " (cons title (map #(get member (:name %)) (:variants recipe)))))

(defn- build-member
  "One member of a recipe as a main component → the build, with the main's id
  under `:main`."
  [build recipe sargs title member options]
  (let [at      (dm/str (:name recipe) " main")
        tree    (-> (car/expand recipe sargs member)
                    (assoc :name (member-name recipe title member)))
        kept    (select-keys build [:ids :slots :placeholders :root])
        ;; a main is built on the page, whatever was being built when it was
        ;; found missing
        options (dissoc options :parent-key :dupes)
        build   (build-node (dissoc build :root) tree nil at (free-spot (:view build)) options)
        id      (:root build)
        build   (cond-> build
                  (some? id) (then at #(cas/create-component % {:shapeIds [id]} options)))]
    ;; what is inside a main is not what the call asked for, unless the build
    ;; stopped there: then it is what exists
    (-> (dissoc build :root)
        (cond-> (not (stopped? build)) (merge kept))
        (assoc :main id))))

(defn- name-axes
  "A new set calls its axes \"Property 1\"…: they get the recipe's names."
  [build recipe variant-id at options]
  (reduce (fn [build [i axis]]
            (then build at
                  (fn [{:keys [data objects] :as view}]
                    (let [had (get-in (cac/variant-facts data objects variant-id) [:axes i])]
                      (if (or (nil? had) (= had (:name axis)))
                        {:changes []}
                        (cac/set-variant-property view
                                                  {:variantId (dm/str variant-id)
                                                   :property had
                                                   :rename (:name axis)}
                                                  options))))))
          build
          (map-indexed vector (:variants recipe))))

(defn- build-main
  "The main of `recipe`: a component, or a variant set with a member per
  combination, laid out by the engine."
  [build recipe sargs title options]
  (let [at      (dm/str (:name recipe) " main")
        members (car/members recipe)
        [build ids]
        (reduce (fn [[build ids] member]
                  (let [build (build-member build recipe sargs title member options)]
                    [build (cond-> ids (not (stopped? build)) (conj (:main build)))]))
                [build []]
                members)
        inside  (placeholder-names recipe)
        note    (fn [build built]
                  (update build :mains conj (cond-> (merge {:recipe (:name recipe)} built)
                                              (not= title (:title recipe)) (assoc :component title)
                                              (seq inside) (assoc :placeholders inside))))]
    (cond
      (stopped? build)
      (cond-> build (seq ids) (note {:built "in part" :memberIds ids}))

      (empty? (:variants recipe))
      (note build {:built true :shapeId (first ids)})

      :else
      (let [build (then build at #(cac/create-variant % {:shapeIds ids} options))
            vid   (some-> build :last :variantId)]
        (if (stopped? build)
          (note build {:built "in part" :memberIds ids})
          (-> build
              (name-axes recipe (parse-uuid vid) at options)
              (then at #(cal/set-layout % {:shapeId vid :type "flex" :dir "row" :wrap true
                                           :rowGap 24 :columnGap 24
                                           :padding {:top 32 :right 32 :bottom 32 :left 32}}
                                        options))
              (then at #(cal/set-layout-child % {:shapeId vid :horizontalSizing "auto"
                                                 :verticalSizing "auto"}
                                              options))
              (note {:built true :variantId vid :members (count ids)})))))))

(defn- ensure-main
  "The main called `title` of `recipe`, built now when neither the file nor a
  linked library has it. Said once in `:mains` either way."
  [build recipe sargs title options]
  (cond
    (stopped? build)
    build

    (contains? (:seen build) title)
    build

    (some? (find-main (:view build) recipe (first (car/members recipe)) title))
    (-> build
        (update :seen (fnil conj #{}) title)
        (update :mains conj (cond-> {:recipe (:name recipe) :built false
                                     :note "the file or a linked library already has it"}
                              (not= title (:title recipe)) (assoc :component title))))

    :else
    (-> (build-main build recipe sargs title options)
        (update :seen (fnil conj #{}) title))))

;; --- Placing a recipe

(defn- of-type
  [tree kind]
  (filter #(= kind (:type %)) (tree-seq map? :children tree)))

(defn- child-named
  "The one shape under `root-id` with that layer name, or nil."
  [objects root-id nm pred]
  (let [found (filter #(and (= nm (:name (get objects %))) (pred (get objects %)))
                      (cfh/get-children-ids objects root-id))]
    (when (= 1 (count found))
      (first found))))

(defn- shape-of
  "A tree without its words: what an instance cannot change."
  [tree]
  (cond-> (dissoc tree :text :with)
    (contains? tree :children) (update :children #(mapv shape-of %))))

(defn- override-texts
  "An instance shows its main's words; the ones the params change are set on
  it, each found by its layer name. The same goes for the instances inside
  it."
  [build recipe args member root-id at {:keys [lookup] :as options}]
  (let [plain  (car/expand recipe (structural-args recipe args) member)
        wanted (car/expand recipe args member)
        unset  (fn [build what nm]
                 (update build :notes conj
                         (dm/str "the " what " \"" nm "\" of this \"" (:name recipe)
                                 "\" was not found by its name: set its words with set_text")))]
    (if (not= (shape-of plain) (shape-of wanted))
      (update build :notes conj
              (dm/str "\"" (:name recipe) "\" is a component, and an instance keeps"
                      " its main's structure: the params that change structure were"
                      " not applied — detach_instance and edit, or change the main"))
      (as-> build build
        (reduce (fn [build [before after]]
                  (if (or (stopped? build) (= (:text before) (:text after)))
                    build
                    (let [id (child-named (-> build :view :objects) root-id (:name before)
                                          cfh/text-shape?)]
                      (if (nil? id)
                        (unset build "text" (:name before))
                        (then build at #(catx/set-text % {:shapeId (dm/str id) :text (:text after)}
                                                       options))))))
                build
                (map vector (of-type plain "text") (of-type wanted "text")))
        (reduce (fn [build [before after]]
                  (if (or (stopped? build) (= (:with before) (:with after)))
                    build
                    (let [used (lookup (:use after))
                          id   (child-named (-> build :view :objects) root-id (:name before)
                                            (constantly true))]
                      (cond
                        (not (component? used)) build
                        (nil? id) (unset build "instance" (:name before))
                        :else (override-texts build used (:with after)
                                              (car/member used (:variant after)) id at options)))))
                build
                (map vector (of-type plain "recipe") (of-type wanted "recipe")))))))

(defn- place-recipe
  "Puts `recipe` under `parent-id` as `node` asks (`:with`, `:variant`,
  `:name`, `:layoutChild`): an instance of its main, or its tree."
  [build recipe node parent-id at spot options]
  (let [args   (:with node)
        member (car/member recipe (:variant node))]
    (if-not (component? recipe)
      (let [tree (cond-> (car/expand recipe args member)
                   (some? (:name node))        (assoc :name (:name node))
                   (some? (:layoutChild node)) (update :layoutChild merge (:layoutChild node)))]
        (build-node build tree parent-id at
                    (when (nil? parent-id) (or spot (free-spot (:view build))))
                    (update options :dupes (fnil into #{}) (repeated-names tree))))
      (let [sargs (structural-args recipe args)
            title (main-title recipe sargs)
            build (ensure-main build recipe sargs title options)
            main  (when-not (stopped? build) (find-main (:view build) recipe member title))]
        (cond
          (stopped? build)
          build

          (nil? main)
          (assoc build :stopped {:problem (dm/str "no main of \"" (:name recipe) "\" to place")} :at at)

          :else
          (let [place (or spot (free-spot (:view build)))
                build (then build at #(cac/create-instance % {:componentId (dm/str (:component-id main))
                                                              :fileId (dm/str (:file-id main))
                                                              :x (:x place) :y (:y place)}
                                                           options))
                id    (some-> build :last :shapeId)]
            (if (stopped? build)
              build
              (as-> build build
                (let [key (key-of build node at options)]
                  (as-> (update build :ids assoc key id) build
                    (cond-> build
                      (some? (:placeholder node))
                      (update-in [:placeholders (:placeholder node)] (fnil conj []) key))
                    ;; what stands for something else inside the component:
                    ;; found from this instance by its layer name
                    (reduce (fn [build [kind layer]]
                              (update-in build [:placeholders kind] (fnil conj [])
                                         ;; what the call itself placed needs no name before it
                                         (if (some? (:name node)) (dm/str key " / " layer) layer)))
                            build
                            (placeholder-layers recipe))))
                (cond-> build
                  (nil? (:root build)) (assoc :root id))
                (cond-> build
                  (some? (:name node))
                  (then at #(caw/modify-shape % {:shapeId id :name (:name node)} options)))
                (cond-> build
                  (some? parent-id)
                  (then at #(cas/nest-shape % {:shapeId id :parentId (dm/str parent-id)} options)))
                (cond-> build
                  (some? (:layoutChild node))
                  (then at #(cal/set-layout-child % (assoc (:layoutChild node) :shapeId id) options)))
                (override-texts build recipe args member (parse-uuid id) at options)))))))))

;; --- build_recipe

;; --- The words, in the same call
;;
;; `texts` maps what a layer goes by — its key in `:ids`, or a path of layer
;; names from the root — to its words. A layer that is not a text and holds
;; exactly one, as an instance of a button does, takes the words for it.

(defn- text-target
  "The text shape `key` means, or nil."
  [objects ids root key]
  (let [parts  (str/split key #"\s*/\s*")
        [start more]
        (or (some (fn [n]
                    (when-let [id (get ids (str/join " / " (take n parts)))]
                      [(parse-uuid id) (drop n parts)]))
                  (range (count parts) 0 -1))
            [root parts])
        target (reduce (fn [id part]
                         (when (some? id)
                           (if (= part (:name (get objects id)))
                             id
                             (child-named objects id part (constantly true)))))
                       start
                       more)
        shape  (get objects target)]
    (cond
      (nil? shape)            nil
      (cfh/text-shape? shape) target
      :else
      (let [inside (filter #(cfh/text-shape? (get objects %)) (cfh/get-children-ids objects target))]
        (when (= 1 (count inside))
          (first inside))))))

(defn- fill-texts
  [build texts {:keys [measure-text] :as options}]
  (if (or (stopped? build) (empty? texts) (nil? (:root build)))
    build
    (let [objects (-> build :view :objects)
          root    (parse-uuid (:root build))
          found   (mapv (fn [[k words]]
                          ;; a key arrives as a keyword, and one with a "/" in
                          ;; it would lose what is before it to `name`
                          (let [key (if (keyword? k) (subs (str k) 1) (str k))]
                            [key (when (string? words) (text-target objects (:ids build) root key)) words]))
                        texts)
          entries (into [] (keep (fn [[_ id words]] (when (some? id) [id words]))) found)
          missed  (into [] (keep (fn [[key id _]] (when (nil? id) key))) found)]
      (cond-> build
        (seq missed)
        (assoc :texts-not-set
               {:keys missed
                :note (str "no text goes by these — use a key of `ids`, or a path of "
                           "layer names from one, such as \"Plan 2 / Choose\"; set "
                           "them with set_texts")})

        (seq entries)
        (then "texts"
              (fn [{:keys [page-id objects]}]
                (if (nil? measure-text)
                  {:fallback "text cannot be measured on the server without the render engine"}
                  {:changes (-> (caw/page-changes page-id objects)
                                (catx/texts-into entries options)
                                (caw/redo))})))))))

(defn recipe-problem
  "Why `build_recipe` cannot run, or nil."
  [{:keys [objects]} {:keys [recipe params variant parentId profile texts]} options]
  (let [recipes (catalog options)
        known   (profiles options)
        found   (get recipes recipe)
        parent  (some->> parentId parse-uuid (get objects))]
    (cond
      (not (string? recipe))
      (dm/str "build_recipe: pass recipe — the name of one of: "
              (str/join ", " (sort (keys recipes))))

      (nil? found)
      (dm/str "build_recipe: no recipe named \"" recipe "\" — there are: "
              (str/join ", " (sort (keys recipes))))

      (some? (car/problem found recipes))
      (dm/str "build_recipe: " (car/problem found recipes))

      (and (some? profile) (not (contains? known profile)))
      (dm/str "build_recipe: no platform profile named \"" profile "\" — there are: "
              (str/join ", " (sort (keys known))))

      (and (some? profile) (some? (car/profile-problem (get known profile) recipes)))
      (dm/str "build_recipe: " (car/profile-problem (get known profile) recipes))

      (some? (car/arguments-problem found params))
      (dm/str "build_recipe: " (car/arguments-problem found params))

      (some? (car/member-problem found variant))
      (dm/str "build_recipe: " (car/member-problem found variant))

      (not (or (nil? texts) (map? texts)))
      "build_recipe: texts is not a map of layer to words"

      (and (some? parentId) (nil? parent))
      (dm/str "build_recipe: no shape on this page with id " parentId
              " (the parentId) — check read_design")

      (and (some? parent) (not (cfh/frame-shape? parent)))
      (dm/str "build_recipe: " (caw/shape-label parent) " is a "
              (some-> (:type parent) name) " — the parent must be a board"))))

(defn- answer
  [build recipe created plan]
  (let [stopped (:stopped build)
        ;; where a role's token came from, when it is neither the file's own
        ;; token of that name nor a new one with the default (those are in
        ;; tokensCreated)
        notable (into {}
                      (remove (fn [[_ from]] (contains? #{"the file's token of that name"
                                                          "a new token, with the role's default"}
                                                        from)))
                      (:from plan))
        result  (cond-> {:recipe (:name recipe)
                         :ids (:ids build)}
                  (seq notable) (assoc :roleTokens (into {}
                                                         (map (fn [[role from]]
                                                                [role {:token (get-in plan [:names role])
                                                                       :from from}]))
                                                         notable))
                  (seq (:slots build)) (assoc :slots (:slots build))
                  (seq (:placeholders build)) (assoc :placeholders (:placeholders build))
                  (seq (:texts-not-set build)) (assoc :textsNotSet (:texts-not-set build))
                  (seq (:mains build)) (assoc :mains (:mains build))
                  (seq created)        (assoc :tokensCreated created)
                  (seq (:notes build)) (assoc :notes (:notes build)))]
    (cond
      ;; not this build's to settle: the host takes over, or resolves the
      ;; tokens and runs it again
      (or (contains? stopped :fallback) (contains? stopped :pending))
      stopped

      (some? stopped)
      (cond-> {:changes (:changes build)
               :result (assoc result
                              :error (dm/str "build_recipe at " (:at build) ": " (:problem stopped))
                              :note (str "PARTIAL build — everything in ids, mains and "
                                         "tokensCreated exists. Fix the error with "
                                         "targeted calls; do NOT build the recipe again."))}
        (contains? stopped :rule) (assoc-in [:result :rule] (:rule stopped)))

      :else
      {:changes (:changes build)
       :result (assoc result
                      :id (:root build)
                      :note (str "recipe built — this is the start, not the result. "
                                 "Still yours to do: the words you did not pass in "
                                 "`texts` (set_texts sets many in one call); every "
                                 "layer in `placeholders` is a grey box standing for "
                                 "an icon or a picture — put the real one in its "
                                 "place (insert_icon, insert_image) or tell the user "
                                 "which you left; fill the slots; and give the "
                                 "tokens the values this design calls for. ids are "
                                 "keyed by layer name. Verify with render_board."))})))

(defn build-recipe
  "`:recipes` in the options are the host's own recipes, over the built-in
  ones. A build that is refused part-way keeps what it made: the answer
  carries those changes and says what exists and where it stopped."
  [view {:keys [recipe params variant parentId x y roles profile texts] :as input} options]
  (if-let [problem (recipe-problem view input options)]
    {:problem problem}
    (let [platform (get (profiles options) profile)
          ;; each recipe as the platform has it; with none, as it is
          recipes (cond->> (catalog options)
                    (some? platform)
                    (into {} (map (fn [[nm found]] [nm (car/with-profile found platform)]))))
          found   (get recipes recipe)
          ;; the variant the platform places, unless the call says another
          variant (if (some? platform)
                    (merge (car/profile-variant platform found)
                           (into {} (map (fn [[k v]] [(name k) v])) variant))
                    variant)
          all     (involved recipes found)
          plan    (token-plan view all roles)]
      (if (contains? plan :problem)
        plan
        (let [options (assoc options
                             :lookup recipes
                             :names (:names plan)
                             :made (:worth plan)
                             :resolve-tokens (resolver (:resolve-tokens options) (:worth plan)))
              build   (-> (cap/start view)
                          (assoc :ids {} :slots {} :placeholders {} :mains [] :notes [])
                          (cond-> (seq (:create plan))
                            (then "tokens" #(cato/create-tokens % {:tokens (:create plan)} options))))
              created (when (and (seq (:create plan)) (not= "tokens" (:at build)))
                        (mapv :name (:create plan)))
              node    (cond-> {:type "recipe" :use recipe}
                        (some? params)  (assoc :with (into {} (map (fn [[k v]] [(name k) v])) params))
                        (seq variant)   (assoc :variant variant))
              ;; where the call says; else wherever is free once the mains
              ;; it needs are built
              spot    (when (and (nil? parentId) (or (some? x) (some? y)))
                        (cond-> (free-spot (:view build))
                          (some? x) (assoc :x x)
                          (some? y) (assoc :y y)))
              build   (if (stopped? build)
                        build
                        (-> (place-recipe build found node (some-> parentId parse-uuid)
                                          (:name found) spot options)
                            (fill-texts texts options)))]
          (answer build found created plan))))))

(defn build-recipe-needs
  "The images the recipe's tree asks for, when it is a built-in one."
  [{:keys [recipe params variant]}]
  (when-let [found (get cars/builtin recipe)]
    (when (and (nil? (car/arguments-problem found params))
               (nil? (car/member-problem found variant)))
      (into []
            (comp (filter #(= "image" (:type %)))
                  (map (fn [node] {:kind :image :url (:url node) :name (or (:name node) "image")})))
            (tree-seq map? :children (car/expand found params variant))))))
