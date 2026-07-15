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
