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
  "The plain value a linked library gives a token called `role`, or nil."
  [libraries role]
  (some (fn [tokens]
          (when-let [token (get tokens role)]
            (let [value (cato/resolved-value tokens token)]
              (when (not= ::cato/unresolved value) value))))
        libraries))

(defn- token-plan
  "→ `{:problem}` or `{:names role → token name, :create [token…], :worth
  token name → what a created token is worth}`."
  [{:keys [file-id data libraries]} recipes given]
  (let [given   (into {} (map (fn [[k v]] [(name k) v])) given)
        own     (tokens-of data)
        shared  (keep (fn [[id library]] (when (not= id file-id) (tokens-of (:data library))))
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
                  (assoc-in plan [:names role] (get given role))

                  (contains? own role)
                  (assoc-in plan [:names role] role)

                  :else
                  (let [{:keys [type default]} (get car/roles role)
                        value (or (shared-value shared role)
                                  (if (= :color type) {:color default :opacity 1} default))]
                    (-> plan
                        (assoc-in [:names role] role)
                        (assoc-in [:worth role] (worth-of value))
                        (update :create conj
                                {:name role
                                 :type (get cto/token-type->dtcg-token-type type)
                                 :value (dm/str (if (map? value) (:color value) value))})))))
              {:names {} :create [] :worth {}}
              (into (sorted-set) (mapcat roles-named recipes))))))

;; --- Mains a file already has

(defn- member-of
  [component]
  (into {} (map (juxt :name :value)) (:variant-properties component)))

(defn- find-main
  "The component that is `recipe` in member `member`, in this file or a
  linked library → `{:file-id :component-id}`, or nil."
  [{:keys [file-id data libraries]} recipe member]
  (let [variants? (boolean (seq (:variants recipe)))
        in-file   (fn [id data]
                    (let [found (->> (ctkl/components-seq data)
                                     (filter #(= (:title recipe) (:name %)))
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
  "Where something new goes on the page: right of everything on it."
  [{:keys [objects]}]
  (let [rects (keep #(:selrect (get objects %)) (:shapes (get objects uuid/zero)))]
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
                    (some? parent-id) (assoc :parentId (dm/str parent-id))
                    (nil? parent-id)  (merge spot))
            build (then build at
                        (case kind
                          "text"  #(catx/create-text % (merge input (select-keys node [:text :align])) options)
                          "image" #(cam/insert-image % (assoc input :url (:url node)) options)
                          #(caw/create-shape % (assoc input :type kind) options)))
            id    (some-> build :last :id parse-uuid)]
        (if (stopped? build)
          build
          (as-> build build
            (update build :ids assoc (or (:name node) at) (dm/str id))
            (cond-> build
              (nil? (:root build)) (assoc :root (dm/str id)))
            (cond-> build
              (some? (:slot node)) (update :slots assoc (:slot node) (dm/str id)))
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
                      (build-node build child id (dm/str at ".children[" i "]") nil options))
                    build
                    (map-indexed vector (:children node)))))))))

;; --- Mains

(defn- member-name
  [recipe member]
  (str/join " / " (cons (:title recipe) (map #(get member (:name %)) (:variants recipe)))))

(defn- build-member
  "One member of a recipe as a main component → the build, with the main's id
  under `:main`."
  [build recipe member options]
  (let [at    (dm/str (:name recipe) " main")
        tree  (-> (car/expand recipe nil member)
                  (assoc :name (member-name recipe member)))
        kept  (select-keys build [:ids :slots])
        build (build-node (dissoc build :root) tree nil at (free-spot (:view build)) options)
        id    (:root build)
        build (cond-> build
                (some? id) (then at #(cas/create-component % {:shapeIds [id]} options)))]
    ;; what is inside a main is not what the call asked for, unless the build
    ;; stopped there: then it is what exists
    (-> build
        (cond-> (not (stopped? build)) (merge kept))
        (dissoc :root)
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
  [build recipe options]
  (let [at      (dm/str (:name recipe) " main")
        members (car/members recipe)
        [build ids]
        (reduce (fn [[build ids] member]
                  (let [build (build-member build recipe member options)]
                    [build (cond-> ids (not (stopped? build)) (conj (:main build)))]))
                [build []]
                members)
        note    (fn [build built]
                  (update build :mains conj (merge {:recipe (:name recipe)} built)))]
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

(defn- ensure-mains
  [build recipes options]
  (reduce (fn [build recipe]
            (cond
              (stopped? build)
              build

              (not (component? recipe))
              build

              (some? (find-main (:view build) recipe (first (car/members recipe))))
              (update build :mains conj {:recipe (:name recipe) :built false
                                         :note "the file or a linked library already has it"})

              :else
              (build-main build recipe options)))
          build
          recipes))

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
  (let [plain  (car/expand recipe nil member)
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
      (build-node build
                  (cond-> (car/expand recipe args member)
                    (some? (:name node))        (assoc :name (:name node))
                    (some? (:layoutChild node)) (update :layoutChild merge (:layoutChild node)))
                  parent-id at spot options)
      (let [main (find-main (:view build) recipe member)]
        (if (nil? main)
          (assoc build :stopped {:problem (dm/str "no main of \"" (:name recipe) "\" to place")} :at at)
          (let [place (or spot (free-spot (:view build)))
                build (then build at #(cac/create-instance % {:componentId (dm/str (:component-id main))
                                                              :fileId (dm/str (:file-id main))
                                                              :x (:x place) :y (:y place)}
                                                           options))
                id    (some-> build :last :shapeId)]
            (if (stopped? build)
              build
              (as-> build build
                (update build :ids assoc (or (:name node) at) id)
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

(defn recipe-problem
  "Why `build_recipe` cannot run, or nil."
  [{:keys [objects]} {:keys [recipe params variant parentId]} options]
  (let [recipes (catalog options)
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

      (some? (car/arguments-problem found params))
      (dm/str "build_recipe: " (car/arguments-problem found params))

      (some? (car/member-problem found variant))
      (dm/str "build_recipe: " (car/member-problem found variant))

      (and (some? parentId) (nil? parent))
      (dm/str "build_recipe: no shape on this page with id " parentId
              " (the parentId) — check read_design")

      (and (some? parent) (not (cfh/frame-shape? parent)))
      (dm/str "build_recipe: " (caw/shape-label parent) " is a "
              (some-> (:type parent) name) " — the parent must be a board"))))

(defn- answer
  [build recipe created]
  (let [stopped (:stopped build)
        result  (cond-> {:recipe (:name recipe)
                         :ids (:ids build)}
                  (seq (:slots build)) (assoc :slots (:slots build))
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
                      :note (str "recipe built — structure only: adapt the words, "
                                 "fill the slots and restyle through the tokens. "
                                 "ids are keyed by layer name. Geometry settles "
                                 "asynchronously; verify with render_board."))})))

(defn build-recipe
  "`:recipes` in the options are the host's own recipes, over the built-in
  ones. A build that is refused part-way keeps what it made: the answer
  carries those changes and says what exists and where it stopped."
  [view {:keys [recipe params variant parentId x y roles] :as input} options]
  (if-let [problem (recipe-problem view input options)]
    {:problem problem}
    (let [recipes (catalog options)
          found   (get recipes recipe)
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
                          (assoc :ids {} :slots {} :mains [] :notes [])
                          (cond-> (seq (:create plan))
                            (then "tokens" #(cato/create-tokens % {:tokens (:create plan)} options)))
                          (ensure-mains all options)
                          (dissoc :root))
              created (when (and (seq (:create plan)) (not= "tokens" (:at build)))
                        (mapv :name (:create plan)))
              node    (cond-> {:type "recipe" :use recipe}
                        (some? params)  (assoc :with (into {} (map (fn [[k v]] [(name k) v])) params))
                        (some? variant) (assoc :variant variant))
              spot    (when (nil? parentId)
                        (cond-> (free-spot (:view build))
                          (some? x) (assoc :x x)
                          (some? y) (assoc :y y)))
              build   (if (stopped? build)
                        build
                        (place-recipe build found node (some-> parentId parse-uuid)
                                      (:name found) spot options))]
          (answer build found created))))))

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
