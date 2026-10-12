;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.icons
  "The design agent's set_icons tool as a pure function; see
  `app.common.agent.write` for what a tool takes and answers.

  It puts real icons where placeholders are, many in one call. A placeholder
  is one of two things, and each has its way:

  - a plain shape, a grey box: the icon takes its place in its parent, at
    its size, and the box goes;
  - an instance of a component, as inside an instance of a list item, where
    nothing can be added or taken out: the icon becomes a component of its
    own, once per file, and the instance is swapped for one of it.

  Either way the icon takes the color token the placeholder had."
  (:require
   [app.common.agent.components :as cac]
   [app.common.agent.compose :as cap]
   [app.common.agent.media :as cam]
   [app.common.agent.structure :as cas]
   [app.common.agent.tokens :as cato]
   [app.common.agent.write :as caw]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(def ^:private max-icons 24)

(def ^:private default-size 20)

(defn- size-of
  [{:keys [size]}]
  (cam/icon-size (or size default-size)))

(defn set-icons-needs
  [{:keys [icons]}]
  (when (and (sequential? icons) (<= (count icons) max-icons))
    (into []
          (comp (filter map?)
                (filter #(nil? (cam/icon-id-problem (:icon %))))
                (map (fn [entry] {:kind :http :url (cam/icon-svg-url (:icon entry) (size-of entry))}))
                (distinct))
          icons)))

(defn- target-of
  "The shape an entry means: `shapeId`, or the one layer called `layer`
  under it."
  [objects {:keys [shapeId layer]}]
  (let [id (when (string? shapeId) (parse-uuid shapeId))]
    (if (and (some? id) (string? layer))
      (let [found (filter #(= layer (:name (get objects %))) (cfh/get-children-ids objects id))]
        (when (= 1 (count found))
          (get objects (first found))))
      (get objects id))))

(defn- entry-problem
  [objects i {:keys [icon] :as entry}]
  (let [where  (dm/str "icons[" i "]")
        target (when (map? entry) (target-of objects entry))]
    (cond
      (not (map? entry))
      (dm/str where " is not {shapeId, icon}")

      (some? (cam/icon-id-problem icon))
      (dm/str where ": " (str/replace (cam/icon-id-problem icon) #"^insert_icon: " ""))

      (nil? target)
      (dm/str where ": no shape with that id"
              (when (:layer entry) (dm/str ", or not exactly one layer called \"" (:layer entry) "\" under it"))
              " — check read_design")

      (ctc/main-instance? target)
      (dm/str where ": " (caw/shape-label target) " is a component's main — put the icon"
              " inside it, on its placeholder")

      (and (ctc/in-component-copy? target) (not (ctc/instance-head? target)))
      (dm/str where ": " (caw/shape-label target) " is a plain layer inside a component"
              " copy, where nothing can be replaced — set the icon on the component's main")

      (= uuid/zero (:id target))
      (dm/str where ": the page itself cannot be an icon"))))

(defn- icon-name
  "\"lucide:house\" → \"house\"."
  [icon]
  (or (second (str/split icon #":" 2)) icon))

(defn- icon-component
  "The component a file has for this icon, or nil."
  [data icon]
  (some (fn [component]
          (when (and (= (icon-name icon) (:name component)) (= "Icon" (:path component)))
            component))
        (ctkl/components-seq data)))

(defn- flow-index
  "Where `shape` is in its parent's flow, or nil when the parent has none."
  [objects shape]
  (let [parent (get objects (:parent-id shape))]
    (when (ctl/any-layout? parent)
      (let [order (if (ctl/reverse? parent) (:shapes parent) (reverse (:shapes parent)))]
        (first (keep-indexed (fn [i id] (when (= id (:id shape)) i)) order))))))

(defn- then
  [run f]
  (cap/then run f))

(defn- stopped?
  [run]
  (some? (:stopped run)))

(defn- colored
  "Binds the color token the placeholder had to what the icon is drawn
  with: its strokes, or its fills when it has no stroke."
  [run root-id token options]
  (if (or (stopped? run) (nil? token) (nil? root-id))
    run
    (let [objects (-> run :view :objects)
          drawn   (->> (cfh/get-children-ids-with-self objects root-id)
                       (map #(get objects %))
                       (remove #(or (cfh/frame-shape? %) (cfh/group-shape? %))))]
      (if (empty? drawn)
        run
        (then run
              (fn [view]
                (let [answer (cato/apply-tokens
                              view
                              {:applications
                               (mapv (fn [shape]
                                       {:shapeId (dm/str (:id shape))
                                        :tokenName token
                                        :properties [(if (seq (:strokes shape)) "strokeColor" "fill")]})
                                     drawn)}
                              options)]
                  ;; a token that does not fit leaves the icon as it came;
                  ;; what the host has to settle first is the host's
                  (if (contains? answer :problem) {:changes []} answer))))))))

(defn- inserted
  "The icon as shapes on the page → the run, its root id under `:last`."
  [run {:keys [icon] :as entry} nm {:keys [x y]} options]
  (then run #(cam/insert-icon % {:icon icon :size (size-of entry) :name nm :x x :y y} options)))

(defn- free-spot
  "A place on the page no shape is at, whatever it is inside of: an icon put
  where a board is would land in that board."
  [{:keys [objects]}]
  (let [rects (keep :selrect (vals (dissoc objects uuid/zero)))]
    (if (empty? rects)
      {:x 0 :y 0}
      {:x (+ 200 (apply max (map :x2 rects))) :y (apply min (map :y1 rects))})))

(defn- replace-plain
  "The icon in the place of a plain shape."
  [run target entry options]
  (let [objects (-> run :view :objects)
        token   (get-in target [:applied-tokens :fill])
        parent  (:parent-id target)
        index   (flow-index objects target)
        run     (inserted run entry (:name target) (free-spot (:view run)) options)
        id      (some-> run :last :id)]
    (if (stopped? run)
      run
      (-> run
          (cond-> (not= uuid/zero parent)
            (then #(cas/nest-shape % (cond-> {:shapeId id :parentId (dm/str parent)}
                                       (some? index) (assoc :index index))
                                   options)))
          (then #(caw/modify-shape % {:shapeId id :width (:width target) :height (:height target)} options))
          (then #(cas/delete-shape % {:shapeIds [(dm/str (:id target))]} options))
          (colored (parse-uuid id) token options)
          (update :set conj {:icon (:icon entry) :id id})))))

(defn- replace-instance
  "An instance swapped for one of the icon's own component, made now when
  the file has none."
  [run target entry options]
  (let [icon  (:icon entry)
        token (get-in target [:applied-tokens :fill])
        had   (icon-component (-> run :view :data) icon)
        run   (if (some? had)
                run
                (let [run (inserted run entry (dm/str "Icon / " (icon-name icon)) (free-spot (:view run)) options)
                      id  (some-> run :last :id)]
                  (cond-> run
                    (not (stopped? run))
                    ;; an icon is drawn in a box of its own units: it takes the
                    ;; size asked for before it becomes a component
                    (-> (then #(caw/modify-shape % {:shapeId id :width (size-of entry) :height (size-of entry)}
                                                 options))
                        (then #(cas/create-component % {:shapeIds [id]} options))
                        (update :components conj (dm/str "Icon / " (icon-name icon)))))))
        made  (when-not (stopped? run) (icon-component (-> run :view :data) icon))]
    (cond
      (stopped? run)
      run

      (nil? made)
      (assoc run :stopped {:problem (dm/str "the icon " icon " did not become a component")})

      :else
      (let [parent (:parent-id target)
            before (set (:shapes (get (-> run :view :objects) parent)))
            run    (then run #(cac/swap-component % {:shapeId (dm/str (:id target))
                                                     :componentId (dm/str (:id made))}
                                                  options))
            ;; the swap puts a new shape where the old one was
            new-id (when-not (stopped? run)
                     (let [objects (-> run :view :objects)]
                       (or (first (remove before (:shapes (get objects parent))))
                           ;; a swap may keep the shape and change what it is
                           (when (contains? objects (:id target)) (:id target)))))]
        (-> run
            (colored new-id token options)
            (update :set conj (cond-> {:icon icon}
                                (some? new-id) (assoc :id (dm/str new-id)))))))))

(defn set-icons
  "Many icons in ONE call. Every entry is checked before anything changes; a
  step that is refused part-way keeps what was done and says so."
  [{:keys [objects] :as view} {:keys [icons]} {:keys [resources parse-svg] :as options}]
  (let [problems (when (sequential? icons)
                   (into [] (keep-indexed (fn [i entry] (entry-problem objects i entry))) icons))
        missing  (when (sequential? icons)
                   (some (fn [entry]
                           (when (map? entry)
                             (nil? (get resources (cam/icon-svg-url (:icon entry) (size-of entry))))))
                         icons))]
    (cond
      (not (and (sequential? icons) (seq icons)))
      {:problem "set_icons: pass icons — an array of {shapeId, layer?, icon, size?}"}

      (> (count icons) max-icons)
      {:problem (dm/str "set_icons: at most " max-icons " icons per call")}

      (seq problems)
      {:problem (dm/str "set_icons: " (str/join " | " problems))}

      (or missing (nil? parse-svg))
      {:fallback "the icons of this call were not fetched"}

      :else
      (let [run (reduce (fn [run entry]
                          (if (stopped? run)
                            run
                            ;; the page as the icons before this one left it
                            (let [target (target-of (-> run :view :objects) entry)]
                              (cond
                                (nil? target)                run
                                (ctc/instance-head? target)  (replace-instance run target entry options)
                                :else                        (replace-plain run target entry options)))))
                        (assoc (cap/start view) :set [] :components [])
                        icons)
            stopped (:stopped run)]
        (cond
          (or (contains? stopped :fallback) (contains? stopped :pending))
          stopped

          :else
          {:changes (:changes run)
           :result (cond-> {:set (:set run)
                            :note (str "icons set — each took its placeholder's place, size "
                                       "and color token. Verify with render_board.")}
                     (seq (:components run))
                     (assoc :componentsCreated (:components run))

                     (some? stopped)
                     (assoc :error (dm/str "set_icons stopped after " (count (:set run)) " of "
                                           (count icons) ": " (:problem stopped))))})))))
