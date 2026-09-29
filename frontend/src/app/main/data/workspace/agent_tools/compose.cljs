;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.compose
  "Composition tools: clone_shape and build_tree, which orchestrate
  the other families to build whole subtrees in one call."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.types.component :as ctc]
   [app.common.types.container :as ctn]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-tools.common :as atc]
   [app.main.data.workspace.agent-tools.layout :as atl]
   [app.main.data.workspace.agent-tools.media :as atm]
   [app.main.data.workspace.agent-tools.structure :as ats]
   [app.main.data.workspace.agent-tools.tokens :as att]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; --- Composition layer: clone_shape / build_tree
;;
;; The NYT postmortem's first-ranked strategy: cost is rounds × prefix, and
;; only collapsing rounds moves it 5-10×. These two tools do in ONE round what
;; the transcripts show taking 25+ — cloning a card into a grid with per-clone
;; content, and standing up a whole laid-out subtree.

(def ^:private max-clones 12)

(defn clone-problem
  "Why `clone_shape` cannot run, or nil. Pure. Public for tests."
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
      (dm/str "clone_shape: " (atc/shape-label shape) " is inside a component copy,"
              " whose structure is owned by the main component — clone the main"
              " instead, or detach_instance first")

      (and (some? pid) (nil? parent))
      (dm/str "clone_shape: no shape on this page with id " (str pid)
              " (the parentId) — check read_design")

      (and parent (not (or (cfh/frame-shape? parent) (cfh/group-shape? parent))))
      (dm/str "clone_shape: " (atc/shape-label parent) " is a "
              (some-> (:type parent) name)
              " — the parent must be a board or a group")

      (and parent (or (ctc/in-component-copy? parent)
                      (ctn/has-any-copy-parent? objects parent)))
      (dm/str "clone_shape: " (atc/shape-label parent) " is part of a component"
              " copy — clone into the main component instead"))))

(defn- upload-image-url
  "External image URL → the image-fill descriptor for `atc/fill->shape`."
  [url]
  (->> (rp/cmd! :create-file-media-object-from-url
                {:name "clone-image" :file-id (:current-file-id @st/state)
                 :url url :is-local true})
       (rx/map (fn [media]
                 {:type "image" :imageId (dm/str (:id media))
                  :width (:width media) :height (:height media)
                  :mtype (:mtype media)}))
       (rx/catch (fn [cause]
                   (rx/throw (ex-info (atm/media-error-message (:code (ex-data cause)))
                                      {}))))))

