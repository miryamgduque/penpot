;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.elicitation
  "Pure logic for the ask_user elicitation form (the in-chat interview).

  The component keeps one ui-state map per question id —

    {:selected #{\"Warm & homey\" :other :decide} :other-text \"…\" :text \"…\"}

  — where `:selected` mixes the question's own option strings with the two
  keyword sentinels `:other` (the Other… chip, whose value is `:other-text`)
  and `:decide` (the \"Decide for me\" chip). Everything that turns that ui
  state into submitted answers lives here, DOM-free.

  On the wire, \"decide for me\" is the literal string `__decide__` — the
  ask_user tool description teaches the model to read it as a delegation."
  (:require
   [cuerdas.core :as str]))

(def decide-sentinel "__decide__")

;; Reference-image caps: per question and for the whole form — the images
;; ride the ask_user tool result onto the wire, so the composer's own
;; restraint (5 per message) applies here too.
(def max-images-per-question 3)
(def max-form-images 5)

(defn image-count
  "How many reference images the whole form currently holds."
  [form-state]
  (transduce (map (comp count :images)) + 0 (vals form-state)))

(defn image-room
  "How many more images question `id` may take, honoring both caps."
  [form-state id]
  (max 0 (min (- max-images-per-question (count (get-in form-state [id :images])))
              (- max-form-images (image-count form-state)))))

(defn form-images
  "Every attached reference image in form order, plus the per-question tally:
  {:images […] :counts {qid n}}. `:counts` is what lets the model tie each
  image block on the tool result back to the question it answers."
  [questions form-state]
  (reduce (fn [acc {:keys [id]}]
            (let [imgs (get-in form-state [id :images])]
              (if (seq imgs)
                (-> acc
                    (update :images into imgs)
                    (assoc-in [:counts id] (count imgs)))
                acc)))
          {:images [] :counts {}}
          questions))

(defn- clean
  [s]
  (when (string? s)
    (not-empty (str/trim s))))

(defn answer
  "The submitted value of one `question` given its ui `qstate`, or nil while
  it is unanswered: single → its option string (the user's own words when
  they picked Other…), multi → a vector of them, text → the string."
  [{:keys [type options]} {:keys [selected other-text text images]}]
  (let [selected (or selected #{})
        other    (clean other-text)]
    (case type
      ;; a reference image with no words is still an answer — the images
      ;; themselves ride the tool result; this line is their stand-in here
      "text"   (or (clean text)
                   (when (seq images) "(see the attached reference images)"))
      "single" (cond
                 (contains? selected :decide) decide-sentinel
                 (contains? selected :other)  other
                 :else                        (first (filter string? selected)))
      "multi"  (if (contains? selected :decide)
                 [decide-sentinel]
                 ;; the question's own option order, not the set's
                 (not-empty
                  (cond-> (filterv #(contains? selected %) options)
                    (and (contains? selected :other) other) (conj other))))
      nil)))

(defn toggle
  "The next `:selected` set after clicking `opt` — an option string, `:other`
  or `:decide` — on a question of `type`. Single-choice replaces (clicking
  the active chip clears it); multi toggles membership; \"Decide for me\" is
  exclusive either way, because delegating AND choosing is a contradiction."
  [type selected opt]
  (let [selected (or selected #{})]
    (cond
      (= :decide opt)   (if (contains? selected :decide) #{} #{:decide})
      (= "single" type) (if (contains? selected opt) #{} #{opt})
      :else             (let [s (disj selected :decide)]
                          (if (contains? s opt) (disj s opt) (conj s opt))))))

(defn answers
  "question id → submitted value, over every answered question. `form-state`
  is question id → ui state. Unanswered questions are simply absent — the
  tool description tells the model an omitted optional means 'skipped'."
  [questions form-state]
  (into {}
        (keep (fn [{:keys [id] :as q}]
                (when-some [v (answer q (get form-state id))]
                  [id v])))
        questions))

(defn complete?
  "Whether every required (non-`:optional`) question has an answer — the
  submit button's gate."
  [questions form-state]
  (every? (fn [{:keys [id optional] :as q}]
            (or (true? optional)
                (some? (answer q (get form-state id)))))
          questions))

(defn- display
  [v]
  (if (= decide-sentinel v) "Decide for me" v))

(defn summary
  "The transcript rendering of the submitted answers — one “Question — answer”
  line per answered question, in form order. This is what stays visible in
  the chat after the form collapses, so it must say exactly what was sent."
  [questions form-state]
  (->> questions
       (keep (fn [{:keys [id question] :as q}]
               (when-some [v (answer q (get form-state id))]
                 (str question " — "
                      (if (vector? v)
                        (str/join ", " (map display v))
                        (display v))))))
       (str/join "\n")))
