;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.session-events-test
  "Pins the design-session event model (phase 01 of the design-session-recording
  plan): the pure translation from Penpot commits into a semantic timeline.

  The load-bearing decision pinned here is that events are classified from the
  CHANGES (`:redo-changes` types + `:mod-obj` operation attrs), not from the
  commit's `:origin`. `:origin` is `(ptk/type origin)` and only 6 of 133
  `commit-changes` call sites pass `origin` at all, so ~95% of real
  interactions carry `:potok.v2.core/undefined`. See the CORRECTION block in the
  plan README."
  (:require
   [app.common.files.changes :as cpc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.session-events :as se]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]))

;; --- helpers

(def ^:private id-1 (uuid "00000000-0000-0000-0000-000000000001"))
(def ^:private id-2 (uuid "00000000-0000-0000-0000-000000000002"))
(def ^:private page-id (uuid "00000000-0000-0000-0000-0000000000aa"))
(def ^:private group-1 (uuid "00000000-0000-0000-0000-0000000000b1"))
(def ^:private group-2 (uuid "00000000-0000-0000-0000-0000000000b2"))

(defn- commit
  "A minimally-realistic commit map, shaped like `dch/commit` builds it
  (frontend/src/app/main/data/changes.cljs:176-193)."
  [& {:as overrides}]
  (merge {:id (uuid "00000000-0000-0000-0000-0000000000ff")
          :created-at 1000
          :source :local
          :origin :potok.v2.core/undefined
          :tags #{}
          :undo-group nil
          :redo-changes []}
         overrides))

(defn- mod-obj
  "A `:mod-obj` change setting `attrs` on `id`."
  [id attrs]
  {:type :mod-obj
   :id id
   :page-id page-id
   :operations (mapv (fn [attr] {:type :set :attr attr :val nil}) attrs)})

(defn- add-obj
  "An `:add-obj` change. Carries a real shape and `:frame-id` because the change
  schema requires both — see `fixtures-satisfy-the-real-change-schema`."
  [id]
  {:type :add-obj
   :id id
   :page-id page-id
   :frame-id uuid/zero
   :obj (cts/setup-shape {:id id :type :rect :name "Rect"})})

;; --- fixture realism
;;
;; The biggest risk in a pure-function phase is fixtures that do not resemble
;; what the app actually emits: the tests then pin a fantasy. So assert the
;; fixtures against Penpot's OWN change schema — the same validator
;; `dch/commit` asserts with (`changes.cljs:167-171`). If a change form here
;; would be rejected by the real pipeline, this fails.

(t/deftest fixtures-satisfy-the-real-change-schema
  (t/testing "every change form used by these tests is one the pipeline accepts"
    (t/are [change] (true? (cpc/valid-change? change))
      (add-obj id-1)
      (mod-obj id-1 [:x :y])
      (mod-obj id-1 [:fills])
      {:type :del-obj :id id-1 :page-id page-id}
      {:type :mov-objects :parent-id id-2 :page-id page-id :shapes [id-1]}
      {:type :reorder-children :parent-id id-2 :page-id page-id :shapes [id-1]}))

  (t/testing "the deliberately-invalid future type is NOT schema-valid, which is
              why the degrade path exists at all"
    (t/is (false? (cpc/valid-change? {:type :some-future-change-type :id id-1})))))

;; --- the noise filter

(t/deftest recordable-drops-position-data-writebacks
  (t/testing "the WASM text-layout writeback is machine noise, not an interaction"
    ;; precedent: frontend/src/app/main/data/workspace.cljs:459 filters the
    ;; same tag out of its own commit subscription.
    (t/is (not (se/recordable? (commit :tags #{:position-data}
                                       :redo-changes [(mod-obj id-1 [:position-data])]))))))

(t/deftest recordable-drops-machine-font-repair
  (t/testing "fix-deleted-fonts repairs the file on load; nobody did it"
    (t/is (not (se/recordable?
                (commit :origin :app.main.data.workspace.fix-deleted-fonts/fix-deleted-fonts-for-page
                        :redo-changes [(mod-obj id-1 [:content])]))))
    (t/is (not (se/recordable?
                (commit :origin :app.main.data.workspace.fix-deleted-fonts/fix-deleted-fonts-for-local-library
                        :redo-changes [(mod-obj id-1 [:content])]))))))

(t/deftest recordable-drops-empty-commits
  (t/is (not (se/recordable? (commit :redo-changes []))))
  (t/is (not (se/recordable? (commit :redo-changes nil)))))

(t/deftest recordable-keeps-real-interactions
  (t/is (se/recordable? (commit :redo-changes [(add-obj id-1)])))
  (t/testing "a remote collaborator's commit is as recordable as a local one"
    (t/is (se/recordable? (commit :source :remote
                                  :redo-changes [(add-obj id-1)])))))

