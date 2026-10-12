;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.compose
  "The design agent's composition tools — clone_shape and build_tree — as pure
  functions; see `app.common.agent.write` for what a tool takes and answers.

  Both do in one call what would otherwise take many: they run the single
  shape tools one after another, each on the file as the previous one left
  it, and answer with all of the changes together."
  (:require
   [app.common.agent.components :as cac]
   [app.common.agent.layout :as cal]
   [app.common.agent.media :as cam]
   [app.common.agent.structure :as cas]
   [app.common.agent.text :as catx]
   [app.common.agent.write :as caw]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as cfc]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.types.component :as ctc]
   [app.common.types.container :as ctn]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [cuerdas.core :as str]))

;; --- One tool after another
;;
;; A run keeps the view current and collects the changes of every step. A step
;; that answers `{:problem}` or `{:fallback}` stops the run: the answer is kept
;; under `:stopped` and the steps after it do nothing.

(defn start
  "A run that has done nothing yet."
  [view]
  {:view view :changes (caw/with-undo [] [])})

(defn- advance
  "The view after `changes`."
  [{:keys [file-id page-id data] :as view} changes]
  (if (empty? changes)
    view
    (let [data (cfc/process-changes data changes false)]
      (-> view
          (assoc :data data)
          (assoc :objects (get-in data [:pages-index page-id :objects]))
          (update :libraries assoc-in [file-id :data] data)))))

(defn then
  "Runs `f` — view → a tool answer — unless the run has stopped. → the run,
  with the step's result under `:last`."
  [{:keys [view stopped] :as run} f]
  (if (some? stopped)
    run
    (let [answer (f view)]
      (if (or (contains? answer :problem) (contains? answer :fallback) (contains? answer :pending))
        (assoc run :stopped answer)
        (-> run
            (update :changes caw/then-changes (:changes answer))
            (assoc :view (advance view (:changes answer)))
            (assoc :last (:result answer)))))))

;; --- clone_shape

(def ^:private max-clones 12)

(defn clone-problem
  "Why `clone_shape` cannot run, or nil."
  [objects id pid clones]
  (let [shape  (get objects id)
        parent (when pid (get objects pid))]
    (cond
      (nil? id)
      "clone_shape: missing or invalid shapeId"

      (nil? shape)
      (dm/str "clone_shape: no shape on this page with id " (str id)
              " — check read_design")

      (not (and (sequential? clones) (seq clones)))
      (str "clone_shape: pass clones — an array of override maps"
           " ({} for a plain copy)")

      (> (count clones) max-clones)
      (dm/str "clone_shape: at most " max-clones " clones per call")

      (not (ctc/allow-duplicate? objects shape))
      (dm/str "clone_shape: " (caw/shape-label shape) " is inside a component copy,"
              " whose structure is owned by the main component — clone the main"
              " instead, or detach_instance first")

      (and (some? pid) (nil? parent))
      (dm/str "clone_shape: no shape on this page with id " (str pid)
              " (the parentId) — check read_design")

      (and parent (not (or (cfh/frame-shape? parent) (cfh/group-shape? parent))))
      (dm/str "clone_shape: " (caw/shape-label parent) " is a "
              (some-> (:type parent) name)
              " — the parent must be a board or a group")

      (and parent (or (ctc/in-component-copy? parent)
                      (ctn/has-any-copy-parent? objects parent)))
      (dm/str "clone_shape: " (caw/shape-label parent) " is part of a component"
              " copy — clone into the main component instead"))))

(defn- http-url?
  [v]
  (and (string? v) (some? (re-matches cam/http-url-re v))))

(defn- clone-image-urls
  [clones]
  (when (sequential? clones)
    (into []
          (comp (filter map?)
                (mapcat (fn [clone] (vals (or (:imageByName clone) {}))))
                (filter http-url?)
                (distinct))
          clones)))

(defn clone-shape-needs
  [{:keys [clones]}]
  (when (and (sequential? clones) (<= (count clones) max-clones))
    (mapv (fn [url] {:kind :image :url url :name "clone-image"})
          (clone-image-urls clones))))

(defn- image-override
  "The fill an `imageByName` value stands for: an uploaded URL, or the id of an
  image the file already has."
  [resources v]
  (cond
    (http-url? v)
    (let [media (get resources v)]
      {:type "image" :imageId (dm/str (:id media))
       :width (:width media) :height (:height media) :mtype (:mtype media)})

    (string? v)
    {:type "image" :imageId v}))

