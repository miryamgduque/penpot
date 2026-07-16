;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.agent-tools-test
  "Guards the validation at the agent's tool boundary.

  `dwv/combine-as-variants` silently drops ids that are not main instances or
  are already variants, and no-ops entirely below two ids. Silence is the one
  answer the agent cannot act on: given no signal, it improvises something that
  looks like the capability — which is how a request for variants came back as
  frames named `Card=Size=Compact`.

  So these tests assert on message *content*, not just rejection. A message that
  fails to name the corrective action is a bug here, even though the call was
  correctly refused."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.agent-tools :as at]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]))

;; ---------------------------------------------------------------------------
;; fixtures
;; ---------------------------------------------------------------------------

(def ^:private id-a (uuid/custom 1 1))
(def ^:private id-b (uuid/custom 1 2))
(def ^:private id-c (uuid/custom 1 3))
(def ^:private id-missing (uuid/custom 9 9))

(defn- main-instance
  [id name]
  {:id id :name name :type :frame :main-instance true})

(defn- plain-frame
  [id name]
  {:id id :name name :type :frame})

(defn- variant-member
  [id name variant-id]
  (assoc (main-instance id name) :variant-id variant-id))

(defn- objects
  [& shapes]
  (into {} (map (juxt :id identity)) shapes))

;; ---------------------------------------------------------------------------
;; variant-members-problem — the accept path
;; ---------------------------------------------------------------------------

(t/deftest two-main-instances-are-combinable
  (let [objs (objects (main-instance id-a "Card") (main-instance id-b "Card Large"))]
    (t/is (nil? (at/variant-members-problem objs [id-a id-b])))))

(t/deftest three-main-instances-are-combinable
  (let [objs (objects (main-instance id-a "Card")
                      (main-instance id-b "Card Large")
                      (main-instance id-c "Card Compact"))]
    (t/is (nil? (at/variant-members-problem objs [id-a id-b id-c])))))

;; ---------------------------------------------------------------------------
;; variant-members-problem — too few members
;; ---------------------------------------------------------------------------

(t/deftest one-id-is-rejected
  (let [objs    (objects (main-instance id-a "Card"))
        problem (at/variant-members-problem objs [id-a])]
    (t/is (some? problem))
    (t/is (str/includes? problem "at least 2"))))

(t/deftest no-ids-is-rejected
  (t/is (some? (at/variant-members-problem {} []))))

(t/deftest duplicate-ids-do-not-count-as-two-members
  ;; combine-as-variants distincts its input, so [a a] reaches it as one id and
  ;; silently no-ops. Catch it here instead, or the agent is told it worked.
  (let [objs    (objects (main-instance id-a "Card"))
        problem (at/variant-members-problem objs [id-a id-a])]
    (t/is (some? problem))
    (t/is (str/includes? problem "at least 2"))))

;; ---------------------------------------------------------------------------
;; variant-members-problem — not a main component
;; ---------------------------------------------------------------------------

(t/deftest plain-frames-are-rejected
  (let [objs    (objects (plain-frame id-a "Card") (plain-frame id-b "Card Large"))
        problem (at/variant-members-problem objs [id-a id-b])]
    (t/is (some? problem))
    (t/is (str/includes? problem "main component"))))

(t/deftest not-a-main-component-message-names-the-fix
  ;; The whole point: the agent must learn it needs create_component, not that
  ;; "input was invalid".
  (let [objs    (objects (plain-frame id-a "Card") (main-instance id-b "Card Large"))
        problem (at/variant-members-problem objs [id-a id-b])]
    (t/is (str/includes? problem "create_component"))))

(t/deftest not-a-main-component-message-names-the-offending-shape
  (let [objs    (objects (plain-frame id-a "Hero Card") (main-instance id-b "Card Large"))
        problem (at/variant-members-problem objs [id-a id-b])]
    (t/is (str/includes? problem "Hero Card"))
    (t/is (str/includes? problem (str id-a)))))

(t/deftest one-offender-reads-as-singular
  (let [objs    (objects (plain-frame id-a "Card") (main-instance id-b "Card Large"))
        problem (at/variant-members-problem objs [id-a id-b])]
    (t/is (str/includes? problem "is not a main component"))))

(t/deftest many-offenders-read-as-plural
  ;; "are not a main component" — plural verb, singular noun — was the first
  ;; draft, and the agent reads these messages as instructions.
  (let [objs    (objects (plain-frame id-a "Card One") (plain-frame id-b "Card Two"))
        problem (at/variant-members-problem objs [id-a id-b])]
    (t/is (str/includes? problem "are not main components"))))

(t/deftest every-offending-shape-is-named-not-only-the-first
  ;; Naming one at a time costs a round trip per bad shape; the agent should be
  ;; able to fix them all and retry once.
  (let [objs    (objects (plain-frame id-a "Card One")
                         (plain-frame id-b "Card Two")
                         (main-instance id-c "Card Three"))
        problem (at/variant-members-problem objs [id-a id-b id-c])]
    (t/is (str/includes? problem "Card One"))
    (t/is (str/includes? problem "Card Two"))))

;; ---------------------------------------------------------------------------
;; variant-members-problem — already a variant
;; ---------------------------------------------------------------------------

(t/deftest existing-variants-are-rejected
  (let [vid     (uuid/custom 2 1)
        objs    (objects (variant-member id-a "Card" vid)
                         (variant-member id-b "Card Large" vid))
        problem (at/variant-members-problem objs [id-a id-b])]
    (t/is (some? problem))
    (t/is (str/includes? problem "add_variant"))))

(t/deftest already-a-variant-wins-over-not-a-main-component
  ;; A variant member is also a main instance, so both branches could match.
  ;; The variant message is the more actionable one.
  (let [vid     (uuid/custom 2 1)
        objs    (objects (variant-member id-a "Card" vid)
                         (main-instance id-b "Card Large"))
        problem (at/variant-members-problem objs [id-a id-b])]
    (t/is (str/includes? problem "add_variant"))))

;; ---------------------------------------------------------------------------
;; variant-members-problem — unknown ids
;; ---------------------------------------------------------------------------

(t/deftest unknown-ids-are-rejected
  (let [objs    (objects (main-instance id-a "Card"))
        problem (at/variant-members-problem objs [id-a id-missing])]
    (t/is (some? problem))
    (t/is (str/includes? problem (str id-missing)))))

(t/deftest unknown-id-message-mentions-the-page
  ;; Cross-page ids arrive here as simply absent — objects is one page's map.
  ;; The message has to hint at that, or the agent hunts for a deleted shape.
  (let [objs    (objects (main-instance id-a "Card"))
        problem (at/variant-members-problem objs [id-a id-missing])]
    (t/is (str/includes? problem "page"))))

;; ---------------------------------------------------------------------------
;; summarize-shape — variant flags
;; ---------------------------------------------------------------------------

(t/deftest a-plain-board-carries-no-variant-keys
  ;; read_design is called constantly; a file with no variants must not pay for
  ;; the feature in its payload.
  (let [objs (objects (plain-frame id-a "Hero"))
        out  (at/summarize-shape objs id-a)]
    (t/is (= "Hero" (:name out)))
    (t/is (not (contains? out :isVariantContainer)))
    (t/is (not (contains? out :variantId)))))

(t/deftest a-container-is-flagged
  (let [objs (objects (assoc (plain-frame id-c "Card") :is-variant-container true))
        out  (at/summarize-shape objs id-c)]
    (t/is (true? (:isVariantContainer out)))))

(t/deftest a-member-carries-its-variant-id-and-name
  (let [objs (objects (assoc (variant-member id-a "Card" id-c) :variant-name "Compact"))
        out  (at/summarize-shape objs id-a)]
    (t/is (= (str id-c) (:variantId out)))
    (t/is (= "Compact" (:variantName out)))))

;; ---------------------------------------------------------------------------
;; variant-sets
;; ---------------------------------------------------------------------------

(defn- container
  [id name child-ids]
  (assoc (plain-frame id name) :is-variant-container true :shapes child-ids))

(defn- component-of
  [id name shape-id variant-id props]
  {:id id :name name :main-instance-id shape-id
   :variant-id variant-id :variant-properties props})

(def ^:private cid-1 (uuid/custom 3 1))
(def ^:private cid-2 (uuid/custom 3 2))
(def ^:private vid (uuid/custom 2 7))

(def ^:private one-set-objects
  (objects (container vid "Card" [id-a id-b])
           (assoc (variant-member id-a "Card" vid) :component-id cid-1)
           (assoc (variant-member id-b "Card" vid) :component-id cid-2)))

(def ^:private one-set-data
  {:components
   {cid-1 (component-of cid-1 "Card" id-a vid [{:name "Size" :value "Compact"}])
    cid-2 (component-of cid-2 "Card" id-b vid [{:name "Size" :value "Large"}])}})

(t/deftest a-file-with-no-variants-yields-no-sets
  (t/is (empty? (at/variant-sets {} (objects (plain-frame id-a "Hero"))))))

(t/deftest a-set-reports-its-container
  (let [[s] (at/variant-sets one-set-data one-set-objects)]
    (t/is (= (str vid) (:variantId s)))
    (t/is (= "Card" (:name s)))))