(defn- do-clones!
  "Duplicates + overrides + relocations for every clone. Side-effecting; the
  ids come from the generated changes, so nothing here waits on the pipeline."
  [state objects id pid clones url->fill]
  (let [page      (dsh/lookup-page state)
        file-id   (:current-file-id state)
        libraries (dsh/lookup-libraries state)
        lib-data  (dsh/lookup-file-data state file-id)
        shape     (get objects id)
        parent    (when pid (get objects pid))
        kids      (cfh/get-children-ids objects id)
        by-name   (fn [nm] (into [] (filter #(= nm (:name (get objects %)))) kids))]
    (atc/interrupt!)
    (let [results
          (vec
           (map-indexed
            (fn [i clone]
              (let [delta    (if pid
                               (gpt/point 0 0)
                               ;; free clones fan out to the right, visibly
                               (gpt/point (* (inc i) (+ (:width shape) 40)) 0))
                    changes  (-> (cb/empty-changes)
                                 (cll/generate-duplicate-changes
                                  objects page #{id} delta libraries lib-data file-id)
                                 (cll/generate-duplicate-changes-update-indices
                                  objects #{id}))
                    old->new (into {}
                                   (comp (filter #(= :add-obj (:type %)))
                                         (keep (fn [ch]
                                                 (when (:old-id ch)
                                                   [(:old-id ch) (get-in ch [:obj :id])]))))
                                   (:redo-changes changes))
                    new-root (get old->new id)]
                (st/emit! (dch/commit-changes changes))
                (when-let [nm (:name clone)]
                  (st/emit! (dwsh/update-shapes [new-root] #(assoc % :name nm))))
                ;; text overrides, matched by the ORIGINAL's layer names
                (doseq [[k text] (:textByName clone)]
                  (doseq [oid (by-name (name k))]
                    (when-let [nid (get old->new oid)]
                      (when (cfh/text-shape? (get objects oid))
                        (st/emit! (dwsh/update-shapes [nid]
                                                      #(update % :content txt/change-text text)))
                        (when (features/active-feature? state "render-wasm/v1")
                          (st/emit! (dwwt/resize-wasm-text-debounce nid)))))))
                ;; image overrides: a URL was pre-uploaded, a bare string is an
                ;; existing imageId
                (doseq [[k v] (:imageByName clone)]
                  (when-let [fill (cond
                                    (and (string? v) (re-matches atc/http-url-re v))
                                    (get url->fill v)

                                    (string? v)
                                    {:type "image" :imageId v})]
                    (doseq [oid (by-name (name k))]
                      (when-let [nid (get old->new oid)]
                        (st/emit! (dwsh/update-shapes [nid]
                                                      #(assoc % :fills (atc/fill->shape fill))))))))
                ;; append to the parent's flow, in clone order
                (when pid
                  (st/emit! (dwsh/relocate-shapes
                             #{new-root} pid
                             (if (and (ctl/any-layout? parent)
                                      (not (ctl/reverse? parent)))
                               0
                               (count (:shapes parent))))))
                {:shapeId (dm/str new-root)
                 :name (or (:name clone) (:name shape))}))
            clones))]
      ;; Seat the appended clones without rebuilding the grid — assign-cells
      ;; preserves the parent's tracks, spans and manual placements (see
      ;; nest_shape); reflow-grid-cells would wipe a user-authored grid.
      (when (and pid (ctl/grid-layout? parent))
        (st/emit! (dwsh/update-shapes [pid]
                                      (fn [p objs] (-> (ctl/assign-cells p objs)
                                                       (ctl/reorder-grid-children)))
                                      {:with-objects? true}))
        (st/emit! (ptk/data-event :layout/update {:ids [pid]})))
      {:clones results
       :note (str "cloned — overrides matched by layer name"
                  (when pid ", clones appended to the parent's flow in order")
                  ". Asynchronous: verify with render_board or read_design.")})))

(defn clone-shape-tool
  [{:keys [shapeId parentId clones]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)
        pid     (some-> parentId parse-uuid)]
    (if-let [problem (clone-problem objects id pid clones)]
      (rx/throw (ex-info problem {}))
      (let [urls (into []
                       (comp (mapcat (fn [c] (vals (or (:imageByName c) {}))))
                             (filter #(and (string? %) (re-matches atc/http-url-re %)))
                             (distinct))
                       clones)]
        (->> (if (empty? urls)
               (rx/of {})
               ;; upload every referenced URL once, then clone with the map
               (->> (rx/from urls)
                    (rx/mapcat (fn [u] (->> (upload-image-url u)
                                            (rx/map (fn [f] [u f])))))
                    (rx/reduce conj {})))
             (rx/map (fn [url->fill]
                       (do-clones! state objects id pid clones url->fill))))))))

;; --- build_tree

(def ^:private tree-node-types #{"board" "rect" "ellipse" "text" "image"})

(def ^:private max-tree-nodes 80)

(def ^:private max-tree-depth 6)

(defn tree-problem
  "Why `build_tree` cannot run, or nil. Pure. Public for tests."
  [tree]
  (letfn [(walk [node path depth]
            (cond
              (not (map? node))
              (dm/str "build_tree: " path " is not a node object")

              (> depth max-tree-depth)
              (dm/str "build_tree: deeper than " max-tree-depth " levels at "
                      path " — flatten the structure or build in two calls")

              (not (contains? tree-node-types (:type node)))
              (dm/str "build_tree: " path " has type \"" (:type node)
                      "\" — use board, rect, ellipse, text or image")

              (and (= "text" (:type node)) (not (string? (:text node))))
              (dm/str "build_tree: text node " path " needs `text` (the words)")

              (and (= "image" (:type node))
                   (not (and (string? (:url node))
                             (re-matches atc/http-url-re (:url node)))))
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

(defn- build-node
  "Creates one node (via the same validated single-shape paths the individual
  tools use) and, in order: its layout, layout-child sizing, token bindings,
  font, then its children — each child appended to the flow in listed order."
  [node parent-id path ids*]
  (let [t    (:type node)
        base (case t
               "board"
               (atc/create-shape (-> (select-keys node [:x :y :width :height :fill])
                                     (assoc :type "board" :name (:name node)
                                            :parentId parent-id)))
               ("rect" "ellipse")
               (atc/create-shape (-> (select-keys node [:x :y :width :height :fill])
                                     (assoc :type t :name (:name node)
                                            :parentId parent-id)))
               "text"
               (ats/create-text (-> (select-keys node [:x :y :fill :align])
                                    (assoc :text (:text node) :name (:name node)
                                           :parentId parent-id)))
               "image"
               (atm/insert-image (-> (select-keys node [:x :y :width :height :url])
                                     (assoc :name (:name node)
                                            :parentId parent-id))))]
    (->> base
         (rx/mapcat
          (fn [{:keys [id]}]
            (swap! ids* assoc (or (:name node) path) id)
            (reduce
             (fn [acc step] (rx/concat acc step))
             (rx/empty)
             (concat
              (when (:layout node)
                [(atl/set-layout (assoc (:layout node) :shapeId id))])
              (when (:layoutChild node)
                [(atl/set-layout-child (assoc (:layoutChild node) :shapeId id))])
              (when (seq (:tokens node))
                [(att/apply-tokens
                  {:applications
                   (mapv (fn [tk]
                           (cond-> {:shapeId id :tokenName (:name tk)}
                             (:properties tk) (assoc :properties (:properties tk))))
                         (:tokens node))})])
              (when (and (:font node) (= "text" t))
                [(atm/set-font {:family (get-in node [:font :family])
                                :variantId (get-in node [:font :variant])
                                :shapeIds [id]})])
              (map-indexed
               (fn [i child]
                 (build-node child id (dm/str path ".children[" i "]") ids*))
               (or (:children node) []))))))
         (rx/catch
          (fn [cause]
            (rx/throw (ex-info (dm/str "at " path ": " (ex-message cause))
                               (or (ex-data cause) {}))))))))

(defn build-tree-tool
  [{:keys [tree parentId]}]
  (if-let [problem (tree-problem tree)]
    (rx/throw (ex-info problem {}))
    (let [ids* (atom {})]
      (->> (build-node tree parentId "tree" ids*)
           (rx/reduce (fn [acc _] acc) nil)
           (rx/map (fn [_]
                     {:created (count @ids*)
                      :ids @ids*
                      :note (str "tree built — ids keyed by node name (or path); "
                                 "children flow in the listed order. Geometry and "
                                 "text settle asynchronously; verify with "
                                 "render_board before presenting.")}))
           ;; a partial build is a repairable state, not a dead end — hand back
           ;; what exists so the fix is targeted, not a rebuild
           (rx/catch (fn [cause]
                       (rx/of {:created (count @ids*)
                               :ids @ids*
                               :error (dm/str "build_tree " (ex-message cause))
                               :note (str "PARTIAL build — everything in ids exists. "
                                          "Fix the error with targeted calls; do NOT "
                                          "re-run the whole tree.")})))))))