(defn- clone-once
  "One clone as a tool step: the copy, its overrides, its place in the parent."
  [{:keys [file-id page-id objects data libraries]} id pid i clone
   {:keys [propagate resources] :as engine}]
  (let [shape    (get objects id)
        page     (get-in data [:pages-index page-id])
        kids     (cfh/get-children-ids objects id)
        by-name  (fn [nm] (filter #(= nm (:name (get objects %))) kids))
        delta    (if pid
                   (gpt/point 0 0)
                   ;; free clones fan out to the right, visibly
                   (gpt/point (* (inc i) (+ (:width shape) 40)) 0))
        copied   (-> (caw/page-changes page-id objects)
                     (cb/with-library-data data)
                     (cll/generate-duplicate-changes objects page #{id} delta libraries data file-id {})
                     (cll/generate-duplicate-changes-update-indices objects #{id})
                     (cb/apply-changes-local))
        old->new (into {}
                       (comp (filter #(= :add-obj (:type %)))
                             (keep (fn [change]
                                     (when (:old-id change)
                                       [(:old-id change) (get-in change [:obj :id])]))))
                       (:redo-changes copied))
        new-root (get old->new id)
        texts    (for [[k text] (:textByName clone)
                       oid      (by-name (name k))
                       :let     [nid (get old->new oid)]
                       :when    (and nid (cfh/text-shape? (get objects oid)))]
                   [nid text])
        images   (for [[k v] (:imageByName clone)
                       :let  [fill (image-override resources v)]
                       :when (some? fill)
                       oid   (by-name (name k))
                       :let  [nid (get old->new oid)]
                       :when (some? nid)]
                   [nid fill])
        changes  (as-> copied $
                   (cond-> $
                     (:name clone) (cb/update-shapes [new-root] #(assoc % :name (:name clone))))
                   ;; text overrides, matched by the ORIGINAL's layer names
                   (reduce (fn [changes [nid text]]
                             (-> changes
                                 (cb/update-shapes [nid] #(update % :content txt/change-text text))
                                 (caw/fit-text engine nid)))
                           $ texts)
                   (reduce (fn [changes [nid fill]]
                             (cb/update-shapes changes [nid] #(assoc % :fills (caw/fill->shape fill))))
                           $ images)
                   (cb/apply-changes-local $))
        changes  (if (nil? pid)
                   changes
                   ;; append to the parent's flow, in clone order
                   (let [parent (get (cb/lookup-objects changes) pid)]
                     (-> changes
                         (cb/with-library-data data)
                         (cls/generate-relocate pid
                                                (if (and (ctl/any-layout? parent)
                                                         (not (ctl/reverse? parent)))
                                                  0
                                                  (count (:shapes parent)))
                                                [new-root])
                         ;; seat it without rebuilding the grid: the parent's
                         ;; tracks, spans and manual placements stay
                         (cond-> (ctl/grid-layout? parent)
                           (caw/seat-in-grid pid)))))]
    {:changes (-> changes
                  (caw/reflow-all propagate (if pid
                                              [pid new-root (:frame-id shape)]
                                              [new-root (:frame-id shape)]))
                  (caw/redo))
     :result {:shapeId (dm/str new-root)
              :name (or (:name clone) (:name shape))}}))

(defn clone-shape
  [{:keys [objects] :as view} {:keys [shapeId parentId clones]}
   {:keys [measure-text resources] :as engine}]
  (let [id      (some-> shapeId parse-uuid)
        pid     (some-> parentId parse-uuid)
        failed  (some (fn [url]
                        (let [media (get resources url)]
                          (cond
                            (nil? media) {:fallback "an image this call uses was not fetched"}
                            (contains? media :error) {:problem (cam/media-error-message (:error media))})))
                      (clone-image-urls clones))]
    (cond
      (some? (clone-problem objects id pid clones))
      {:problem (clone-problem objects id pid clones)}

      (some? failed)
      failed

      (and (nil? measure-text) (some #(seq (:textByName %)) clones))
      {:fallback "text cannot be measured on the server without the render engine"}

      :else
      (let [run (reduce (fn [run [i clone]]
                          (let [next (then run #(clone-once % id pid i clone engine))]
                            (cond-> next
                              (nil? (:stopped next)) (update :clones conj (:last next)))))
                        (assoc (start view) :clones [])
                        (map-indexed vector clones))]
        (or (:stopped run)
            {:changes (:changes run)
             :result {:clones (:clones run)
                      :note (str "cloned — overrides matched by layer name"
                                 (when pid ", clones appended to the parent's flow in order")
                                 ". Asynchronous: verify with render_board or read_design.")}})))))

;; --- build_tree

(def ^:private tree-node-types #{"board" "rect" "ellipse" "text" "image" "instance"})

(def ^:private max-tree-nodes 80)

(def ^:private max-tree-depth 6)

(declare tree-problem*)

(defn tree-problem
  "Why `build_tree` cannot run, or nil."
  [tree]
  (let [;; the components of the tree, in the order they are built
        made (into #{} (comp (filter #(true? (:component %))) (keep :name))
                   (when (map? tree) (tree-seq map? :children tree)))]
    (tree-problem* tree made)))

(defn- tree-problem*
  [tree made]
  (letfn [(walk [node path depth]
            (cond
              (not (map? node))
              (dm/str "build_tree: " path " is not a node object")

              (> depth max-tree-depth)
              (dm/str "build_tree: deeper than " max-tree-depth " levels at "
                      path " — flatten the structure or build in two calls")

              (not (contains? tree-node-types (:type node)))
              (dm/str "build_tree: " path " has type \"" (:type node)
                      "\" — use board, rect, ellipse, text, image or instance")

              (and (= "instance" (:type node))
                   (not (or (string? (:componentId node))
                            (contains? made (:of node)))))
              (dm/str "build_tree: instance " path " needs `of` — the name of a node of"
                      " this tree marked component: true and listed BEFORE it"
                      (when (seq made) (dm/str " (" (str/join ", " (sort made)) ")"))
                      " — or a `componentId`")

              (and (true? (:component node)) (not= "board" (:type node)))
              (dm/str "build_tree: " path " is marked component and is a "
                      (:type node) " — only a board becomes a component")

              (and (true? (:component node)) (not (string? (:name node))))
              (dm/str "build_tree: " path " is marked component and needs a `name`")

              (and (= "text" (:type node)) (not (string? (:text node))))
              (dm/str "build_tree: text node " path " needs `text` (the words)")

              (and (= "image" (:type node)) (not (http-url? (:url node))))
              (dm/str "build_tree: image node " path
                      " needs an absolute http(s) `url`")

              (and (seq (:children node)) (not= "board" (:type node)))
              (dm/str "build_tree: " path " has children but is a "
                      (:type node) " — only boards contain children")

              :else
              (some (fn [[i child]]
                      (walk child (dm/str path ".children[" i "]") (inc depth)))
                    (map-indexed vector (:children node)))))]
    (cond
      (nil? tree)
      "build_tree: pass tree — the root node ({type, name, children…})"

      (> (count (tree-seq map? :children tree)) max-tree-nodes)
      (dm/str "build_tree: more than " max-tree-nodes
              " nodes — split the build into two calls")

      :else
      (walk tree "tree" 1))))

(defn build-tree-needs
  [{:keys [tree]}]
  (when (nil? (tree-problem tree))
    (into []
          (comp (filter #(= "image" (:type %)))
                (map (fn [node] {:kind :image :url (:url node) :name (or (:name node) "image")})))
          (tree-seq map? :children tree))))

(defn- at-path
  "Notes where the run stopped, when the step just run stopped it."
  [run path]
  (cond-> run
    (and (some? (:stopped run)) (nil? (:path run)))
    (assoc :path path)))

(declare build-node build-shape)

(defn- build-instance
  "An instance of a component — one this tree made, by its node's name, or
  one the file has — under `parent-id`, with the words `:texts` gives its
  layers."
  [run node parent-id path options]
  (let [component (or (:componentId node) (get (:components run) (:of node)))
        run       (if (nil? component)
                    (assoc run :stopped {:problem (dm/str "no component \"" (:of node) "\" was built before this instance")})
                    (then run #(cac/create-instance % {:componentId component :x (or (:x node) 0) :y (or (:y node) 0)} options)))
        run       (at-path run path)
        id        (:shapeId (:last run))]
    (if (some? (:stopped run))
      run
      (as-> (update run :ids assoc (or (:name node) path) id) run
        (cond-> run
          (some? (:name node))
          (then #(caw/modify-shape % {:shapeId id :name (:name node)} options)))
        (cond-> run
          (some? parent-id)
          (then #(cas/nest-shape % {:shapeId id :parentId parent-id} options)))
        (cond-> run
          (:layoutChild node)
          (then #(cal/set-layout-child % (assoc (:layoutChild node) :shapeId id) options)))
        (cond-> run
          (seq (:texts node))
          (then (fn [{:keys [objects] :as view}]
                  (let [inside (cfh/get-children-ids objects (parse-uuid id))
                        found  (fn [nm] (first (filter #(and (= nm (:name (get objects %)))
                                                             (cfh/text-shape? (get objects %)))
                                                       inside)))
                        texts  (into [] (keep (fn [[k words]]
                                                (when-let [target (found (if (keyword? k) (subs (str k) 1) (str k)))]
                                                  {:shapeId (dm/str target) :text words})))
                                     (:texts node))]
                    (if (empty? texts)
                      {:changes []}
                      (catx/set-texts view {:texts texts} options))))))
        (at-path run path)))))

(defn- build-node
  "Creates one node through the single shape tools and, in order: its layout,
  its sizing as a layout child, its token bindings, its font, then its
  children — each child appended to the flow in listed order. A board marked
  `component` then becomes a main component, for the instances after it."
  [run node parent-id path options]
  (if (= "instance" (:type node))
    (build-instance run node parent-id path options)
    (build-shape run node parent-id path options)))

(defn- build-shape
  [run node parent-id path {:keys [apply-tokens] :as options}]
  (let [kind (:type node)
        base (case kind
               ("board" "rect" "ellipse")
               #(caw/create-shape % (-> (select-keys node [:x :y :width :height :fill])
                                        (assoc :type kind :name (:name node) :parentId parent-id))
                                  options)
               "text"
               #(catx/create-text % (-> (select-keys node [:x :y :fill :align])
                                        (assoc :text (:text node) :name (:name node) :parentId parent-id))
                                  options)
               "image"
               #(cam/insert-image % (-> (select-keys node [:x :y :width :height :url])
                                        (assoc :name (:name node) :parentId parent-id))
                                  options))
        run  (-> run (then base) (at-path path))
        id   (:id (:last run))]
    (if (some? (:stopped run))
      run
      (as-> (update run :ids assoc (or (:name node) path) id) run
        (cond-> run
          (:layout node)
          (then #(cal/set-layout % (assoc (:layout node) :shapeId id) options)))
        (cond-> run
          (:layoutChild node)
          (then #(cal/set-layout-child % (assoc (:layoutChild node) :shapeId id) options)))
        (cond-> run
          (seq (:tokens node))
          (then (fn [view]
                  (if (nil? apply-tokens)
                    {:fallback "tokens cannot be applied on the server yet"}
                    (apply-tokens view
                                  {:applications
                                   (mapv (fn [token]
                                           (cond-> {:shapeId id :tokenName (:name token)}
                                             (:properties token) (assoc :properties (:properties token))))
                                         (:tokens node))}
                                  options)))))
        (cond-> run
          (and (:font node) (= "text" kind))
          (then #(cam/set-font % {:family (get-in node [:font :family])
                                  :variantId (get-in node [:font :variant])
                                  :shapeIds [id]}
                               options)))
        (at-path run path)
        (reduce (fn [run [i child]]
                  (build-node run child id (dm/str path ".children[" i "]") options))
                run
                (map-indexed vector (or (:children node) [])))
        (if (and (true? (:component node)) (nil? (:stopped run)))
          (let [run (-> run
                        (then #(cas/create-component % {:shapeIds [id]} options))
                        (at-path path))]
            (cond-> run
              (nil? (:stopped run))
              (assoc-in [:components (:name node)] (:componentId (:last run)))))
          run)))))

(defn build-tree
  "`:apply-tokens` in the options is the apply_tokens tool, when the host has
  one. A build that is refused part-way keeps what it made: the answer carries
  those changes and says where it stopped."
  [view {:keys [tree parentId]} options]
  (if-let [problem (tree-problem tree)]
    {:problem problem}
    (let [run     (build-node (assoc (start view) :ids {} :components {}) tree parentId "tree" options)
          stopped (:stopped run)]
      (cond
        ;; not this build's to settle: the host takes over, or resolves
        ;; the tokens and runs it again
        (or (contains? stopped :fallback) (contains? stopped :pending))
        stopped

        (some? stopped)
        {:changes (:changes run)
         :result {:created (count (:ids run))
                  :ids (:ids run)
                  :error (dm/str "build_tree at " (:path run) ": " (:problem stopped))
                  :note (str "PARTIAL build — everything in ids exists. "
                             "Fix the error with targeted calls; do NOT "
                             "re-run the whole tree.")}}

        :else
        {:changes (:changes run)
         :result (cond-> {:created (count (:ids run))
                          :ids (:ids run)}
                   (seq (:components run)) (assoc :components (:components run))
                   :always
                   (assoc
                    :note (str "tree built — ids keyed by node name (or path); "
                               "children flow in the listed order. Geometry and "
                               "text settle asynchronously; verify with "
                               "render_board before presenting.")))}))))
