;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.session-events
  "Pure translation from Penpot commits into a design-session timeline.

  Phase 01 of the design-session-recording plan. Everything here is a pure
  function over commit maps — no subscription, no state, no IO. The recorder
  that feeds it lives in `session-recorder` (phase 04); the critique that
  consumes it lives in `session-review` (phase 09).

  ## Why events are classified from the CHANGES, not from `:origin`

  A commit map (`app.main.data.changes/commit`) carries an `:origin` key, and it
  is tempting to read it as \"the interaction that caused this\". It is not.
  `:origin` is `(ptk/type origin)`, and `origin` is passed by only **6 of 133**
  `commit-changes` call sites — so essentially every real layout interaction
  arrives as `:potok.v2.core/undefined` (`ptk/type` returns `::undefined` for
  anything that does not implement potok's `Event` protocol).

  So classification reads the changes themselves: the `:redo-changes` `:type`
  values and, for `:mod-obj`, the `:operations` `:attr` names. That signal is
  better on every axis — it is schema-validated on every commit
  (`app.common.files.changes/check-changes`), it is the actual data model rather
  than an incidental UI event name, and it is identical whether a human dragged
  a shape or an agent tool wrote it.

  `:origin` is still recorded, and is still load-bearing for exactly one thing:
  undo, redo and machine font-repair are among the few call sites that do pass
  it, which is how we recognise them."
  (:require
   [cuerdas.core :as str]))

;; --- noise
;;
;; Not every commit is somebody doing something. Penpot writes to its own file
;; for bookkeeping, and those commits must not appear in a session or the
;; timeline drowns in machine chatter.

(def ^:private noise-tags
  "Commit tags that mark a machine writeback. `:position-data` is the WASM text
  layout writing measured geometry back into the file; the app already filters
  the same tag out of its own commit subscription (`data/workspace.cljs:459`)."
  #{:position-data})

(def ^:private machine-origins
  "Origins whose commits repair the file rather than express an intention.
  These are among the handful of call sites that pass `:origin` at all."
  #{:app.main.data.workspace.fix-deleted-fonts/fix-deleted-fonts-for-page
    :app.main.data.workspace.fix-deleted-fonts/fix-deleted-fonts-for-local-library})

(def ^:private undo-origins
  #{:app.main.data.workspace.undo/undo
    :app.main.data.workspace.undo/undo-to-index})

(def ^:private redo-origins
  #{:app.main.data.workspace.undo/redo})

(defn recordable?
  "True when a commit represents something a participant did.

  Rejects machine writebacks and empty commits. Deliberately does NOT reject
  remote commits — a session is about everyone on the file, so a collaborator's
  changes are exactly as recordable as our own."
  [{:keys [tags origin redo-changes]}]
  (boolean
   (and (seq redo-changes)
        (not (some noise-tags tags))
        (not (contains? machine-origins origin)))))

;; --- shape ids

(defn touched-shape-ids
  "Shape ids named by a commit's redo-changes (`:id` on add/mod/del forms,
  `:shapes` on mov/reg forms).

  Shared with the AI panel's violations watcher, which had the only copy of
  this before the session recorder needed it too."
  [redo-changes]
  (into #{}
        (mapcat (fn [{:keys [id shapes]}]
                  (cond-> []
                    (some? id)   (conj id)
                    (seq shapes) (into shapes))))
        redo-changes))

;; --- classification

(def ^:private layout-attr-prefix "layout-")

