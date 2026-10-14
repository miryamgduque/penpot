;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.recipe
  "The recipe format: what a recipe is, and why a given one is not valid.

  A recipe is the structure of a common piece of UI as plain data — maps,
  vectors, strings, numbers and booleans, the same once written as JSON. It
  says what the piece consists of and never how it looks: a value that a
  design system owns (a color, a spacing, a radius, a type size) is named by
  its role and never written down.

    {:recipe   1                    the format version
     :name     \"pricing-table\"      its id, kebab-case
     :title    \"Pricing table\"
     :purpose  \"…\"                  what it is for, in a sentence
     :category \"section\"            control, container, navigation, section,
                                    screen
     :keywords [\"plans\" …]          optional, more words to be found by
     :params   [param …]            optional, what whoever builds it may set
     :variants [axis …]             optional, makes it a variant set
     :component true                optional, makes it a single component
     :tree     node}                the root, a board

  A param is `{:name :type :default :description}`; `:type` is text, number
  (with `:min` and `:max`), boolean or choice (with `:options`).

  An axis is `{:name \"Kind\" :values [\"Primary\" \"Secondary\"]}`. The recipe
  is built once per combination of values, and the first value of each axis
  is the one the tree describes as written.

  A node is one of

    board    :layout :children :slot
    rect, ellipse
    text     :text :align
    image    :url
    recipe   :use :with :variant — another recipe, placed here

  and any of them may carry `:name`, `:width`, `:height` (not a recipe),
  `:layoutChild`, `:tokens`, `:when`, `:repeat` and `:vary`.

  - `:layout` and `:layoutChild` are what set_layout and set_layout_child
    take. A node has no x or y: its parent's layout places it.
  - `:tokens` maps a property to a role: `{:fill \"color.primary\"}`. The
    properties are the ones a token can bind to, plus the shorthands in
    `shorthands`; the roles are the ones in `roles`.
  - `:placeholder \"icon\"` (or image) marks a rect or an ellipse as a box
    that stands for something a recipe cannot hold: whoever builds it puts
    the real thing in its place.
  - `:slot \"name\"` marks a board as a place to be filled after the build.
    A recipe that is a component has none: what gets placed is an instance,
    and an instance cannot be given children.
  - `:when` leaves the node out unless it holds: `{:param \"icon\"}`,
    `{:param \"kind\" :is \"x\"}` or `{:variant \"Kind\" :is \"Primary\"}`.
  - `:repeat` builds the node that many times: a number, or `{:param n}`.
  - `:vary` is `[{:on {:Kind \"Secondary\"} :set {…}}]`: what this node has
    instead in the variants that match; `:set` takes `:tokens`, `:width`,
    `:height`, `:layout`, `:layoutChild` and `:text`. In a repeated node an
    entry may say `:at 1` in place of `:on`: what it has instead at that
    turn, as the tab that is the open one.

  Where a number is taken, `{:param \"name\"}` stands for a param's value. In
  a string, `{name}` does, and `{index}` is the turn of the nearest repeated
  node, from 1.

  Nothing here says where a recipe comes from or how it is stored."
  (:require
   [app.common.agent.tokens :as cato]
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]))

(def version 1)

