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