(def ^:private attr-kinds
  "`:mod-obj` operation attrs → event kind, in precedence order (first match
  wins for a commit touching several).

  Geometry sits LAST on purpose: Penpot recalculates position and selrect as a
  side effect of almost everything, so a commit that changes both `:fills` and
  `:x` was a restyle, not a move."
  [[:token      #{:applied-tokens}]
   [:text       #{:content}]
   [:rename     #{:name}]
   [:visibility #{:hidden :blocked}]
   [:style      #{:fills :strokes :shadow :blur :background-blur :opacity
                  :blend-mode :stroke-color :stroke-width :stroke-style
                  :stroke-alignment :stroke-cap-start :stroke-cap-end
                  :fill-color :fill-opacity :rx :ry :r1 :r2 :r3 :r4}]
   [:resize     #{:width :height :grow-type}]
   [:move       #{:x :y}]
   [:transform  #{:rotation :transform :transform-inverse :flip-x :flip-y
                  :selrect :points}]])

(def ^:private type-kinds
  "Change `:type` → event kind. Types absent here fall through to `:other`,
  which names the raw keyword so a new change type is visible rather than
  silently swallowed."
  {:add-obj                :create
   :del-obj                :delete
   :mov-objects            :reparent
   :reorder-children       :reorder
   :add-page               :page
   :mod-page               :page
   :del-page               :page
   :mov-page               :page
   :add-component          :component
   :mod-component          :component
   :del-component          :component
   :restore-component      :component
   :purge-component        :component
   :set-token              :token
   :set-token-set          :token
   :set-token-theme        :token
   :set-tokens-lib         :token
   :set-active-token-themes :token
   :rename-token-set-group :token
   :move-token-set         :token
   :move-token-set-group   :token
   :add-color              :library
   :mod-color              :library
   :del-color              :library
   :add-media              :library
   :mod-media              :library
   :del-media              :library
   :add-typography         :library
   :mod-typography         :library
   :del-typography         :library
   :set-plugin-data        :metadata
   :set-guide              :canvas-config
   :set-default-grid       :canvas-config
   :set-flow               :canvas-config
   :set-base-font-size     :canvas-config})

(def ^:private kind-priority
  "When one event covers several kinds, the most intentional wins. Creating a
  shape inside a board emits `:add-obj` *and* `:mov-objects`; the person
  created something."
  [:create :delete :page :component :token :library :metadata
   :layout :text :style :rename :visibility
   :reparent :reorder :resize :move :transform :canvas-config :other])

(def ^:private kind-rank
  (into {} (map-indexed (fn [i k] [k i])) kind-priority))

(defn- layout-attr?
  [attr]
  (and (keyword? attr)
       (str/starts-with? (name attr) layout-attr-prefix)))

(defn- mod-obj-kind
  "Classify one `:mod-obj` change by the attrs its operations set."
  [{:keys [operations]}]
  (let [attrs (into #{}
                    (comp (map :attr) (filter some?))
                    operations)]
    (if (some layout-attr? attrs)
      :layout
      (or (some (fn [[kind kind-attrs]]
                  (when (some kind-attrs attrs) kind))
                attr-kinds)
          ;; an :assign operation carries a whole value map with no :attr, and
          ;; :set-touched / :set-remote-synced are sync bookkeeping
          :other))))

(defn- change-kind
  [{:keys [type] :as change}]
  (if (= :mod-obj type)
    (mod-obj-kind change)
    (get type-kinds type :other)))

(defn- dominant-kind
  [kinds]
  (->> kinds
       (sort-by #(get kind-rank % (count kind-priority)))
       (first)))

(defn- unmapped-types
  "Raw change types with no mapping, so `:other` can name itself."
  [redo-changes]
  (into (sorted-set)
        (comp (map :type)
              (filter #(and (some? %)
                            (not= :mod-obj %)
                            (not (contains? type-kinds %)))))
        redo-changes))

(defn- pluralize-shapes
  [n]
  (if (= 1 n) "1 shape" (str n " shapes")))

(defn- describe
  "Human label for an event. `types` are the unmapped change types, carried so an
  `:other` event names itself even after coalescing."
  [kind shape-count types]
  (case kind
    :create        (str "created " (pluralize-shapes shape-count))
    :delete        (str "deleted " (pluralize-shapes shape-count))
    :move          (str "moved " (pluralize-shapes shape-count))
    :resize        (str "resized " (pluralize-shapes shape-count))
    :transform     (str "transformed " (pluralize-shapes shape-count))
    :style         (str "restyled " (pluralize-shapes shape-count))
    :rename        (str "renamed " (pluralize-shapes shape-count))
    :text          (str "edited text on " (pluralize-shapes shape-count))
    :layout        (str "changed layout on " (pluralize-shapes shape-count))
    :visibility    (str "changed visibility of " (pluralize-shapes shape-count))
    :reparent      (str "reparented " (pluralize-shapes shape-count))
    :reorder       (str "reordered " (pluralize-shapes shape-count))
    :token         "changed design tokens"
    :library       "changed a library asset"
    :component     "changed a component"
    :page          "changed the page structure"
    :metadata      "changed file metadata"
    :canvas-config "changed canvas setup"
    :undo          "undid the previous step"
    :redo          "redid a step"
    (if (seq types)
      (str "changed the file (" (str/join ", " types) ")")
      "changed the file")))

;; --- events

(defn- at-ms
  "Normalize `:created-at` to epoch millis at the boundary, so an event is
  plainly serializable (phases 06/07 persist these) and cheap to compare.
  Tolerates a raw number so tests need not construct dates."
  [created-at]
  (cond
    (number? created-at) created-at
    (some? created-at)   (inst-ms created-at)
    :else                nil))

(defn commit->event
  "Translate one commit map into a session event.

  Attribution (`:who`, `:profile-id`, `:session-id`, `:provider`, `:model`) is
  passed through when the commit already carries it — phases 02 and 03 are what
  put it there. Until then `:who` reads `:user`, which is the honest default:
  every commit today is somebody at a keyboard."
  [{:keys [id origin undo-group redo-changes created-at source
           who profile-id session-id provider model]}]
  (let [shape-ids (touched-shape-ids redo-changes)
        types     (unmapped-types redo-changes)
        kind      (cond
                    (contains? undo-origins origin) :undo
                    (contains? redo-origins origin) :redo
                    :else (dominant-kind (map change-kind redo-changes)))]
    {:id         id
     :at         (at-ms created-at)
     :source     source
     :who        (or who :user)
     :profile-id profile-id
     :session-id session-id
     :provider   provider
     :model      model
     :kind       kind
     :label      (describe kind (count shape-ids) types)
     :shape-ids  shape-ids
     :types      types
     :origin     (when-not (= :potok.v2.core/undefined origin) origin)
     :undo-group undo-group
     :raw-count  (count redo-changes)
     ;; how many commits this event swallowed; a coalesced drag reports many
     :commits    1}))

(defn- actor-key
  [{:keys [who profile-id session-id provider model]}]
  [(or who :user) profile-id session-id provider model])

(defn- merge-events
  "Fold a run of events from one actor and one undo-group into a single event."
  [events]
  (if (= 1 (count events))
    (first events)
    (let [first-event (first events)
          shape-ids   (reduce into #{} (map :shape-ids events))
          types       (reduce into (sorted-set) (map :types events))
          kind        (dominant-kind (map :kind events))]
      (assoc first-event
             :kind kind
             :shape-ids shape-ids
             :types types
             :label (describe kind (count shape-ids) types)
             :raw-count (reduce + (map :raw-count events))
             :commits (count events)))))

(defn coalesce
  "Fold a commit sequence into a semantic timeline.

  One human gesture emits many commits — a drag is dozens — so adjacent commits
  that share an `:undo-group` become one event. Three constraints keep the
  result truthful:

  - only ADJACENT commits merge, so a group interrupted by other work does not
    swallow the interruption;
  - a nil `:undo-group` never merges with anything, including another nil;
  - commits from different actors never merge, even inside one group.
    Attribution is the point of a recording; merging across people would
    falsify it."
  [commits]
  (->> commits
       (filter recordable?)
       (map-indexed
        (fn [idx commit]
          ;; a nil undo-group gets a per-index discriminator so it can never
          ;; collide with a neighbour
          [(actor-key commit) (or (:undo-group commit) [::ungrouped idx])
           (commit->event commit)]))
       (partition-by (fn [[actor group _]] [actor group]))
       (mapv (fn [run] (merge-events (mapv peek run))))))

;; --- rendering for the reviewer

(defn- actor-label
  [{:keys [who provider model]}]
  (if (= :agent who)
    (str "agent " (or model provider "unknown model"))
    "user"))

(defn- offset-label
  [base at]
  (if (and (number? base) (number? at))
    (str "+" (int (/ (- at base) 1000)) "s")
    "+?"))

(defn timeline->prompt-text
  "Render a timeline as compact text for a reviewing model: one line per event.

  Deliberately terse — phase 09 sends this to a model with a hard char budget,
  and a session can be long. The acting model is named on agent lines so a
  critique can distinguish which model did what."
  [events]
  (if (empty? events)
    "No recorded interactions."
    (let [base (:at (first events))]
      (->> events
           (map (fn [{:keys [at label shape-ids commits] :as event}]
                  (str (offset-label base at)
                       " " (actor-label event)
                       ": " label
                       (when (and commits (> commits 1))
                         (str " (" commits " steps)"))
                       (when (seq shape-ids)
                         (str " [" (count shape-ids) " touched]")))))
           (str/join "\n")))))