;; --- classification by change type

(t/deftest commit->event-classifies-structural-change-types
  (t/are [expected-kind change]
         (= expected-kind (:kind (se/commit->event (commit :redo-changes [change]))))

    :create   (add-obj id-1)
    :delete   {:type :del-obj :id id-1 :page-id page-id}
    :reparent {:type :mov-objects :parent-id id-2 :page-id page-id :shapes [id-1]}
    :reorder  {:type :reorder-children :parent-id id-2 :page-id page-id :shapes [id-1]}
    :page     {:type :add-page :page {:id page-id :name "Page 1"}}
    :token    {:type :set-token :set-name "core" :token-name "color.brand" :token {}}
    :library  {:type :add-color :color {:id id-1 :name "red"}}
    :metadata {:type :set-plugin-data :object-type :file :namespace :penpot-vibes :key "design-md" :value "x"}))

(t/deftest commit->event-classifies-mod-obj-by-operation-attrs
  (t/are [expected-kind attrs]
         (= expected-kind (:kind (se/commit->event (commit :redo-changes [(mod-obj id-1 attrs)]))))

    :move       [:x :y]
    :resize     [:width :height]
    :transform  [:rotation]
    :style      [:fills]
    :style      [:strokes :shadow]
    :rename     [:name]
    :text       [:content]
    :layout     [:layout-flex-dir :layout-gap]
    :token      [:applied-tokens]
    :visibility [:hidden])

  (t/testing "a mixed mod-obj picks one kind deterministically rather than throwing"
    (let [event (se/commit->event (commit :redo-changes [(mod-obj id-1 [:x :fills :name])]))]
      (t/is (keyword? (:kind event)))
      (t/is (string? (:label event))))))