(def categories #{"control" "container" "navigation" "section" "screen"})

(def placeholders #{"icon" "image"})

(def max-nodes 80)

(def max-depth 6)

(def max-repeat 12)

(def max-axes 3)

(def max-members 24)

;; --- Token roles
;;
;; What a recipe may name in `:tokens`. A role is not a token: whoever builds
;; the recipe binds it to the file's own token for that role when it has one,
;; and to the default here when it has not.

(def roles
  {"color.background"    {:type :color :default "#ffffff"}
   "color.surface"       {:type :color :default "#ffffff"}
   "color.surface-muted" {:type :color :default "#f3f4f6"}
   "color.border"        {:type :color :default "#d1d5db"}
   "color.text"          {:type :color :default "#111827"}
   "color.text-muted"    {:type :color :default "#6b7280"}
   "color.primary"       {:type :color :default "#2563eb"}
   "color.on-primary"    {:type :color :default "#ffffff"}
   "color.danger"        {:type :color :default "#dc2626"}
   "color.success"       {:type :color :default "#16a34a"}
   "color.warning"       {:type :color :default "#d97706"}
   "color.info"          {:type :color :default "#0284c7"}
   "spacing.xs"          {:type :spacing :default 4}
   "spacing.sm"          {:type :spacing :default 8}
   "spacing.md"          {:type :spacing :default 12}
   "spacing.lg"          {:type :spacing :default 16}
   "spacing.xl"          {:type :spacing :default 24}
   "spacing.2xl"         {:type :spacing :default 32}
   "spacing.3xl"         {:type :spacing :default 48}
   "radius.sm"           {:type :border-radius :default 4}
   "radius.md"           {:type :border-radius :default 8}
   "radius.lg"           {:type :border-radius :default 12}
   "radius.full"         {:type :border-radius :default 999}
   "border-width.sm"     {:type :stroke-width :default 1}
   "border-width.md"     {:type :stroke-width :default 2}
   "font-size.xs"        {:type :font-size :default 12}
   "font-size.sm"        {:type :font-size :default 14}
   "font-size.md"        {:type :font-size :default 16}
   "font-size.lg"        {:type :font-size :default 20}
   "font-size.xl"        {:type :font-size :default 28}
   "font-size.2xl"       {:type :font-size :default 40}
   "font-weight.regular" {:type :font-weight :default 400}
   "font-weight.medium"  {:type :font-weight :default 500}
   "font-weight.bold"    {:type :font-weight :default 700}
   "font-family.body"    {:type :font-family :default "Source Sans Pro"}
   "font-family.heading" {:type :font-family :default "Source Sans Pro"}
   "shadow.sm"           {:type :shadow
                          :default [{:offsetX 0 :offsetY 1 :blur 2 :spread 0 :color "rgba(15, 23, 42, 0.08)"}]}
   "shadow.md"           {:type :shadow
                          :default [{:offsetX 0 :offsetY 4 :blur 12 :spread 0 :color "rgba(15, 23, 42, 0.12)"}]}
   "shadow.lg"           {:type :shadow
                          :default [{:offsetX 0 :offsetY 12 :blur 32 :spread 0 :color "rgba(15, 23, 42, 0.18)"}]}})

(def role-kinds
  "What the roles are about, and the token type of each kind: the whole
  vocabulary recipes and token packs share. A pack fills every role; a
  recipe names only roles."
  {"color"        :color
   "spacing"      :spacing
   "radius"       :border-radius
   "border-width" :stroke-width
   "font-size"    :font-size
   "font-weight"  :font-weight
   "font-family"  :font-family
   "shadow"       :shadow})

(defn role-kind
  "The kind of a role name: what comes before its first dot."
  [role]
  (first (str/split role #"\." 2)))

(def shorthands
  "A property of `:tokens` that stands for several a token binds to."
  {"padding"      ["paddingTop" "paddingRight" "paddingBottom" "paddingLeft"]
   "paddingX"     ["paddingLeft" "paddingRight"]
   "paddingY"     ["paddingTop" "paddingBottom"]
   "gap"          ["rowGap" "columnGap"]
   "borderRadius" ["borderRadiusTopLeft" "borderRadiusTopRight"
                   "borderRadiusBottomRight" "borderRadiusBottomLeft"]})

(defn token-properties
  "The properties a token binds to that `property` of `:tokens` stands for."
  [property]
  (or (get shorthands property) [property]))

;; --- Small checks
;;
;; Each answers nil, or a sentence that names what is wrong and where.

(def ^:private name-re #"^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

(def ^:private param-name-re #"^[a-z][a-zA-Z0-9]*$")

(def ^:private placeholder-re #"\{([A-Za-z][A-Za-z0-9]*)\}")

(defn- listed
  [names]
  (str/join ", " (sort (map name names))))

(defn- key-problem
  [where m allowed]
  (when-let [k (first (remove allowed (keys m)))]
    (dm/str where " has \"" (name k) "\", which is not part of the format — it takes: "
            (listed allowed))))

(defn- text?
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- param-ref?
  [v]
  (and (map? v) (contains? v :param)))

(defn- whole?
  [v]
  (and (number? v) (== v (long v))))

;; --- Params

(def ^:private param-types #{"text" "number" "boolean" "choice"})

(defn value-problem
  "Why `v` is not a value for `param`, or nil."
  [{:keys [type options min max] :as param} v]
  (let [label (dm/str "\"" (:name param) "\"")]
    (case type
      "text"    (when-not (string? v) (dm/str label " takes text"))
      "boolean" (when-not (boolean? v) (dm/str label " takes true or false"))
      "choice"  (when-not (some #(= v %) options)
                  (dm/str label " takes one of: " (str/join ", " options)))
      "number"  (cond
                  (not (number? v)) (dm/str label " takes a number")
                  (and (some? min) (< v min)) (dm/str label " is at least " min)
                  (and (some? max) (> v max)) (dm/str label " is at most " max))
      nil)))

(defn- param-problem
  [where {:keys [type options min max] :as param}]
  (cond
    (not (map? param))
    (dm/str where " is not a param object")

    (key-problem where param #{:name :type :default :description :options :min :max})
    (key-problem where param #{:name :type :default :description :options :min :max})

    (not (and (string? (:name param)) (re-matches param-name-re (:name param))))
    (dm/str where " needs a `name` of letters and digits that starts in lower case")

    (= "index" (:name param))
    (dm/str where " is named \"index\", which is the turn of a repeated node")

    (not (contains? param-types type))
    (dm/str where " has type \"" type "\" — use one of: " (listed param-types))

    (and (some? (:description param)) (not (string? (:description param))))
    (dm/str where " has a `description` that is not text")

    (and (not= "choice" type) (contains? param :options))
    (dm/str where " has `options`, which only a choice takes")

    (and (not= "number" type) (or (contains? param :min) (contains? param :max)))
    (dm/str where " has `min` or `max`, which only a number takes")

    (and (= "choice" type)
         (not (and (vector? options) (seq options) (every? text? options)
                   (apply distinct? options))))
    (dm/str where " needs `options`: the different values it may take, as text")

    (not (every? #(or (nil? %) (number? %)) [min max]))
    (dm/str where " has a `min` or `max` that is not a number")

    (and (some? min) (some? max) (> min max))
    (dm/str where " has a `min` above its `max`")

    (not (contains? param :default))
    (dm/str where " needs a `default`")

    (some? (value-problem param (:default param)))
    (dm/str where " has a default that does not fit: " (value-problem param (:default param)))))

(defn- params-problem
  [params]
  (cond
    (nil? params)
    nil

    (not (vector? params))
    "params is not a list"

    :else
    (or (some (fn [[i param]] (param-problem (dm/str "params[" i "]") param))
              (map-indexed vector params))
        (when-let [twice (some (fn [[nm n]] (when (> n 1) nm))
                               (frequencies (map :name params)))]
          (dm/str "params has two called \"" twice "\"")))))

;; --- Variant axes

(defn- plain-name?
  [v]
  (and (text? v) (not (str/includes? v "/"))))

(defn- axes-problem
  [axes]
  (cond
    (nil? axes)
    nil

    (not (and (vector? axes) (seq axes)))
    "variants is not a list of axes"

    (> (count axes) max-axes)
    (dm/str "variants has more than " max-axes " axes")

    :else
    (or (some (fn [[i {:keys [values] :as axis}]]
                (let [where (dm/str "variants[" i "]")]
                  (cond
                    (not (map? axis))
                    (dm/str where " is not an axis object")

                    (key-problem where axis #{:name :values})
                    (key-problem where axis #{:name :values})

                    (not (plain-name? (:name axis)))
                    (dm/str where " needs a `name` with no \"/\" in it")

                    (not (and (vector? values) (>= (count values) 2)
                              (every? plain-name? values) (apply distinct? values)))
                    (dm/str where " needs `values`: two or more different names,"
                            " with no \"/\" in them"))))
              (map-indexed vector axes))
        (when-not (apply distinct? (map :name axes))
          "variants has two axes with the same name")
        (when (> (reduce * (map #(count (:values %)) axes)) max-members)
          (dm/str "variants make more than " max-members
                  " members — drop an axis or a value")))))

;; --- Values inside the tree
;;
;; `ctx` is what a node may refer to: `:params` by name, `:axes` (name → its
;; values), whether it is `:repeated?`, and the `:lookup` of other recipes.

(defn- number-problem
  "A number, or a number param."
  [{:keys [params]} where v]
  (cond
    (param-ref? v)
    (let [param (get params (:param v))]
      (cond
        (not= #{:param} (set (keys v))) (dm/str where " takes {param: name} and nothing else")
        (nil? param) (dm/str where " names \"" (:param v) "\", which is not a param")
        (not= "number" (:type param)) (dm/str where " needs a number, and \""
                                              (:param v) "\" is a " (:type param))))

    (not (and (number? v) (pos? v)))
    (dm/str where " is not a number above zero")))

(defn- string-problem
  "Text whose `{placeholders}` are all params, or `{index}` in a repeat."
  [{:keys [params repeated?]} where v]
  (if-not (string? v)
    (dm/str where " is not text")
    (some (fn [[_ nm]]
            (cond
              (contains? params nm) nil
              (and (= "index" nm) repeated?) nil
              (= "index" nm) (dm/str where " uses {index} outside a repeated node")
              :else (dm/str where " uses {" nm "}, which is not a param")))
          (re-seq placeholder-re v))))

(defn- condition-problem
  [{:keys [params axes]} where c]
  (cond
    (not (map? c))
    (dm/str where " is not a condition object")

    (key-problem where c #{:param :variant :is})
    (key-problem where c #{:param :variant :is})

    (= (contains? c :param) (contains? c :variant))
    (dm/str where " needs one of `param` or `variant`")

    (contains? c :variant)
    (let [values (get axes (:variant c))]
      (cond
        (nil? values) (dm/str where " names \"" (:variant c) "\", which is not a variant axis")
        (not (some #(= (:is c) %) values))
        (dm/str where " needs `is`: one of " (str/join ", " values))))

    :else
    (let [param (get params (:param c))]
      (cond
        (nil? param)
        (dm/str where " names \"" (:param c) "\", which is not a param")

        (and (not (contains? c :is)) (not= "boolean" (:type param)))
        (dm/str where " needs `is`, as \"" (:param c) "\" is not a boolean")

        (and (contains? c :is) (some? (value-problem param (:is c))))
        (dm/str where " can never hold: " (value-problem param (:is c)))))))

(defn- repeat-problem
  [{:keys [params]} where v]
  (if (param-ref? v)
    (let [param (get params (:param v))]
      (cond
        (not= #{:param} (set (keys v)))
        (dm/str where " takes {param: name} and nothing else")

        (nil? param)
        (dm/str where " names \"" (:param v) "\", which is not a param")

        (not (and (= "number" (:type param))
                  (whole? (:min param)) (>= (:min param) 0)
                  (whole? (:max param)) (<= (:max param) max-repeat)))
        (dm/str where " needs a number param with a whole `min` of 0 or more"
                " and a whole `max` of " max-repeat " or less")))
    (when-not (and (whole? v) (<= 1 v max-repeat))
      (dm/str where " is not a whole number from 1 to " max-repeat))))

(defn- tokens-problem
  [where tokens]
  (if-not (map? tokens)
    (dm/str where " is not a map of property to role")
    (some (fn [[k role-name]]
            (let [property (name k)
                  role     (get roles role-name)]
              (cond
                (not (every? cato/token-attr (token-properties property)))
                (dm/str where "." property " is not a property a token binds to")

                (nil? role)
                (dm/str where "." property " names \"" role-name
                        "\", which is not a role — a recipe names a role and"
                        " never a value. Roles: " (listed (keys roles)))

                (some? (cato/application-problem {:name role-name :type (:type role)}
                                                 (token-properties property)))
                (dm/str where "." property " cannot take \"" role-name "\", a "
                        (name (:type role)) " role"))))
          tokens)))

(def ^:private layout-keys
  #{:type :dir :alignItems :justifyContent :rowGap :columnGap :padding :wrap :columns})

(def ^:private layout-child-keys
  #{:horizontalSizing :verticalSizing :alignSelf :margin :absolute :zIndex
    :minWidth :maxWidth :minHeight :maxHeight})

(defn- layout-problem
  [where layout whole-layout?]
  (cond
    (not (map? layout))
    (dm/str where " is not a layout object")

    (key-problem where layout layout-keys)
    (key-problem where layout layout-keys)

    (and whole-layout? (not (contains? #{"flex" "grid"} (:type layout))))
    (dm/str where " needs `type`: flex or grid")))

(defn- layout-child-problem
  [where layout-child]
  (cond
    (not (map? layout-child))
    (dm/str where " is not a layout-child object")

    (key-problem where layout-child layout-child-keys)
    (key-problem where layout-child layout-child-keys)))

(defn- fields-problem
  "The fields a node and a `:set` share."
  [ctx where fields full?]
  (or (when (contains? fields :width) (number-problem ctx (dm/str where ".width") (:width fields)))
      (when (contains? fields :height) (number-problem ctx (dm/str where ".height") (:height fields)))
      (when (contains? fields :text) (string-problem ctx (dm/str where ".text") (:text fields)))
      (when (contains? fields :tokens) (tokens-problem (dm/str where ".tokens") (:tokens fields)))
      (when (contains? fields :layout) (layout-problem (dm/str where ".layout") (:layout fields) full?))
      (when (contains? fields :layoutChild)
        (layout-child-problem (dm/str where ".layoutChild") (:layoutChild fields)))))

;; --- Nodes

(def ^:private common-keys #{:type :name :when :repeat :layoutChild})

(def ^:private node-keys
  {"board"   (into common-keys [:width :height :tokens :vary :layout :children :slot])
   "rect"    (into common-keys [:width :height :tokens :vary :placeholder])
   "ellipse" (into common-keys [:width :height :tokens :vary :placeholder])
   "text"    (into common-keys [:width :height :tokens :vary :text :align])
   "image"   (into common-keys [:width :height :tokens :vary :url])
   "recipe"  (into common-keys [:use :with :variant :vary :placeholder])})

(def ^:private set-keys
  {"board"   #{:tokens :width :height :layout :layoutChild}
   "rect"    #{:tokens :width :height :layoutChild}
   "ellipse" #{:tokens :width :height :layoutChild}
   "text"    #{:tokens :width :height :layoutChild :text}
   "image"   #{:tokens :width :height :layoutChild}
   "recipe"  #{:variant :with :layoutChild}})

(defn- vary-problem
  [{:keys [axes] :as ctx} where kind vary]
  (cond
    (not (vector? vary))
    (dm/str where " is not a list")

    :else
    (some (fn [[i entry]]
            (let [where (dm/str where "[" i "]")]
              (cond
                (not (map? entry))
                (dm/str where " is not an object")

                (key-problem where entry #{:on :at :set})
                (key-problem where entry #{:on :at :set})

                (= (contains? entry :on) (contains? entry :at))
                (dm/str where " needs one of `on` (axis values) or `at` (a turn of a repeat)")

                (and (contains? entry :at) (not (:repeated? ctx)))
                (dm/str where " has `at` outside a repeated node")

                (and (contains? entry :at) (not (and (whole? (:at entry)) (<= 1 (:at entry) max-repeat))))
                (dm/str where ".at is not a whole number from 1 to " max-repeat)

                (and (contains? entry :on) (empty? axes))
                (dm/str where " has `on`, but the recipe has no variants")

                (and (contains? entry :on) (not (and (map? (:on entry)) (seq (:on entry)))))
                (dm/str where " needs `on`: the axis values it applies to")

                (not (and (map? (:set entry)) (seq (:set entry))))
                (dm/str where " needs `set`: what the node has instead")

                :else
                (or (some (fn [[axis value]]
                            (let [values (get axes (name axis))]
                              (cond
                                (nil? values)
                                (dm/str where ".on names \"" (name axis)
                                        "\", which is not a variant axis")
                                (not (some #(= value %) values))
                                (dm/str where ".on." (name axis) " is not one of: "
                                        (str/join ", " values)))))
                          (:on entry))
                    (key-problem (dm/str where ".set") (:set entry) (get set-keys kind))
                    (fields-problem ctx (dm/str where ".set") (:set entry) false)))))
          (map-indexed vector vary))))

(defn- argument-problem
  "A value handed to another recipe's param: a plain value, or one of ours."
  [{:keys [params] :as ctx} where target v]
  (cond
    (param-ref? v)
    (let [param (get params (:param v))]
      (cond
        (not= #{:param} (set (keys v))) (dm/str where " takes {param: name} and nothing else")
        (nil? param) (dm/str where " names \"" (:param v) "\", which is not a param")
        (and target (not= (:type target) (:type param)))
        (dm/str where " hands a " (:type param) " to a " (:type target) " param")))

    (not (or (string? v) (number? v) (boolean? v)))
    (dm/str where " is not text, a number or true/false")

    (string? v)
    (or (string-problem ctx where v)
        ;; a placeholder is filled in before the other recipe reads the value
        (when (and target (not (re-find placeholder-re v)))
          (some->> (value-problem target v) (dm/str where ": "))))

    (some? target)
    (some->> (value-problem target v) (dm/str where ": "))))

(defn- use-problem
  [{:keys [lookup self] :as ctx} where {:keys [use with variant]}]
  (let [target  (when (and lookup (string? use)) (lookup use))
        tparams (into {} (map (juxt :name identity)) (:params target))
        taxes   (into {} (map (juxt :name :values)) (:variants target))]
    (cond
      (not (and (string? use) (re-matches name-re use)))
      (dm/str where " needs `use`: the name of a recipe")

      (= use self)
      (dm/str where " uses the recipe it is part of")

      (and (some? with) (not (map? with)))
      (dm/str where ".with is not a map of param to value")

      (and (some? variant) (not (map? variant)))
      (dm/str where ".variant is not a map of axis to value")

      (and (some? lookup) (nil? target))
      (dm/str where " uses \"" use "\", and there is no recipe of that name")

      :else
      (or (some (fn [[k v]]
                  (let [where (dm/str where ".with." (name k))]
                    (if (and target (not (contains? tparams (name k))))
                      (dm/str where " is not a param of \"" use "\""
                              (when (seq tparams) (dm/str " — it takes: " (listed (keys tparams)))))
                      (argument-problem ctx where (get tparams (name k)) v))))
                with)
          (some (fn [[k v]]
                  (let [where (dm/str where ".variant." (name k))]
                    (cond
                      (not (string? v))
                      (dm/str where " is not the name of a value")

                      (and target (not (contains? taxes (name k))))
                      (dm/str where " is not a variant axis of \"" use "\"")

                      (and target (not (some #(= v %) (get taxes (name k)))))
                      (dm/str where " is not one of: " (str/join ", " (get taxes (name k)))))))
                variant)))))

(defn- node-problem
  [ctx where node depth]
  (let [kind (:type node)
        ctx  (cond-> ctx
               (and (map? node) (contains? node :repeat)) (assoc :repeated? true))]
    (cond
      (not (map? node))
      (dm/str where " is not a node object")

      (> depth max-depth)
      (dm/str where " is deeper than " max-depth " levels — move part of it to a"
              " recipe of its own and use it here")

      (not (contains? node-keys kind))
      (dm/str where " has type \"" kind "\" — use one of: " (listed (keys node-keys)))

      (key-problem where node (get node-keys kind))
      (key-problem where node (get node-keys kind))

      (and (contains? node :name) (not (text? (:name node))))
      (dm/str where ".name is not text")

      (and (contains? node :name) (some? (string-problem ctx (dm/str where ".name") (:name node))))
      (string-problem ctx (dm/str where ".name") (:name node))

      (and (= "text" kind) (not (contains? node :text)))
      (dm/str where " is a text and needs `text` (the words)")

      (and (= "image" kind) (some? (string-problem ctx (dm/str where ".url") (:url node))))
      (string-problem ctx (dm/str where ".url") (:url node))

      (and (= "image" kind) (not (re-find #"^(https?://|\{[A-Za-z][A-Za-z0-9]*\}$)" (:url node))))
      (dm/str where ".url is neither an absolute http(s) address nor one {param}")

      (and (contains? node :align) (not (contains? #{"left" "center" "right"} (:align node))))
      (dm/str where ".align is not left, center or right")

      (and (contains? node :placeholder) (not (contains? placeholders (:placeholder node))))
      (dm/str where ".placeholder is not one of: " (listed placeholders))

      (and (contains? node :slot) (not (text? (:slot node))))
      (dm/str where ".slot is not a name")

      (and (contains? node :children) (not (vector? (:children node))))
      (dm/str where ".children is not a list")

      :else
      (or (when (contains? node :when)
            (condition-problem ctx (dm/str where ".when") (:when node)))
          (when (contains? node :repeat)
            (repeat-problem ctx (dm/str where ".repeat") (:repeat node)))
          (if (= "recipe" kind)
            (or (use-problem ctx where node)
                (when (contains? node :layoutChild)
                  (layout-child-problem (dm/str where ".layoutChild") (:layoutChild node))))
            (fields-problem ctx where node true))
          (when (contains? node :vary)
            (vary-problem ctx (dm/str where ".vary") kind (:vary node)))
          (some (fn [[i child]]
                  (node-problem ctx (dm/str where ".children[" i "]") child (inc depth)))
                (map-indexed vector (:children node)))))))

(defn- nodes
  [tree]
  (tree-seq map? #(filter map? (:children %)) tree))

(defn- tree-problem
  [ctx tree]
  (cond
    (not (map? tree))
    "tree is missing — the root node, a board"

    (not= "board" (:type tree))
    "tree is not a board — the root holds the rest and is what gets placed"

    (or (contains? tree :when) (contains? tree :repeat))
    "tree has `when` or `repeat`, which the root cannot take"

    (> (count (nodes tree)) max-nodes)
    (dm/str "tree has more than " max-nodes " nodes — move part of it to a recipe"
            " of its own and use it here")

    :else
    (or (node-problem ctx "tree" tree 1)
        (when-let [twice (some (fn [[slot n]] (when (> n 1) slot))
                               (frequencies (keep :slot (nodes tree))))]
          (dm/str "tree has two slots called \"" twice "\"")))))

;; --- Recipes that use recipes

(defn uses
  "The names of the recipes this one places."
  [recipe]
  (into [] (comp (filter #(= "recipe" (:type %))) (keep :use) (distinct))
        (nodes (:tree recipe))))

(defn- loop-problem
  "A recipe that, through the ones it uses, comes back to itself."
  [recipe lookup]
  (letfn [(walk [nm path]
            (cond
              (= nm (:name recipe)) (conj path nm)
              (some #(= nm %) path) nil
              :else (some #(walk % (conj path nm)) (some-> (lookup nm) uses))))]
    (when-let [path (some #(walk % [(:name recipe)]) (uses recipe))]
      (dm/str "uses itself: " (str/join " → " path)))))

;; --- The whole recipe

(def ^:private recipe-keys
  #{:recipe :name :title :purpose :category :keywords :params :variants :component :tree})

(defn- recipe-problem
  [{:keys [params variants keywords] :as recipe} lookup]
  (cond
    (not (map? recipe))
    "not a recipe object"

    (not= version (:recipe recipe))
    (dm/str "`recipe` is " (pr-str (:recipe recipe)) " — the format version read here is "
            version)

    (key-problem "the recipe" recipe recipe-keys)
    (key-problem "the recipe" recipe recipe-keys)

    (not (and (string? (:name recipe)) (re-matches name-re (:name recipe))))
    "needs a `name` in kebab-case, such as \"pricing-table\""

    (not (text? (:title recipe)))
    "needs a `title`"

    (not (and (text? (:purpose recipe)) (<= (count (:purpose recipe)) 240)))
    "needs a `purpose`: what it is for, in 240 characters or less"

    (not (contains? categories (:category recipe)))
    (dm/str "needs a `category`: one of " (listed categories))

    (not (or (nil? keywords) (and (vector? keywords) (every? text? keywords))))
    "keywords is not a list of words"

    (and (contains? recipe :component) (not (boolean? (:component recipe))))
    "component is not true or false"

    (and (some? variants) (false? (:component recipe)))
    "has variants, so it cannot say component: false"

    :else
    (or (params-problem params)
        (axes-problem variants)
        (tree-problem {:params (into {} (map (juxt :name identity)) params)
                       :axes   (into {} (map (juxt :name :values)) variants)
                       :lookup lookup
                       :self   (:name recipe)}
                      (:tree recipe))
        (when (and (or (some? variants) (true? (:component recipe)))
                   (some :slot (nodes (:tree recipe))))
          "has a slot and is a component — an instance cannot be filled, so one of the two has to go")
        (when lookup (loop-problem recipe lookup)))))

(defn problem
  "Why `recipe` is not a valid recipe, as a sentence that names what is wrong
  and where, or nil. With `lookup` — name → recipe, or nil — the recipes it
  uses are checked too: that they exist, take what they are handed, and do not
  lead back to it."
  ([recipe] (problem recipe nil))
  ([recipe lookup]
   (when-let [message (recipe-problem recipe lookup)]
     (if (and (map? recipe) (string? (:name recipe)))
       (dm/str "recipe \"" (:name recipe) "\": " message)
       (dm/str "recipe: " message)))))

;; --- What a recipe comes to
;;
;; A recipe, the values of its params and one member of its variants come to
;; a tree with nothing left to decide: no conditions, no repeats, no
;; placeholders. This is what gets built.

(defn- by-name
  "A map as it arrives, keyed by keyword or by text → keyed by text."
  [m]
  (into {} (map (fn [[k v]] [(name k) v])) m))

(defn- repeat-params
  [recipe]
  (into #{} (comp (keep :repeat) (filter param-ref?) (map :param)) (nodes (:tree recipe))))

;; A param that changes what layers there are — how many times a node
;; repeats, whether one is there, how big one is — and not only words.

(defn structural-params
  "The names of the params of `recipe` that change its structure."
  [recipe]
  (into #{}
        (mapcat (fn [node]
                  (concat (keep #(when (param-ref? %) (:param %))
                                [(:repeat node) (:width node) (:height node)])
                          (when (contains? (:when node) :param) [(:param (:when node))])
                          (keep #(when (param-ref? %) (:param %))
                                (mapcat (fn [entry] [(-> entry :set :width) (-> entry :set :height)])
                                        (:vary node))))))
        (nodes (:tree recipe))))

(defn arguments-problem
  "Why `args` — param → value — cannot be handed to `recipe`, or nil."
  [recipe args]
  (let [params (into {} (map (juxt :name identity)) (:params recipe))
        counts (repeat-params recipe)]
    (if-not (or (nil? args) (map? args))
      "params is not a map of param to value"
      (some (fn [[nm v]]
              (let [param (get params nm)]
                (cond
                  (nil? param)
                  (dm/str "\"" nm "\" is not a param of \"" (:name recipe) "\""
                          (if (seq params)
                            (dm/str " — it takes: " (listed (keys params)))
                            ", which takes none"))

                  (some? (value-problem param v))
                  (value-problem param v)

                  (and (contains? counts nm) (not (whole? v)))
                  (dm/str "\"" nm "\" takes a whole number"))))
            (by-name args)))))

(defn members
  "Every member of the recipe's variants, as axis → value, the one the tree
  describes first. A recipe with no variants has the one empty member."
  [recipe]
  (reduce (fn [members {:keys [values] :as axis}]
            (for [member members value values]
              (assoc member (:name axis) value)))
          [{}]
          (:variants recipe)))

(defn member-problem
  "Why `variant` — axis → value — names no member of `recipe`, or nil."
  [recipe variant]
  (let [axes (into {} (map (juxt :name :values)) (:variants recipe))]
    (if-not (or (nil? variant) (map? variant))
      "variant is not a map of axis to value"
      (some (fn [[axis value]]
              (let [values (get axes axis)]
                (cond
                  (nil? values)
                  (dm/str "\"" axis "\" is not a variant axis of \"" (:name recipe) "\""
                          (if (seq axes)
                            (dm/str " — it has: " (str/join ", " (keys axes)))
                            ", which has no variants"))

                  (not (some #(= value %) values))
                  (dm/str "\"" axis "\" is one of: " (str/join ", " values)))))
            (by-name variant)))))

(defn member
  "The whole member that `variant` names: what it leaves out is the first
  value of that axis."
  [recipe variant]
  (merge (first (members recipe)) (by-name variant)))

(defn- shown
  "A value as it reads inside a text."
  [v]
  (if (whole? v) (str (long v)) (str v)))

(defn- fill-in
  [{:keys [args index]} s]
  (str/replace s placeholder-re
               (fn [[_ nm]]
                 (shown (if (and (= "index" nm) (some? index)) index (get args nm))))))

(defn- value-of
  [{:keys [args]} v]
  (if (param-ref? v) (get args (:param v)) v))

(defn- holds?
  [{:keys [args member]} {:keys [param variant] :as c}]
  (cond
    (some? variant)      (= (:is c) (get member variant))
    (contains? c :is)    (= (:is c) (get args param))
    :else                (true? (get args param))))

(defn- varied
  "The node with what its `:vary` gives it in this member."
  [{:keys [member index]} node]
  (reduce (fn [node {:keys [on] :as entry}]
            (if (if (contains? entry :at)
                  (= (:at entry) index)
                  (every? (fn [[axis value]] (= value (get member (name axis)))) on))
              (reduce-kv (fn [node k v]
                           (if (contains? #{:tokens :layout :layoutChild :variant :with} k)
                             (update node k merge v)
                             (assoc node k v)))
                         node
                         (:set entry))
              node))
          (dissoc node :vary)
          (:vary node)))

(declare expand-children)

(defn- expand-node
  [ctx node]
  (let [node (varied ctx node)]
    (cond-> (dissoc node :when :repeat)
      (contains? node :name)     (update :name #(fill-in ctx %))
      (contains? node :text)     (update :text #(fill-in ctx %))
      (contains? node :url)      (update :url #(fill-in ctx %))
      (contains? node :width)    (update :width #(value-of ctx %))
      (contains? node :height)   (update :height #(value-of ctx %))
      (contains? node :with)     (update :with (fn [with]
                                                 (into {}
                                                       (map (fn [[k v]]
                                                              (let [v (value-of ctx v)]
                                                                [(name k) (if (string? v) (fill-in ctx v) v)])))
                                                       with)))
      (contains? node :variant)  (update :variant by-name)
      (contains? node :children) (update :children #(expand-children ctx %)))))

(defn- expand-children
  [ctx children]
  (into []
        (mapcat (fn [child]
                  (cond
                    (and (contains? child :when) (not (holds? ctx (:when child))))
                    nil

                    (contains? child :repeat)
                    (map #(expand-node (assoc ctx :index (inc %)) child)
                         (range (long (value-of ctx (:repeat child)))))

                    :else
                    [(expand-node ctx child)])))
        children))

(defn expand
  "The tree `recipe` comes to with `args` (param → value, over the defaults)
  in the member `variant` names. The recipe and what it is handed must have
  no problem. A `recipe` node stays one, with what it hands over worked out."
  [recipe args variant]
  (expand-node {:args   (merge (into {} (map (juxt :name :default)) (:params recipe))
                               (by-name args))
                :member (member recipe variant)}
               (:tree recipe)))

;; --- Platform profiles
;;
;; A recipe says nothing of a platform. What a platform asks of it — other
;; sizes, other spacing, another variant of a control — is a profile: a
;; document of its own that names recipes and says what each has instead.
;;
;;   {:profile 1
;;    :name    "ios"
;;    :title   "iOS"
;;    :purpose "…"
;;    :recipes {"top-bar" {:params  {:links 0}            other defaults
;;                         :adjust  [{:layer "Brand"      a layer, by name
;;                                    :set {:tokens {:fontSize "font-size.md"}}}
;;                                   {:layer "Action"     a recipe it places
;;                                    :set {:variant {:Size "Small"}}}]}
;;              "button"  {:variant {:Size "Medium"}}}}   the variant placed
;;
;; `:set` takes what `:vary` takes, and `:variant` on a layer that places a
;; recipe. A component is built once in a file, for every platform: a profile
;; picks its variant and leaves its layers alone. A recipe a profile does not
;; name is as it was.

(defn- profile-entry-problem
  [where entry recipe]
  (let [component? (or (seq (:variants recipe)) (true? (:component recipe)))
        named      (group-by :name (nodes (:tree recipe)))
        ctx        {:params (into {} (map (juxt :name identity)) (:params recipe))
                    :axes   (into {} (map (juxt :name :values)) (:variants recipe))
                    :repeated? true}]
    (cond
      (not (map? entry))
      (dm/str where " is not an object")

      (key-problem where entry #{:params :variant :adjust})
      (key-problem where entry #{:params :variant :adjust})

      (some? (arguments-problem recipe (:params entry)))
      (dm/str where ".params: " (arguments-problem recipe (:params entry)))

      (some? (member-problem recipe (:variant entry)))
      (dm/str where ".variant: " (member-problem recipe (:variant entry)))

      (and (contains? entry :adjust) component?)
      (dm/str where " adjusts a component, which is built once for every platform — pick its"
              " `variant` here, and adjust the recipes that place it")

      (and (contains? entry :adjust) (not (vector? (:adjust entry))))
      (dm/str where ".adjust is not a list")

      :else
      (some (fn [[i {:keys [layer] :as adjust}]]
              (let [where (dm/str where ".adjust[" i "]")
                    found (get named layer)
                    kind  (:type (first found))]
                (cond
                  (not (map? adjust))
                  (dm/str where " is not an object")

                  (key-problem where adjust #{:layer :set})
                  (key-problem where adjust #{:layer :set})

                  (empty? found)
                  (dm/str where " names the layer \"" layer "\", and \"" (:name recipe)
                          "\" has none of that name — it has: "
                          (str/join ", " (distinct (keep :name (nodes (:tree recipe))))))

                  (not (and (map? (:set adjust)) (seq (:set adjust))))
                  (dm/str where " needs `set`: what the layer has instead")

                  (= "recipe" kind)
                  (or (key-problem (dm/str where ".set") (:set adjust) #{:variant :layoutChild})
                      (when-not (or (nil? (-> adjust :set :variant)) (map? (-> adjust :set :variant)))
                        (dm/str where ".set.variant is not a map of axis to value")))

                  :else
                  (or (key-problem (dm/str where ".set") (:set adjust) (get set-keys kind))
                      (fields-problem ctx (dm/str where ".set") (:set adjust) false)))))
            (map-indexed vector (:adjust entry))))))

(defn profile-problem
  "Why `profile` is not a valid platform profile over `recipes` (name →
  recipe), as a sentence that names what is wrong and where, or nil."
  [profile recipes]
  (let [problem
        (cond
          (not (map? profile))
          "not a profile object"

          (not= version (:profile profile))
          (dm/str "`profile` is " (pr-str (:profile profile)) " — the format version read here is "
                  version)

          (key-problem "the profile" profile #{:profile :name :title :purpose :recipes})
          (key-problem "the profile" profile #{:profile :name :title :purpose :recipes})

          (not (and (string? (:name profile)) (re-matches name-re (:name profile))))
          "needs a `name` in kebab-case, such as \"ios\""

          (not (text? (:title profile)))
          "needs a `title`"

          (not (text? (:purpose profile)))
          "needs a `purpose`"

          (not (map? (:recipes profile)))
          "needs `recipes`: a map of recipe name to what it has instead"

          :else
          (some (fn [[k entry]]
                  (let [recipe (get recipes (name k))]
                    (if (nil? recipe)
                      (dm/str "recipes names \"" (name k) "\", and there is no recipe of that name")
                      (profile-entry-problem (dm/str "recipes." (name k)) entry recipe))))
                (:recipes profile)))]
    (when (some? problem)
      (if (and (map? profile) (string? (:name profile)))
        (dm/str "profile \"" (:name profile) "\": " problem)
        (dm/str "profile: " problem)))))

(defn- profile-entry
  [profile recipe]
  (some (fn [[k entry]] (when (= (:name recipe) (name k)) entry)) (:recipes profile)))

(defn profile-variant
  "The variant of `recipe` that `profile` places, as axis → value, or nil."
  [profile recipe]
  (some-> (profile-entry profile recipe) :variant by-name not-empty))

(defn with-profile
  "`recipe` as `profile` has it: its params' defaults and its layers
  adjusted. A recipe the profile does not name comes back as it was."
  [recipe profile]
  (let [entry    (profile-entry profile recipe)
        defaults (by-name (:params entry))
        adjusts  (group-by :layer (:adjust entry))
        adjust   (fn [node]
                   (reduce (fn [node {:keys [set]}]
                             (reduce-kv (fn [node k v]
                                          (if (contains? #{:tokens :layout :layoutChild :variant} k)
                                            (update node k merge v)
                                            (assoc node k v)))
                                        node
                                        set))
                           node
                           (get adjusts (:name node))))
        walk     (fn walk [node]
                   (cond-> (adjust node)
                     (contains? node :children) (update :children #(mapv walk %))))]
    (if (nil? entry)
      recipe
      (cond-> (update recipe :tree walk)
        (seq defaults)
        (update :params (fn [params]
                          (mapv #(cond-> % (contains? defaults (:name %)) (assoc :default (get defaults (:name %))))
                                params)))))))