(t/deftest a-set-reports-its-members-and-properties
  (let [[s]   (at/variant-sets one-set-data one-set-objects)
        props (mapcat :properties (:members s))]
    (t/is (= 2 (count (:members s))))
    (t/is (= #{"Compact" "Large"} (set (map :value props))))
    (t/is (= #{"Size"} (set (map :name props))))))

(t/deftest a-member-reports-its-component-id
  ;; Phase 03/04 target a component, not a shape — the id has to be reachable.
  (let [[s] (at/variant-sets one-set-data one-set-objects)]
    (t/is (= #{(str cid-1) (str cid-2)}
             (set (map :componentId (:members s)))))))

(t/deftest a-nested-container-is-still-found
  ;; A container relocated into a board vanishes from a top-level scan, and the
  ;; agent would then rebuild a set that already exists.
  (let [board-id (uuid/custom 4 1)
        objs     (assoc one-set-objects
                        board-id {:id board-id :name "Page" :type :frame :shapes [vid]})]
    (t/is (= 1 (count (at/variant-sets one-set-data objs))))))

;; ---------------------------------------------------------------------------
;; variant-naming-hint — the path convention
;;
;; combine-as-variants derives the set name from the common PATH prefix of the
;; member names. No shared prefix and it falls back to "Component/" + the name,
;; so the set is called "Component". Names like "Badge / Compact" give a set
;; called "Badge"; path depth sets the number of axes.
;; ---------------------------------------------------------------------------

(t/deftest a-shared-path-prefix-needs-no-hint
  (t/is (nil? (at/variant-naming-hint ["Badge / Compact" "Badge / Large"]))))

(t/deftest a-deeper-shared-prefix-needs-no-hint
  (t/is (nil? (at/variant-naming-hint ["Chip / Small / Hover" "Chip / Large / Default"]))))

(t/deftest no-shared-prefix-is-hinted
  (let [hint (at/variant-naming-hint ["Card" "Card Large"])]
    (t/is (some? hint))
    (t/is (str/includes? hint "Component"))))

(t/deftest the-hint-suggests-a-concrete-rename
  ;; A hint the agent can't act on is noise. It has to show the shape of the fix.
  (let [hint (at/variant-naming-hint ["Card" "Card Large"])]
    (t/is (str/includes? hint "/"))))

(t/deftest a-partial-word-overlap-is-not-a-path-prefix
  ;; "Card" and "Card Large" LOOK related but share no path SEGMENT — the
  ;; distinction that decides whether the set is called "Card" or "Component".
  (t/is (some? (at/variant-naming-hint ["Card" "Card Large"]))))

(t/deftest one-name-being-a-prefix-path-of-another-still-counts
  (t/is (nil? (at/variant-naming-hint ["Card / A" "Card / B" "Card / C"]))))

;; ---------------------------------------------------------------------------
;; variant-property-problem
;; ---------------------------------------------------------------------------

(def ^:private a-set
  {:container? true
   :axes ["Size"]
   :member-ids #{"c-1" "c-2"}})

(t/deftest renaming-an-existing-axis-is-allowed
  (t/is (nil? (at/variant-property-problem a-set {:variantId "v" :property "Size" :rename "Scale"}))))

(t/deftest setting-a-members-value-is-allowed
  (t/is (nil? (at/variant-property-problem
               a-set {:variantId "v" :property "Size" :componentId "c-1" :value "Compact"}))))

(t/deftest a-non-container-is-rejected
  (let [problem (at/variant-property-problem
                 (assoc a-set :container? false) {:variantId "v" :property "Size" :rename "Scale"})]
    (t/is (str/includes? problem "create_variant"))))

(t/deftest an-unknown-axis-is-rejected-and-lists-the-real-ones
  ;; The agent guesses "Size" before reading; the message has to teach it.
  (let [problem (at/variant-property-problem
                 (assoc a-set :axes ["Property 1"])
                 {:variantId "v" :property "Size" :rename "Scale"})]
    (t/is (str/includes? problem "Property 1"))))

(t/deftest a-value-without-a-component-is-rejected
  (let [problem (at/variant-property-problem
                 a-set {:variantId "v" :property "Size" :value "Compact"})]
    (t/is (str/includes? problem "componentId"))))

(t/deftest a-component-outside-the-set-is-rejected
  (let [problem (at/variant-property-problem
                 a-set {:variantId "v" :property "Size" :componentId "c-9" :value "Compact"})]
    (t/is (str/includes? problem "not a member"))))

(t/deftest a-call-that-does-nothing-is-rejected
  ;; Neither rename nor value: the events would no-op silently and the agent
  ;; would read the empty success as "the axis is named now".
  (let [problem (at/variant-property-problem a-set {:variantId "v" :property "Size"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "nothing to do"))))

;; ---------------------------------------------------------------------------
;; axis-pos — name → index
;; ---------------------------------------------------------------------------

(t/deftest axis-pos-finds-the-index
  (t/is (= 0 (at/axis-pos ["Size" "State"] "Size")))
  (t/is (= 1 (at/axis-pos ["Size" "State"] "State"))))

(t/deftest axis-pos-is-nil-for-an-unknown-axis
  (t/is (nil? (at/axis-pos ["Size"] "Hierarchy"))))

(t/deftest axis-pos-resolves-before-a-rename-invalidates-it
  ;; Order of operations: pos comes from the CURRENT name. Resolving after the
  ;; rename would look up a name that no longer exists and silently no-op.
  (let [axes ["Size" "State"]
        pos  (at/axis-pos axes "State")]
    (t/is (= 1 pos))
    (t/is (nil? (at/axis-pos (assoc axes pos "Mode") "State")))))

;; ---------------------------------------------------------------------------
;; add-variant-problem
;;
;; `add-new-variant` takes EITHER a member or the container (variants.cljs:360
;; resolves a container to `(last (:shapes …))`, its primary) — so both are
;; accepted here. The plan expected the container to be an error; it isn't.
;; ---------------------------------------------------------------------------

(t/deftest a-member-can-be-grown-from
  (let [objs (objects (variant-member id-a "Card" vid))]
    (t/is (nil? (at/add-variant-problem objs id-a)))))

(t/deftest the-container-can-be-grown-from
  (let [objs (objects (container vid "Card" [id-a id-b]))]
    (t/is (nil? (at/add-variant-problem objs vid)))))

(t/deftest a-plain-frame-cannot-be-grown-from
  (let [objs    (objects (plain-frame id-a "Hero"))
        problem (at/add-variant-problem objs id-a)]
    (t/is (some? problem))
    (t/is (str/includes? problem "create_variant"))))

(t/deftest a-lone-main-component-cannot-be-grown-from
  ;; A main component that isn't in a set yet: the fix is create_variant with a
  ;; second component, not add_variant.
  (let [objs    (objects (main-instance id-a "Card"))
        problem (at/add-variant-problem objs id-a)]
    (t/is (str/includes? problem "create_variant"))))

(t/deftest an-unknown-id-is-rejected
  (let [problem (at/add-variant-problem (objects (variant-member id-a "Card" vid)) id-missing)]
    (t/is (some? problem))
    (t/is (str/includes? problem "page"))))

(t/deftest a-missing-id-is-rejected
  (t/is (some? (at/add-variant-problem {} nil))))

;; ---------------------------------------------------------------------------
;; layout-changes — public param names -> internal :layout-* keys
;;
;; The mapping is the substance of the tool. Derived from
;; common/types/shape/layout.cljc, NOT from the plugin's flex.cljs, which does
;; its own aliasing.
;; ---------------------------------------------------------------------------

(t/deftest dir-maps-to-layout-flex-dir
  (t/is (= :column (:layout-flex-dir (at/layout-changes {:dir "column"})))))

(t/deftest gaps-map-into-one-layout-gap-map
  (let [out (at/layout-changes {:rowGap 8 :columnGap 16})]
    (t/is (= {:row-gap 8 :column-gap 16} (:layout-gap out)))))

(t/deftest one-gap-alone-still-nests
  (t/is (= {:column-gap 16} (:layout-gap (at/layout-changes {:columnGap 16})))))

(t/deftest padding-maps-to-p1-p4-clockwise-from-top
  (let [out (at/layout-changes {:padding {:top 1 :right 2 :bottom 3 :left 4}})]
    (t/is (= {:p1 1 :p2 2 :p3 3 :p4 4} (:layout-padding out)))))

(t/deftest a-partial-padding-only-sets-what-was-given
  (t/is (= {:p1 10} (:layout-padding (at/layout-changes {:padding {:top 10}})))))

(t/deftest alignment-maps-to-keywords
  (let [out (at/layout-changes {:alignItems "center" :justifyContent "space-between"})]
    (t/is (= :center (:layout-align-items out)))
    (t/is (= :space-between (:layout-justify-content out)))))

(t/deftest wrap-maps-to-a-type-not-a-boolean
  ;; :layout-wrap-type is :wrap/:nowrap — a raw boolean would fail the schema
  (t/is (= :wrap (:layout-wrap-type (at/layout-changes {:wrap true}))))
  (t/is (= :nowrap (:layout-wrap-type (at/layout-changes {:wrap false})))))

(t/deftest absent-params-produce-no-keys
  ;; update-layout patches whatever it is given; a stray nil would clobber.
  (t/is (= {} (at/layout-changes {}))))

;; ---------------------------------------------------------------------------
;; grid — Phase 22: set_layout learns type: grid
;; ---------------------------------------------------------------------------

(t/deftest grid-is-a-valid-layout-type
  (t/is (nil? (at/layout-problem (objects (plain-frame id-a "Gallery")) id-a {:type "grid"}))))

(t/deftest a-css-flavoured-layout-type-is-rejected
  ;; The agent's instinct might be "flexbox" or "css-grid".
  (let [problem (at/layout-problem (objects (plain-frame id-a "Gallery")) id-a {:type "flexbox"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "grid"))
    (t/is (str/includes? problem "flex"))))

(t/deftest grid-still-requires-a-board
  (let [problem (at/layout-problem (objects {:id id-a :name "Box" :type :rect}) id-a {:type "grid"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "board"))))

(t/deftest columns-map-to-a-grid-track-vector
  ;; 3 columns → three tracks, each an :fr track (:flex 1), matching Penpot's
  ;; default-track-value.
  (let [tracks (at/grid-tracks 3)]
    (t/is (= 3 (count tracks)))
    (t/is (every? #(= :flex (:type %)) tracks))))

(t/deftest zero-or-missing-columns-adds-no-tracks
  ;; Omitting columns leaves calculate-params to infer them from the children —
  ;; that is the whole point of the auto-grid. Forcing tracks would fight it.
  (t/is (empty? (at/grid-tracks nil)))
  (t/is (empty? (at/grid-tracks 0))))

(t/deftest a-type-change-alone-is-enough-of-a-change
  ;; "make this a grid" with no other params must NOT trip the "nothing to
  ;; change" guard — the type IS the change.
  (t/is (nil? (at/layout-problem (objects (plain-frame id-a "G")) id-a {:type "grid"}))))

;; ---------------------------------------------------------------------------
;; layout-problem
;; ---------------------------------------------------------------------------

(t/deftest a-board-can-take-a-layout
  (t/is (nil? (at/layout-problem (objects (plain-frame id-a "Hero")) id-a {:dir "row"}))))

(t/deftest a-rect-cannot-take-a-layout
  (let [objs    (objects {:id id-a :name "Box" :type :rect})
        problem (at/layout-problem objs id-a {:dir "row"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "board"))))

(t/deftest an-unknown-layout-shape-is-rejected
  (t/is (some? (at/layout-problem {} id-missing {:dir "row"}))))

(t/deftest a-css-flavoured-dir-is-rejected-with-the-real-options
  ;; The agent's instinct is CSS: "horizontal", "flex-start". Listing the valid
  ;; values turns a wrong guess into a correct retry.
  (let [problem (at/layout-problem (objects (plain-frame id-a "Hero")) id-a {:dir "horizontal"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "row"))))

(t/deftest a-css-flavoured-align-is-rejected
  (let [problem (at/layout-problem (objects (plain-frame id-a "Hero")) id-a
                                   {:alignItems "flex-start"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "start"))))

(t/deftest removing-a-layout-that-is-not-there-is-rejected
  (let [problem (at/layout-problem (objects (plain-frame id-a "Hero")) id-a {:remove true})]
    (t/is (some? problem))
    (t/is (str/includes? problem "no layout"))))

(t/deftest removing-an-existing-layout-is-allowed
  (let [objs (objects (assoc (plain-frame id-a "Hero") :layout :flex))]
    (t/is (nil? (at/layout-problem objs id-a {:remove true})))))

(t/deftest a-call-with-nothing-to-change-is-rejected
  (let [problem (at/layout-problem (objects (plain-frame id-a "Hero")) id-a {})]
    (t/is (some? problem))))

;; ---------------------------------------------------------------------------
;; layout-child-attrs — public params -> internal :layout-item-* keys
;; ---------------------------------------------------------------------------

(t/deftest sizing-maps-to-layout-item-sizing
  (let [out (at/layout-child-attrs {:horizontalSizing "fill" :verticalSizing "auto"})]
    (t/is (= :fill (:layout-item-h-sizing out)))
    (t/is (= :auto (:layout-item-v-sizing out)))))

(t/deftest align-self-and-absolute-and-z-index-map
  (let [out (at/layout-child-attrs {:alignSelf "center" :absolute true :zIndex 3})]
    (t/is (= :center (:layout-item-align-self out)))
    (t/is (true? (:layout-item-absolute out)))
    (t/is (= 3 (:layout-item-z-index out)))))

(t/deftest margin-maps-to-m1-m4-clockwise-from-top
  (let [out (at/layout-child-attrs {:margin {:top 1 :right 2 :bottom 3 :left 4}})]
    (t/is (= {:m1 1 :m2 2 :m3 3 :m4 4} (:layout-item-margin out)))))

(t/deftest min-max-map-to-w-h-keys
  (let [out (at/layout-child-attrs {:minWidth 10 :maxWidth 20 :minHeight 30 :maxHeight 40})]
    (t/is (= 10 (:layout-item-min-w out)))
    (t/is (= 20 (:layout-item-max-w out)))
    (t/is (= 30 (:layout-item-min-h out)))
    (t/is (= 40 (:layout-item-max-h out)))))

(t/deftest absolute-false-is-kept-not-dropped
  ;; `false` is a real instruction ("rejoin the flow"), not an absent param —
  ;; a `some?` check keeps it, a truthiness check would silently drop it.
  (t/is (= {:layout-item-absolute false} (at/layout-child-attrs {:absolute false}))))

(t/deftest absent-child-params-produce-no-keys
  (t/is (= {} (at/layout-child-attrs {}))))

;; ---------------------------------------------------------------------------
;; layout-child-problem
;; ---------------------------------------------------------------------------

(def ^:private board-id (uuid/custom 5 1))

(defn- laid-out-board
  [id child-ids]
  {:id id :name "Row" :type :frame :layout :flex :shapes child-ids})

(t/deftest a-child-of-a-laid-out-board-is-fine
  (let [objs (objects (laid-out-board board-id [id-a])
                      (assoc (plain-frame id-a "Item") :parent-id board-id))]
    (t/is (nil? (at/layout-child-problem objs id-a {:horizontalSizing "fill"})))))

(t/deftest a-child-whose-parent-has-no-layout-is-rejected
  ;; update-layout-child writes the attrs anyway: they persist, do nothing, and
  ;; spring to life later. A silent no-op wearing a success message.
  (let [objs    (objects (assoc (plain-frame board-id "Plain") :shapes [id-a])
                         (assoc (plain-frame id-a "Item") :parent-id board-id))
        problem (at/layout-child-problem objs id-a {:horizontalSizing "fill"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "set_layout"))))

(t/deftest the-rejection-names-the-parent-to-fix
  (let [objs    (objects (assoc (plain-frame board-id "Plain") :shapes [id-a])
                         (assoc (plain-frame id-a "Item") :parent-id board-id))
        problem (at/layout-child-problem objs id-a {:horizontalSizing "fill"})]
    (t/is (str/includes? problem (str board-id)))))

(t/deftest a-top-level-shape-is-rejected
  (let [objs    (objects (plain-frame id-a "Loose"))
        problem (at/layout-child-problem objs id-a {:horizontalSizing "fill"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "nest_shape"))))

(t/deftest a-css-flavoured-sizing-is-rejected-with-the-real-options
  (let [objs    (objects (laid-out-board board-id [id-a])
                         (assoc (plain-frame id-a "Item") :parent-id board-id))
        problem (at/layout-child-problem objs id-a {:horizontalSizing "grow"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "fill"))))

(t/deftest a-child-call-with-nothing-to-change-is-rejected
  (let [objs (objects (laid-out-board board-id [id-a])
                      (assoc (plain-frame id-a "Item") :parent-id board-id))]
    (t/is (some? (at/layout-child-problem objs id-a {})))))

;; ---------------------------------------------------------------------------
;; token types — public DTCG names -> internal keywords
;;
;; The enum is DERIVED from cto/token-type->dtcg-token-type, never retyped: a
;; hand-copied list silently drifts the next time Penpot adds a type.
;; ---------------------------------------------------------------------------

(t/deftest every-offered-type-resolves-to-a-real-internal-type
  (t/is (seq at/token-type-names))
  (doseq [n at/token-type-names]
    (t/is (some? (at/token-type n)) (str n " does not resolve"))))

(t/deftest the-offered-types-cover-what-the-skills-ask-for
  (let [offered (set at/token-type-names)]
    (doseq [n ["color" "spacing" "borderRadius" "sizing" "opacity" "fontSizes" "number"]]
      (t/is (contains? offered n) (str n " should be offered")))))

(t/deftest composites-are-not-offered
  ;; :value is ::sm/any, so make-token would happily accept a string for a
  ;; typography token and author something malformed. Better to not offer it.
  (let [offered (set at/token-type-names)]
    (t/is (not (contains? offered "typography")))
    (t/is (not (contains? offered "shadow")))))

(t/deftest dtcg-names-map-to-internal-keywords
  (t/is (= :border-radius (at/token-type "borderRadius")))
  (t/is (= :spacing (at/token-type "spacing")))
  (t/is (= :stroke-width (at/token-type "borderWidth"))))

;; ---------------------------------------------------------------------------
;; token-problem
;; ---------------------------------------------------------------------------

(t/deftest a-spacing-token-is-allowed
  (t/is (nil? (at/token-problem {:type "spacing" :name "spacing.md" :value "16"}))))

(t/deftest an-unknown-type-is-rejected-and-lists-the-real-ones
  (let [problem (at/token-problem {:type "padding" :name "x" :value "1"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "spacing"))))

(t/deftest a-composite-type-is-rejected-honestly
  ;; Not "invalid" — it is a real Penpot type this tool cannot express yet.
  (let [problem (at/token-problem {:type "typography" :name "t" :value "x"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "structured"))))

(t/deftest a-missing-name-or-value-is-rejected
  (t/is (some? (at/token-problem {:type "color" :value "#fff"})))
  (t/is (some? (at/token-problem {:type "color" :name "c.x"}))))

(t/deftest a-zero-value-is-allowed
  ;; The `absolute false` lesson from Phase 07: 0 is a legitimate spacing value,
  ;; and a truthiness check would reject it.
  (t/is (nil? (at/token-problem {:type "spacing" :name "spacing.none" :value "0"}))))

(t/deftest an-alias-value-survives
  ;; "{color.blue.500}" is a reference to another token — a large part of what
  ;; makes a token system a system. Must not be validated away.
  (t/is (nil? (at/token-problem {:type "color" :name "color.brand" :value "{color.blue.500}"}))))

;; ---------------------------------------------------------------------------
;; token attrs — public camelCase names -> internal attr keywords
;; ---------------------------------------------------------------------------

(t/deftest every-internal-attr-has-a-public-name
  ;; Derived from cto/all-keys, so the map cannot drift from the schema.
  (doseq [k at/token-attr-universe]
    (t/is (some? (at/public-attr-name k)) (str k " has no public name"))))

(t/deftest public-names-round-trip
  (doseq [k at/token-attr-universe]
    (t/is (= k (at/token-attr (at/public-attr-name k))))))

(t/deftest the-gap-and-padding-names-are-what-the-skills-use
  (t/is (= :column-gap (at/token-attr "columnGap")))
  (t/is (= :row-gap (at/token-attr "rowGap")))
  (t/is (= :p1 (at/token-attr "paddingTop")))
  (t/is (= :p4 (at/token-attr "paddingLeft"))))

(t/deftest positional-radius-keys-get-semantic-names
  (t/is (= :r1 (at/token-attr "borderRadiusTopLeft")))
  (t/is (= :r3 (at/token-attr "borderRadiusBottomRight"))))

(t/deftest margins-get-semantic-names
  (t/is (= :m1 (at/token-attr "marginTop"))))

(t/deftest an-unknown-attr-does-not-resolve
  (t/is (nil? (at/token-attr "gap")))
  (t/is (nil? (at/token-attr "nonsense"))))

;; --- the regression guard: apply_tokens is the safe colouring path and the one
;; --- tool token-only-colors can never reject. Widening must not break it.

(t/deftest fill-still-works
  (t/is (= #{:fill} (at/attr-set nil ["fill"]))))

(t/deftest the-default-is-still-fill
  (t/is (= #{:fill} (at/attr-set nil []))))

(t/deftest the-legacy-stroke-alias-still-works
  ;; The old attr-set mapped "stroke" -> :stroke-color, and it is in the shipped
  ;; spec. Dropping it would silently break a name the agent may already use.
  (t/is (= #{:stroke-color} (at/attr-set nil ["stroke"]))))

;; ---------------------------------------------------------------------------
;; application-problem — type/attr compatibility
;; ---------------------------------------------------------------------------

(t/deftest a-spacing-token-on-a-gap-is-fine
  (t/is (nil? (at/application-problem {:type :spacing :name "spacing.md"} ["columnGap"]))))

(t/deftest a-colour-token-on-a-fill-is-fine
  (t/is (nil? (at/application-problem {:type :color :name "color.brand"} ["fill"]))))

(t/deftest a-colour-token-on-padding-is-a-category-error
  (let [problem (at/application-problem {:type :color :name "color.brand"} ["paddingTop"])]
    (t/is (some? problem))
    (t/is (str/includes? problem "color.brand"))))

(t/deftest the-mismatch-message-lists-what-the-type-CAN-bind-to
  ;; Listing the valid attrs for THIS type is shorter and more useful than the
  ;; whole 40-key universe.
  (let [problem (at/application-problem {:type :color :name "color.brand"} ["paddingTop"])]
    (t/is (str/includes? problem "fill"))))

(t/deftest an-unknown-attr-is-rejected
  (let [problem (at/application-problem {:type :spacing :name "spacing.md"} ["gap"])]
    (t/is (some? problem))
    (t/is (str/includes? problem "gap"))))

;; ---------------------------------------------------------------------------
;; tokens-by-type — read_design's token section
;; ---------------------------------------------------------------------------

(defn- tok
  ([type name value] (tok type name value nil))
  ([type name value resolved]
   (cond-> {:type type :name name :value value}
     (some? resolved) (assoc :resolved-value resolved))))

(t/deftest tokens-are-grouped-by-their-public-type-name
  ;; DTCG names, the same ones create_token takes — the agent should be able to
  ;; read a type here and pass it straight back.
  (let [out (at/tokens-by-type [(tok :color "color.brand" "#6366f1")
                                (tok :spacing "spacing.md" "16")
                                (tok :border-radius "radius.card" "12")])]
    (t/is (= #{"color" "spacing" "borderRadius"} (set (keys out))))))

(t/deftest several-tokens-of-one-type-group-together
  (let [out (at/tokens-by-type [(tok :spacing "spacing.sm" "8")
                                (tok :spacing "spacing.md" "16")])]
    (t/is (= 2 (count (get out "spacing"))))))

(t/deftest a-type-with-no-tokens-is-absent-not-empty
  (let [out (at/tokens-by-type [(tok :color "color.brand" "#6366f1")])]
    (t/is (= ["color"] (keys out)))
    (t/is (not (contains? out "spacing")))))

(t/deftest no-tokens-yields-nothing
  (t/is (empty? (at/tokens-by-type []))))

(t/deftest a-literal-token-shows-just-its-value
  (let [[t] (get (at/tokens-by-type [(tok :spacing "spacing.md" "16" "16")]) "spacing")]
    (t/is (= "16" (:value t)))
    (t/is (not (contains? t :resolvedValue)))))

(t/deftest an-alias-shows-both-its-reference-and-what-it-resolves-to
  ;; An agent that sees only "#6366f1" cannot tell a literal from a reference —
  ;; and that distinction is most of what penpot-audit-tokens looks for.
  (let [[t] (get (at/tokens-by-type [(tok :color "color.brand" "{color.blue.500}" "#6366f1")])
                 "color")]
    (t/is (= "{color.blue.500}" (:value t)))
    (t/is (= "#6366f1" (:resolvedValue t)))))

;; ---------------------------------------------------------------------------
;; delete-problem / duplicate-problem
;; ---------------------------------------------------------------------------

(t/deftest a-plain-shape-can-be-deleted
  (t/is (nil? (at/delete-problem (objects (plain-frame id-a "Hero")) [id-a]))))

(t/deftest deleting-nothing-is-rejected
  (let [problem (at/delete-problem {} [])]
    (t/is (some? problem))
    (t/is (str/includes? problem "shapeIds"))))

(t/deftest deleting-an-unknown-id-is-rejected
  (let [problem (at/delete-problem (objects (plain-frame id-a "Hero")) [id-a id-missing])]
    (t/is (some? problem))
    (t/is (str/includes? problem (str id-missing)))))

(t/deftest deleting-names-every-unknown-id-at-once
  (let [problem (at/delete-problem {} [id-a id-b])]
    (t/is (str/includes? problem (str id-a)))
    (t/is (str/includes? problem (str id-b)))))

(t/deftest a-plain-shape-can-be-duplicated
  (t/is (nil? (at/duplicate-problem (objects (plain-frame id-a "Hero")) [id-a]))))

(t/deftest duplicating-a-shape-inside-a-component-copy-is-rejected
  ;; duplicate-shapes filters these out with allow-duplicate? and then no-ops on
  ;; the empty set — success with nothing done.
  (let [copy-head (assoc (plain-frame id-b "Card copy") :shape-ref (uuid/custom 7 1)
                         :component-id (uuid/custom 7 2) :component-root true)
        inner     (assoc (plain-frame id-a "Inner") :parent-id id-b
                         :shape-ref (uuid/custom 7 3))
        problem   (at/duplicate-problem (objects copy-head inner) [id-a])]
    (t/is (some? problem))
    (t/is (str/includes? problem "component copy"))))

(t/deftest duplicating-nothing-is-rejected
  (t/is (some? (at/duplicate-problem {} []))))

(t/deftest the-duplicate-tool-tells-the-truth-about-offsets
  ;; calc-duplicate-delta (selection.cljs:439) offsets ONLY frames: "The default
  ;; is leave normal shapes in place, but put new frames to the right of the
  ;; original." A note promising an offset would be false for every rect — and a
  ;; copy sitting invisibly on its original is the thing the agent must be told.
  (let [spec (first (filter #(= "duplicate_shape" (:name %)) at/tool-specs))]
    (t/is (str/includes? (:description spec) "BOARD"))
    (t/is (str/includes? (:description spec) "ON TOP"))))

;; ---------------------------------------------------------------------------
;; group-problem / ungroup-problem
;;
;; Both events filter silently and then no-op on the empty set: group-shapes
;; removes copy-children and variants, ungroup-shapes removes copy-children,
;; components and variant containers.
;; ---------------------------------------------------------------------------

(t/deftest two-plain-shapes-can-be-grouped
  (let [objs (objects (plain-frame id-a "A") (plain-frame id-b "B"))]
    (t/is (nil? (at/group-problem objs [id-a id-b])))))

(t/deftest one-shape-can-be-grouped
  ;; Penpot allows ⌘G on a single shape; the plan assumed it needed two.
  (t/is (nil? (at/group-problem (objects (plain-frame id-a "A")) [id-a]))))

(t/deftest grouping-nothing-is-rejected
  (t/is (some? (at/group-problem {} []))))

(t/deftest grouping-a-variant-member-is-rejected
  (let [objs    (objects (variant-member id-a "Card" vid) (plain-frame id-b "B"))
        problem (at/group-problem objs [id-a id-b])]
    (t/is (some? problem))
    (t/is (str/includes? problem "variant"))))

(t/deftest grouping-inside-a-component-copy-is-rejected
  (let [head  (assoc (plain-frame id-b "Card copy") :shape-ref (uuid/custom 8 1))
        inner (assoc (plain-frame id-a "Inner") :parent-id id-b)
        problem (at/group-problem (objects head inner) [id-a])]
    (t/is (some? problem))
    (t/is (str/includes? problem "component copy"))))

(t/deftest a-group-can-be-ungrouped
  (let [objs (objects {:id id-a :name "G" :type :group :shapes [id-b]})]
    (t/is (nil? (at/ungroup-problem objs [id-a])))))

(t/deftest a-board-can-be-ungrouped
  ;; ungroup-shapes handles frames via remove-frame-changes — the plan wrongly
  ;; assumed a board should be rejected here.
  (t/is (nil? (at/ungroup-problem (objects (plain-frame id-a "Board")) [id-a]))))

(t/deftest a-rect-cannot-be-ungrouped
  (let [problem (at/ungroup-problem (objects {:id id-a :name "Box" :type :rect}) [id-a])]
    (t/is (some? problem))
    (t/is (str/includes? problem "group"))))

(t/deftest a-component-cannot-be-ungrouped
  ;; groups.cljs says so in its own comment: "components can't be ungrouped"
  (let [objs    (objects (assoc (main-instance id-a "Card") :component-root true
                                :component-id (uuid/custom 8 2)))
        problem (at/ungroup-problem objs [id-a])]
    (t/is (some? problem))
    (t/is (str/includes? problem "component"))))

(t/deftest a-variant-container-cannot-be-ungrouped
  (let [problem (at/ungroup-problem (objects (container vid "Card" [id-a])) [vid])]
    (t/is (some? problem))
    (t/is (str/includes? problem "variant"))))

(t/deftest ungrouping-nothing-is-rejected
  (t/is (some? (at/ungroup-problem {} []))))

;; ---------------------------------------------------------------------------
;; library-components — read_design's components section
;; ---------------------------------------------------------------------------

(def ^:private local-file-id (uuid/custom 9 1))
(def ^:private lib-file-id (uuid/custom 9 2))

(defn- comp-entry
  ([id name] {:id id :name name})
  ([id name extra] (merge {:id id :name name} extra)))

(def ^:private libraries
  {local-file-id {:name "My File"
                  :data {:components {cid-1 (comp-entry cid-1 "Card")
                                      cid-2 (comp-entry cid-2 "Button")}}}
   lib-file-id   {:name "Design System"
                  :data {:components {(uuid/custom 9 5) (comp-entry (uuid/custom 9 5) "DS Input")}}}})

(t/deftest local-components-are-listed
  (let [out (at/library-components libraries local-file-id)]
    (t/is (contains? (set (map :name out)) "Card"))
    (t/is (contains? (set (map :name out)) "Button"))))

(t/deftest a-local-component-carries-no-fileId
  ;; It is the default target — saying so on every entry is payload for nothing.
  (let [card (first (filter #(= "Card" (:name %)) (at/library-components libraries local-file-id)))]
    (t/is (not (contains? card :fileId)))))

(t/deftest connected-library-components-are-listed-with-their-file
  ;; instantiate-component takes a file-id, so cross-library placement works —
  ;; but only if the agent can see which library a component lives in.
  (let [input (first (filter #(= "DS Input" (:name %)) (at/library-components libraries local-file-id)))]
    (t/is (some? input))
    (t/is (= (str lib-file-id) (:fileId input)))
    (t/is (= "Design System" (:library input)))))

(t/deftest deleted-components-are-not-listed
  (let [libs {local-file-id {:name "My File"
                             :data {:components {cid-1 (comp-entry cid-1 "Gone" {:deleted true})
                                                 cid-2 (comp-entry cid-2 "Card")}}}}]
    (t/is (= ["Card"] (map :name (at/library-components libs local-file-id))))))

(t/deftest variant-members-are-not-listed-again
  ;; They are already under :variants with their componentIds; listing them here
  ;; too would double the payload for a set-heavy file.
  (let [libs {local-file-id {:name "My File"
                             :data {:components
                                    {cid-1 (comp-entry cid-1 "Card" {:variant-id vid})
                                     cid-2 (comp-entry cid-2 "Button")}}}}]
    (t/is (= ["Button"] (map :name (at/library-components libs local-file-id))))))

;; ---------------------------------------------------------------------------
;; instance-problem
;; ---------------------------------------------------------------------------

(t/deftest a-known-component-can-be-instantiated
  (t/is (nil? (at/instance-problem libraries local-file-id
                                   {:componentId (str cid-1) :x 0 :y 0}))))

(t/deftest an-unknown-component-is-rejected
  (let [problem (at/instance-problem libraries local-file-id
                                     {:componentId (str id-missing) :x 0 :y 0})]
    (t/is (some? problem))
    (t/is (str/includes? problem "read_design"))))

(t/deftest a-component-from-another-library-needs-its-fileId
  ;; Without fileId we would look in the local file and reject something real.
  (let [ds (str (uuid/custom 9 5))]
    (t/is (some? (at/instance-problem libraries local-file-id {:componentId ds :x 0 :y 0})))
    (t/is (nil? (at/instance-problem libraries local-file-id
                                     {:componentId ds :fileId (str lib-file-id) :x 0 :y 0})))))

(t/deftest a-missing-position-is-rejected
  ;; instantiate-component asserts (gpt/point? position) — a nil would throw an
  ;; assertion rather than return a message.
  (let [problem (at/instance-problem libraries local-file-id {:componentId (str cid-1)})]
    (t/is (some? problem))
    (t/is (str/includes? problem "x"))))

(t/deftest a-zero-position-is-allowed
  (t/is (nil? (at/instance-problem libraries local-file-id
                                   {:componentId (str cid-1) :x 0 :y 0}))))

;; ---------------------------------------------------------------------------
;; bounded — every list read_design returns must say when it held back
;; ---------------------------------------------------------------------------

(t/deftest a-short-list-is-returned-whole-and-says-nothing
  (let [[items omitted] (at/bounded [1 2 3] 10 "shapes")]
    (t/is (= [1 2 3] items))
    (t/is (nil? omitted))))

(t/deftest a-long-list-is-cut-and-says-so
  (let [[items omitted] (at/bounded (range 100) 10 "shapes")]
    (t/is (= 10 (count items)))
    (t/is (some? omitted))
    (t/is (str/includes? omitted "10"))
    (t/is (str/includes? omitted "100"))))

(t/deftest the-omission-note-says-how-to-get-the-rest
  ;; A cut the agent cannot act on is just a cut.
  (let [[_ omitted] (at/bounded (range 100) 10 "shapes")]
    (t/is (str/includes? omitted "find_shapes"))))

;; ---------------------------------------------------------------------------
;; summarize-shape — child visibility
;; ---------------------------------------------------------------------------

(t/deftest a-shape-with-children-says-how-many
  ;; Without this the agent cannot tell an empty board from one it can't see into.
  (let [objs (objects (assoc (plain-frame id-a "Board") :shapes [id-b id-c])
                      (plain-frame id-b "X") (plain-frame id-c "Y"))
        out  (at/summarize-shape objs id-a)]
    (t/is (= 2 (:childCount out)))))

(t/deftest a-childless-shape-says-nothing-about-children
  (let [out (at/summarize-shape (objects (plain-frame id-a "Leaf")) id-a)]
    (t/is (not (contains? out :childCount)))))

;; ---------------------------------------------------------------------------
;; shape-matches? — find_shapes
;; ---------------------------------------------------------------------------

(t/deftest find-matches-on-name-substring-case-insensitively
  (let [s (plain-frame id-a "Primary Button")]
    (t/is (at/shape-matches? s {:name "button"}))
    (t/is (at/shape-matches? s {:name "PRIMARY"}))
    (t/is (not (at/shape-matches? s {:name "card"})))))

(t/deftest find-matches-on-type
  (let [s {:id id-a :name "Box" :type :rect}]
    (t/is (at/shape-matches? s {:type "rect"}))
    (t/is (not (at/shape-matches? s {:type "board"})))))

(t/deftest find-matches-on-type-using-the-agents-vocabulary
  ;; create_shape takes "board"; the internal type is :frame. The agent should
  ;; not have to know that asymmetry.
  (let [s (plain-frame id-a "Hero")]
    (t/is (at/shape-matches? s {:type "board"}))
    (t/is (at/shape-matches? s {:type "frame"}))))

(t/deftest find-combines-criteria
  (let [s (plain-frame id-a "Primary Button")]
    (t/is (at/shape-matches? s {:name "button" :type "board"}))
    (t/is (not (at/shape-matches? s {:name "button" :type "rect"})))))

(t/deftest find-with-no-criteria-matches-nothing
  ;; A query that matches everything is a dump, and dumps are what this phase
  ;; exists to stop.
  (t/is (not (at/shape-matches? (plain-frame id-a "Hero") {}))))

;; ---------------------------------------------------------------------------
;; shadow->shape / style-attrs — Phase 14's widening of modify_shape
;; ---------------------------------------------------------------------------

(t/deftest a-shadow-maps-to-penpots-shape
  (let [s (at/shadow->shape {:offsetX 0 :offsetY 4 :blur 8 :spread 0 :color "#000000" :opacity 0.25})]
    (t/is (= 0 (:offset-x s)))
    (t/is (= 4 (:offset-y s)))
    (t/is (= 8 (:blur s)))
    (t/is (= :drop-shadow (:style s)))
    (t/is (false? (:hidden s)))))

(t/deftest a-shadows-colour-is-a-map-not-a-string
  ;; schema:color is a map — a bare hex string would fail the schema.
  (let [s (at/shadow->shape {:offsetY 4 :blur 8 :color "#112233" :opacity 0.5})]
    (t/is (= "#112233" (:color (:color s))))
    (t/is (= 0.5 (:opacity (:color s))))))

(t/deftest an-inner-shadow-is-selectable
  (t/is (= :inner-shadow (:style (at/shadow->shape {:style "inner-shadow" :blur 4})))))

(t/deftest shadow-defaults-are-sane
  (let [s (at/shadow->shape {})]
    (t/is (= 0 (:offset-x s)))
    (t/is (= 0 (:spread s)))
    (t/is (some? (:id s)))))

(t/deftest radius-maps-to-r1-through-r4
  (let [out (at/style-attrs {:radius 12})]
    (t/is (= 12 (:r1 out)))
    (t/is (= 12 (:r4 out)))))

(t/deftest opacity-zero-is-kept
  ;; 0 is a legitimate opacity — the same trap as `absolute false` and "0" spacing.
  (t/is (= {:opacity 0} (at/style-attrs {:opacity 0}))))

(t/deftest absent-style-params-produce-no-keys
  (t/is (= {} (at/style-attrs {}))))

;; --- the guard: this is the phase, not an afterthought

(t/deftest shadow-colours-are-collected-for-the-guard
  ;; token-only-colors watches fills and strokes. A shadow carries a colour too —
  ;; widening modify_shape without widening the guard opens a second, unwatched
  ;; path for raw hex, and it would be the MOST used one.
  (t/is (= ["#ff0000"] (at/input-colors {:shadow {:color "#ff0000"}}))))

(t/deftest fill-and-stroke-are-still-collected
  (t/is (= #{"#111111" "#222222"} (set (at/input-colors {:fill "#111111" :stroke "#222222"})))))

(t/deftest every-colour-in-one-call-is-collected
  (let [out (set (at/input-colors {:fill "#111111" :stroke "#222222" :shadow {:color "#333333"}}))]
    (t/is (= #{"#111111" "#222222" "#333333"} out))))

(t/deftest a-call-with-no-colours-collects-none
  (t/is (empty? (at/input-colors {:radius 8 :opacity 0.5}))))

;; ---------------------------------------------------------------------------
;; code-problem — generate_code
;;
;; The skill's method is COMPARISON (design vs shipped code), so a half
;; stylesheet produces confidently false findings. Better to refuse and be
;; narrowed than to answer with part of the truth.
;; ---------------------------------------------------------------------------

(t/deftest a-shape-can-be-inspected
  (t/is (nil? (at/code-problem (objects (plain-frame id-a "Card")) [id-a] "html"))))

(t/deftest inspecting-nothing-is-rejected
  (let [problem (at/code-problem {} [] "html")]
    (t/is (some? problem))
    (t/is (str/includes? problem "select"))))

(t/deftest an-unknown-shape-is-rejected
  (let [problem (at/code-problem (objects (plain-frame id-a "Card")) [id-missing] "html")]
    (t/is (some? problem))
    (t/is (str/includes? problem (str id-missing)))))

(t/deftest an-unknown-markup-type-is-rejected-with-the-real-ones
  (let [problem (at/code-problem (objects (plain-frame id-a "Card")) [id-a] "jsx")]
    (t/is (some? problem))
    (t/is (str/includes? problem "html"))
    (t/is (str/includes? problem "svg"))))

(t/deftest svg-is-a-valid-type
  (t/is (nil? (at/code-problem (objects (plain-frame id-a "Card")) [id-a] "svg"))))

;; ---------------------------------------------------------------------------
;; fill-summary — Phase 17: read a shape's paint
;;
;; The agent must RECOGNISE and COPY, not round-trip internals — so these are
;; compact descriptors, not raw attrs. Only one of fill-color /
;; fill-color-gradient / fill-image is ever set (valid-fill-attrs).
;; ---------------------------------------------------------------------------

(t/deftest a-solid-fill-reports-its-copyable-colour
  (let [[f] (at/fill-summary [{:fill-color "#6366f1" :fill-opacity 1}])]
    (t/is (= "solid" (:type f)))
    (t/is (= "#6366f1" (:color f)))))

(t/deftest a-solid-fills-opacity-shows-only-when-not-fully-opaque
  (t/is (nil? (:opacity (first (at/fill-summary [{:fill-color "#fff" :fill-opacity 1}])))))
  (t/is (= 0.5 (:opacity (first (at/fill-summary [{:fill-color "#fff" :fill-opacity 0.5}]))))))

(t/deftest a-token-backed-fill-says-so
  ;; A fill bound to a token is not a raw colour to copy — it is a reference to
  ;; reuse, which is most of what penpot-audit-tokens cares about.
  (let [[f] (at/fill-summary [{:fill-color "#6366f1" :fill-color-ref-id (uuid/custom 6 1)}])]
    (t/is (true? (:fromToken f)))))

(t/deftest a-gradient-fill-is-distinguished-and-carries-its-stops
  (let [[f] (at/fill-summary [{:fill-color-gradient
                               {:type :linear :start-x 0 :start-y 0 :end-x 1 :end-y 1 :width 1
                                :stops [{:color "#000000" :offset 0}
                                        {:color "#ffffff" :offset 1}]}}])]
    (t/is (= "gradient" (:type f)))
    (t/is (= "linear" (:gradient f)))
    (t/is (= ["#000000" "#ffffff"] (:stops f)))))

(t/deftest an-image-fill-is-distinguished-and-named
  ;; The novel case: images are unreachable today. The agent cannot reproduce the
  ;; raster, so the useful answer is "this is an image, here is its handle".
  (let [id (uuid/custom 6 2)
        [f] (at/fill-summary [{:fill-image {:id id :width 800 :height 600
                                            :mtype "image/png" :name "mars.png"}}])]
    (t/is (= "image" (:type f)))
    (t/is (= (str id) (:imageId f)))
    (t/is (= 800 (:width f)))
    (t/is (= "mars.png" (:name f)))))

(t/deftest several-fills-are-all-reported-in-order
  (let [out (at/fill-summary [{:fill-color "#111111"} {:fill-color "#222222"}])]
    (t/is (= ["#111111" "#222222"] (map :color out)))))

(t/deftest no-fills-yields-nothing
  (t/is (empty? (at/fill-summary nil)))
  (t/is (empty? (at/fill-summary []))))

;; --- and the payload economy the phase's own notes demand

(t/deftest a-shape-without-fills-carries-no-fills-key
  (t/is (not (contains? (at/summarize-shape (objects (plain-frame id-a "Bare")) id-a {:look? true})
                        :fills))))

(t/deftest a-filled-shape-carries-its-fills-when-asked
  (let [objs (objects (assoc (plain-frame id-a "Card") :fills [{:fill-color "#6366f1"}]))
        out  (at/summarize-shape objs id-a {:look? true})]
    (t/is (= [{:type "solid" :color "#6366f1"}] (:fills out)))))

(t/deftest fills-are-off-by-default
  ;; read_design's broad list is the most-called payload we produce; paint on
  ;; every shape cost ~11% of the 20k budget and would crowd out Phase 18.
  ;; The selection and find_shapes hits opt in.
  (let [objs (objects (assoc (plain-frame id-a "Card") :fills [{:fill-color "#6366f1"}]))]
    (t/is (not (contains? (at/summarize-shape objs id-a) :fills)))))

;; ---------------------------------------------------------------------------
;; fill->shape — Phase 17's write half
;;
;; The write shape MIRRORS the read shape, so a descriptor from read_design can
;; be passed straight back: read -> copy -> write round-trips.
;; ---------------------------------------------------------------------------

(t/deftest a-bare-hex-still-works
  ;; The shipped param was a hex string; it must keep working.
  (t/is (= [{:fill-color "#6366f1" :fill-opacity 1}] (at/fill->shape "#6366f1"))))

(t/deftest a-solid-descriptor-works
  (t/is (= [{:fill-color "#6366f1" :fill-opacity 1}]
           (at/fill->shape {:type "solid" :color "#6366f1"}))))

(t/deftest a-solid-descriptor-keeps-its-opacity
  (t/is (= [{:fill-color "#6366f1" :fill-opacity 0.5}]
           (at/fill->shape {:type "solid" :color "#6366f1" :opacity 0.5}))))

(t/deftest a-gradient-descriptor-round-trips-from-the-read-shape
  (let [out (first (at/fill->shape {:type "gradient" :gradient "linear"
                                    :stops ["#ff0000" "#0000ff"]}))
        g   (:fill-color-gradient out)]
    (t/is (= :linear (:type g)))
    (t/is (= ["#ff0000" "#0000ff"] (mapv :color (:stops g))))
    ;; stops must be spread across the axis, or every stop sits at 0
    (t/is (= [0 1] (mapv :offset (:stops g))))))

(t/deftest a-three-stop-gradient-spaces-its-offsets
  (let [g (:fill-color-gradient (first (at/fill->shape {:type "gradient" :stops ["#000" "#888" "#fff"]})))]
    (t/is (= [0 0.5 1] (mapv :offset (:stops g))))))

(t/deftest an-image-descriptor-round-trips-by-id
  ;; The replicate case: reuse the SAME raster rather than approximate it.
  (let [id (uuid/custom 6 3)
        out (first (at/fill->shape {:type "image" :imageId (str id)
                                    :width 800 :height 600 :mtype "image/png"}))]
    (t/is (= id (:id (:fill-image out))))
    (t/is (= 800 (:width (:fill-image out))))))

;; --- fill-problem: validation

(t/deftest a-gradient-without-stops-is-rejected
  (let [problem (at/fill-problem {:type "gradient" :stops []})]
    (t/is (some? problem))
    (t/is (str/includes? problem "stops"))))

(t/deftest an-image-without-an-id-is-rejected
  (let [problem (at/fill-problem {:type "image"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "imageId"))))

(t/deftest an-image-with-an-unparseable-id-is-rejected
  (t/is (some? (at/fill-problem {:type "image" :imageId "not-a-uuid"}))))

(t/deftest an-unknown-fill-type-is-rejected-with-the-real-ones
  (let [problem (at/fill-problem {:type "pattern"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "gradient"))
    (t/is (str/includes? problem "image"))))

(t/deftest a-solid-without-a-colour-is-rejected
  (t/is (some? (at/fill-problem {:type "solid"}))))

(t/deftest a-valid-fill-has-no-problem
  (t/is (nil? (at/fill-problem "#6366f1")))
  (t/is (nil? (at/fill-problem {:type "gradient" :stops ["#000" "#fff"]})))
  (t/is (nil? (at/fill-problem {:type "image" :imageId (str (uuid/custom 6 4))}))))

;; --- the guard must see every colour a gradient carries

(t/deftest every-gradient-stop-is-collected-for-the-guard
  ;; A gradient is several raw colours in a trench coat. If the guard only saw
  ;; `fill` as a string, all of them would walk straight past token-only-colors.
  (t/is (= #{"#ff0000" "#0000ff"}
           (set (at/input-colors {:fill {:type "gradient" :stops ["#ff0000" "#0000ff"]}})))))

(t/deftest an-image-fill-carries-no-colour-to-guard
  (t/is (empty? (at/input-colors {:fill {:type "image" :imageId "x"}}))))

(t/deftest a-solid-descriptor-is-collected-for-the-guard
  (t/is (= ["#6366f1"] (at/input-colors {:fill {:type "solid" :color "#6366f1"}}))))

;; ---------------------------------------------------------------------------
;; effect-summary — Phase 18: read a shape's effects
;;
;; The descriptor MIRRORS Phase 14's write params 1:1, so read -> write is a
;; straight copy with no attr-name translation. Only present, non-default attrs
;; are emitted: a plain shape must add nothing.
;; ---------------------------------------------------------------------------

(t/deftest a-plain-shape-has-no-effects
  (t/is (empty? (at/effect-summary (plain-frame id-a "Bare")))))

(t/deftest a-uniform-radius-reads-back-as-phase-14-writes-it
  ;; Phase 14 takes `radius: 12`; the read must say `radius: 12`, not :r1..:r4.
  (t/is (= 12 (:radius (at/effect-summary (assoc (plain-frame id-a "C")
                                                 :r1 12 :r2 12 :r3 12 :r4 12))))))

(t/deftest a-zero-radius-is-not-reported
  ;; Every shape has r1..r4 = 0 by default; reporting it would cost the budget
  ;; for nothing.
  (t/is (nil? (:radius (at/effect-summary (assoc (plain-frame id-a "C")
                                                 :r1 0 :r2 0 :r3 0 :r4 0))))))

(t/deftest a-mixed-radius-reports-each-corner
  ;; Phase 14 can only write one radius for all four, so a mixed radius cannot
  ;; be copied in one call — say so rather than report a misleading single number.
  (let [out (at/effect-summary (assoc (plain-frame id-a "C") :r1 4 :r2 8 :r3 4 :r4 8))]
    (t/is (= {:topLeft 4 :topRight 8 :bottomRight 4 :bottomLeft 8} (:radius out)))))

(t/deftest a-reduced-opacity-is-reported
  (t/is (= 0.5 (:opacity (at/effect-summary (assoc (plain-frame id-a "C") :opacity 0.5))))))

(t/deftest a-full-opacity-is-not-reported
  (t/is (nil? (:opacity (at/effect-summary (assoc (plain-frame id-a "C") :opacity 1))))))

(t/deftest a-shadow-reads-back-in-phase-14s-own-param-names
  ;; The 1:1 mirror: these keys are exactly modify_shape's shadow params.
  (let [out (at/effect-summary
             (assoc (plain-frame id-a "C")
                    :shadow [{:style :drop-shadow :offset-x 0 :offset-y 4 :blur 12 :spread 0
                              :hidden false :color {:color "#000000" :opacity 0.25}}]))
        s   (:shadow out)]
    (t/is (= "drop-shadow" (:style s)))
    (t/is (= 0 (:offsetX s)))
    (t/is (= 4 (:offsetY s)))
    (t/is (= 12 (:blur s)))
    (t/is (= "#000000" (:color s)))
    (t/is (= 0.25 (:opacity s)))))

(t/deftest a-hidden-shadow-is-not-reported
  ;; It contributes nothing to the look, and copying it would be wrong.
  (t/is (nil? (:shadow (at/effect-summary
                        (assoc (plain-frame id-a "C")
                               :shadow [{:style :drop-shadow :hidden true :blur 4
                                         :color {:color "#000"}}]))))))

(t/deftest several-shadows-report-as-a-list
  (let [out (at/effect-summary
             (assoc (plain-frame id-a "C")
                    :shadow [{:style :drop-shadow :blur 4 :hidden false :color {:color "#111111"}}
                             {:style :inner-shadow :blur 8 :hidden false :color {:color "#222222"}}]))]
    (t/is (= 2 (count (:shadows out))))
    (t/is (nil? (:shadow out)))))

(t/deftest a-blur-is-reported-even-though-it-cannot-be-written
  ;; The transcript's complaint was a "glow" it could see but not inspect. A blur
  ;; IS that glow. modify_shape cannot write one, so — like an image fill in
  ;; Phase 17 — the honest move is to NAME it, not drop it silently.
  (let [out (at/effect-summary (assoc (plain-frame id-a "C")
                                      :blur {:type :layer-blur :value 8 :hidden false}))]
    (t/is (= 8 (:value (:blur out))))
    (t/is (= "layer-blur" (:type (:blur out))))))

(t/deftest a-hidden-blur-is-not-reported
  (t/is (nil? (:blur (at/effect-summary (assoc (plain-frame id-a "C")
                                               :blur {:type :layer-blur :value 8 :hidden true}))))))

(t/deftest a-non-normal-blend-mode-is-reported
  (t/is (= "multiply" (:blendMode (at/effect-summary (assoc (plain-frame id-a "C")
                                                            :blend-mode :multiply))))))

(t/deftest a-normal-blend-mode-is-not-reported
  (t/is (nil? (:blendMode (at/effect-summary (assoc (plain-frame id-a "C")
                                                    :blend-mode :normal))))))

;; --- payload economy, same rule as fills

(t/deftest effects-are-off-by-default
  (let [objs (objects (assoc (plain-frame id-a "C") :opacity 0.5))]
    (t/is (not (contains? (at/summarize-shape objs id-a) :opacity)))))

(t/deftest effects-appear-when-the-look-is-asked-for
  (let [objs (objects (assoc (plain-frame id-a "C") :opacity 0.5 :r1 8 :r2 8 :r3 8 :r4 8))
        out  (at/summarize-shape objs id-a {:look? true})]
    (t/is (= 0.5 (:opacity out)))
    (t/is (= 8 (:radius out)))))

;; ---------------------------------------------------------------------------
;; text-problem — Phase 19: edit the words
;;
;; Scope: content and alignment. Size / family / weight are already reachable via
;; apply_tokens (fontSize, fontFamily, fontWeight …), so this covers the rest —
;; per the phase's own "check the token path first".
;; ---------------------------------------------------------------------------

(defn- text-shape
  [id name]
  {:id id :name name :type :text})

(t/deftest a-text-shapes-content-can-be-rewritten
  (t/is (nil? (at/text-problem (objects (text-shape id-a "Heading")) id-a {:text "New words"}))))

(t/deftest a-text-shape-can-be-aligned
  (t/is (nil? (at/text-problem (objects (text-shape id-a "Heading")) id-a {:align "center"}))))

(t/deftest a-non-text-shape-is-rejected
  ;; The trap this guards: modify_shape's `name` renames the LAYER. An agent that
  ;; aims set_text at a board is confusing the label with the words.
  (let [problem (at/text-problem (objects (plain-frame id-a "Board")) id-a {:text "hi"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "text"))))

(t/deftest set-text-on-an-unknown-shape-is-rejected
  (t/is (some? (at/text-problem {} id-missing {:text "hi"}))))

(t/deftest set-text-with-nothing-to-change-is-rejected
  (let [problem (at/text-problem (objects (text-shape id-a "Heading")) id-a {})]
    (t/is (some? problem))
    (t/is (str/includes? problem "text"))))

(t/deftest an-unknown-align-is-rejected-with-the-real-ones
  (let [problem (at/text-problem (objects (text-shape id-a "H")) id-a {:align "middle"})]
    (t/is (some? problem))
    (t/is (str/includes? problem "center"))))

(t/deftest an-empty-string-is-a-real-edit
  ;; "" clears the text — a legitimate instruction, and the `some?`-not-truthy
  ;; trap for the fifth time (absolute false, "0" token, x: 0, opacity 0).
  (t/is (nil? (at/text-problem (objects (text-shape id-a "H")) id-a {:text ""}))))

;; ---------------------------------------------------------------------------
;; token sets and themes — Phase 20
;; ---------------------------------------------------------------------------

(t/deftest naming-a-set-that-exists-is-fine
  (t/is (nil? (at/token-set-problem ["Global" "modes/dark"] "modes/dark"))))

(t/deftest naming-a-set-that-does-not-exist-is-rejected-with-the-real-ones
  ;; The message must carry the set names, or the agent is guessing blind.
  (let [problem (at/token-set-problem ["Global" "modes/light"] "modes/dark")]
    (t/is (some? problem))
    (t/is (str/includes? problem "modes/light"))
    (t/is (str/includes? problem "Global"))))

(t/deftest omitting-the-set-is-fine
  ;; Default behaviour is Phase 08's: the library's existing set.
  (t/is (nil? (at/token-set-problem ["Global"] nil))))

(t/deftest naming-a-set-in-an-empty-library-is-rejected
  (let [problem (at/token-set-problem [] "modes/dark")]
    (t/is (some? problem))
    (t/is (str/includes? problem "create_token_set"))))

;; --- theme validation

(t/deftest a-theme-over-real-sets-is-fine
  (t/is (nil? (at/theme-problem ["Global" "modes/dark"] {:name "Dark" :sets ["modes/dark"]}))))

(t/deftest a-theme-needs-a-name
  (t/is (some? (at/theme-problem ["Global"] {:sets ["Global"]}))))

(t/deftest a-theme-over-a-missing-set-is-rejected
  (let [problem (at/theme-problem ["Global"] {:name "Dark" :sets ["modes/dark"]})]
    (t/is (some? problem))
    (t/is (str/includes? problem "modes/dark"))))

(t/deftest a-theme-needs-at-least-one-set
  ;; A theme that enables nothing is a switch wired to nothing — it would
  ;; "activate" and change literally nothing, which reads as a broken tool.
  (let [problem (at/theme-problem ["Global"] {:name "Dark" :sets []})]
    (t/is (some? problem))
    (t/is (str/includes? problem "set"))))

;; --- set naming: "/" is the group separator, which is how modes/* works

(t/deftest a-grouped-set-name-is-normalised
  (t/is (= "modes/dark" (at/normalize-set "modes / dark")))
  (t/is (= "modes/dark" (at/normalize-set "modes/dark"))))

(t/deftest a-set-name-is-required
  (let [problem (at/new-set-problem ["Global"] "")]
    (t/is (some? problem))))

(t/deftest creating-a-set-that-already-exists-is-rejected
  ;; create-token-set would overwrite it — and overwriting a set is how Phase 08
  ;; wiped the library.
  (let [problem (at/new-set-problem ["Global" "modes/dark"] "modes/dark")]
    (t/is (some? problem))
    (t/is (str/includes? problem "already"))))

;; ---------------------------------------------------------------------------
;; detach-problem — Phase 21
;;
;; The capability is a one-liner over dwl/detach-component; the value is refusing
;; the shapes of it that destroy something, and naming what the caller meant.
;; ---------------------------------------------------------------------------

(defn- copy-root
  [id name]
  {:id id :name name :type :frame
   :shape-ref (uuid/custom 4 9) :component-id (uuid/custom 4 8) :component-root true})

(t/deftest a-plain-copy-detaches
  (t/is (nil? (at/detach-problem (objects (copy-root id-a "Card copy")) id-a))))

(t/deftest a-shape-that-is-not-a-component-is-rejected
  (let [problem (at/detach-problem (objects (plain-frame id-a "Just a board")) id-a)]
    (t/is (some? problem))
    (t/is (str/includes? problem "not a component copy"))))

(t/deftest detaching-a-MAIN-is-rejected-and-says-what-they-meant
  ;; Detaching a main is nonsense — it IS the component. The agent almost
  ;; certainly meant one of its copies.
  (let [problem (at/detach-problem (objects (main-instance id-a "Card")) id-a)]
    (t/is (some? problem))
    (t/is (str/includes? problem "main"))))

(t/deftest detaching-a-variant-MEMBER-is-rejected
  ;; A member is the set's structure — detaching it would gut the set. Note the
  ;; message must NOT claim file corruption: gotcha #12 was refuted natively.
  (let [problem (at/detach-problem (objects (variant-member id-a "Card" vid)) id-a)]
    (t/is (some? problem))
    (t/is (str/includes? problem "variant set"))))

(t/deftest the-variant-rejection-does-not-claim-corruption
  ;; The playbook's "corrupted files and hung all subsequent saves" is the
  ;; PLUGIN path. Verified natively: saves continued, edits persisted, the file
  ;; reloaded clean. Repeating the claim would be a lie the agent acts on.
  (let [problem (at/detach-problem (objects (variant-member id-a "Card" vid)) id-a)]
    (t/is (not (str/includes? (str/lower problem) "corrupt")))))

(t/deftest a-nested-shape-inside-a-copy-names-the-root-to-detach
  ;; You cannot detach a piece of a copy — the message has to carry the root id
  ;; or the agent has nothing to act on.
  (let [root  (copy-root id-b "Card copy")
        inner (assoc (plain-frame id-a "Inner") :parent-id id-b :shape-ref (uuid/custom 4 7))
        problem (at/detach-problem (objects root inner) id-a)]
    (t/is (some? problem))
    (t/is (str/includes? problem (str id-b)))))

(t/deftest an-unknown-shape-is-rejected-by-detach
  (t/is (some? (at/detach-problem {} id-missing))))

;; ---------------------------------------------------------------------------
;; order preservation
;; ---------------------------------------------------------------------------

(t/deftest input-order-is-preserved
  ;; combine-as-variants only honours order for a sequential collection —
  ;; a set gets silently normalized to layer-tree order.
  (let [ids (at/variant-member-ids ["00000001-0001-0000-0000-000000000000"
                                    "00000001-0002-0000-0000-000000000000"])]
    (t/is (sequential? ids))
    (t/is (= 2 (count ids)))))

(t/deftest unparseable-ids-are-dropped-not-crashed
  (let [ids (at/variant-member-ids ["not-a-uuid"])]
    (t/is (= [] ids))))