(t/deftest commit->event-degrades-on-unknown-change-type
  (t/testing "an unmapped change type must not throw, and must name itself"
    (let [event (se/commit->event
                 (commit :redo-changes [{:type :some-future-change-type :id id-1}]))]
      (t/is (= :other (:kind event)))
      (t/is (string? (:label event)))
      (t/is (re-find #"some-future-change-type" (:label event))
            "the raw keyword survives into the label so it is visible, not silent"))))

(t/deftest commit->event-recognises-undo-and-redo-via-origin
  (t/testing "origin's one genuine use: undo/redo identify themselves with it"
    ;; undo.cljs:316,361,398 are among the only call sites that pass :origin.
    (t/is (= :undo (:kind (se/commit->event
                           (commit :origin :app.main.data.workspace.undo/undo
                                   :redo-changes [(mod-obj id-1 [:x])])))))
    (t/is (= :undo (:kind (se/commit->event
                           (commit :origin :app.main.data.workspace.undo/undo-to-index
                                   :redo-changes [(mod-obj id-1 [:x])])))))
    (t/is (= :redo (:kind (se/commit->event
                           (commit :origin :app.main.data.workspace.undo/redo
                                   :redo-changes [(mod-obj id-1 [:x])])))))))

;; --- the event record

(t/deftest commit->event-carries-provenance-and-shape-ids
  (let [event (se/commit->event
               (commit :created-at 4242
                       :undo-group group-1
                       :redo-changes [(add-obj id-1)
                                      {:type :mov-objects :parent-id id-2
                                       :page-id page-id :shapes [id-2]}]))]
    (t/is (= 4242 (:at event)))
    (t/is (= group-1 (:undo-group event)))
    (t/is (= 2 (:raw-count event)))
    (t/is (= #{id-1 id-2} (:shape-ids event)))
    (t/testing "who defaults to :user — phases 02/03 fill real attribution"
      (t/is (= :user (:who event))))
    (t/testing "attribution keys exist even when unknown, so consumers need no nil-punning"
      (t/is (contains? event :profile-id))
      (t/is (contains? event :model)))))

(t/deftest commit->event-preserves-explicit-attribution
  (t/testing "an already-attributed commit (phases 02/03) is passed through"
    (let [event (se/commit->event
                 (commit :who :agent
                         :profile-id id-1
                         :session-id id-2
                         :provider "anthropic"
                         :model "claude-opus-4-8"
                         :redo-changes [(add-obj id-1)]))]
      (t/is (= :agent (:who event)))
      (t/is (= "claude-opus-4-8" (:model event)))
      (t/is (= id-1 (:profile-id event))))))

;; --- coalescing

(t/deftest coalesce-merges-commits-sharing-an-undo-group
  (t/testing "one user drag emits many commits; the timeline should show one event"
    (let [events (se/coalesce
                  [(commit :created-at 1 :undo-group group-1
                           :redo-changes [(mod-obj id-1 [:x :y])])
                   (commit :created-at 2 :undo-group group-1
                           :redo-changes [(mod-obj id-1 [:x :y])])
                   (commit :created-at 3 :undo-group group-1
                           :redo-changes [(mod-obj id-1 [:x :y])])])]
      (t/is (= 1 (count events)))
      (t/is (= 3 (:raw-count (first events))))
      (t/is (= 1 (:at (first events))) "the group keeps its first timestamp"))))

(t/deftest coalesce-keeps-distinct-groups-apart
  (t/testing "commit-changes defaults undo-group to a fresh uuid, so unrelated
              commits must never merge"
    (let [events (se/coalesce
                  [(commit :created-at 1 :undo-group group-1
                           :redo-changes [(mod-obj id-1 [:x])])
                   (commit :created-at 2 :undo-group group-2
                           :redo-changes [(mod-obj id-2 [:x])])])]
      (t/is (= 2 (count events)))))

  (t/testing "nil undo-group never merges, even with another nil"
    (let [events (se/coalesce
                  [(commit :created-at 1 :undo-group nil
                           :redo-changes [(mod-obj id-1 [:x])])
                   (commit :created-at 2 :undo-group nil
                           :redo-changes [(mod-obj id-2 [:x])])])]
      (t/is (= 2 (count events))))))

(t/deftest coalesce-only-merges-adjacent-commits
  (t/testing "a group interrupted by other work does not swallow the interruption"
    (let [events (se/coalesce
                  [(commit :created-at 1 :undo-group group-1
                           :redo-changes [(mod-obj id-1 [:x])])
                   (commit :created-at 2 :undo-group group-2
                           :redo-changes [(mod-obj id-2 [:fills])])
                   (commit :created-at 3 :undo-group group-1
                           :redo-changes [(mod-obj id-1 [:x])])])]
      (t/is (= 3 (count events))
            "same group, non-adjacent: kept separate so the timeline stays truthful"))))

(t/deftest coalesce-drops-noise-and-preserves-order
  (let [events (se/coalesce
                [(commit :created-at 1 :undo-group group-1
                         :redo-changes [(add-obj id-1)])
                 (commit :created-at 2 :tags #{:position-data}
                         :redo-changes [(mod-obj id-1 [:position-data])])
                 (commit :created-at 3 :undo-group group-2
                         :redo-changes [(mod-obj id-1 [:fills])])])]
    (t/is (= [:create :style] (mapv :kind events)))
    (t/is (= [1 3] (mapv :at events)) "monotonic by created-at, noise removed")))

(t/deftest coalesce-keeps-unknown-type-names-through-a-merge
  (t/testing "a coalesced :other event still names its raw types, so a new
              upstream change type stays visible after merging"
    (let [events (se/coalesce
                  [(commit :created-at 1 :undo-group group-1
                           :redo-changes [{:type :some-future-change-type :id id-1}])
                   (commit :created-at 2 :undo-group group-1
                           :redo-changes [{:type :another-future-type :id id-1}])])]
      (t/is (= 1 (count events)))
      (t/is (re-find #"some-future-change-type" (:label (first events))))
      (t/is (re-find #"another-future-type" (:label (first events)))))))

(t/deftest coalesce-merges-only-same-actor
  (t/testing "two collaborators' commits must never merge, even in one group"
    (let [events (se/coalesce
                  [(commit :created-at 1 :undo-group group-1 :profile-id id-1
                           :redo-changes [(mod-obj id-1 [:x])])
                   (commit :created-at 2 :undo-group group-1 :profile-id id-2
                           :redo-changes [(mod-obj id-1 [:x])])])]
      (t/is (= 2 (count events))
            "attribution is the whole point; merging across actors would falsify it"))))

;; --- prompt rendering

(t/deftest timeline->prompt-text-is-compact-and-attributed
  (let [events (se/coalesce
                [(commit :created-at 1 :undo-group group-1 :profile-id id-1
                         :redo-changes [(add-obj id-1)])
                 (commit :created-at 2 :undo-group group-2 :who :agent
                         :provider "anthropic" :model "claude-opus-4-8"
                         :redo-changes [(mod-obj id-1 [:fills])])])
        text (se/timeline->prompt-text events)]
    (t/is (string? text))
    (t/is (re-find #"agent" text))
    (t/is (re-find #"claude-opus-4-8" text) "the acting model must be visible to the reviewer")
    (t/testing "one line per event keeps the payload predictable"
      (t/is (= 2 (count (remove empty? (str/lines text))))))))

(t/deftest timeline->prompt-text-handles-an-empty-session
  (t/testing "a recording where nothing happened is a valid, honest result"
    (t/is (string? (se/timeline->prompt-text [])))))
