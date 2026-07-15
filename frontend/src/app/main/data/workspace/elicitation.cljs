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

(defn- clean
  [s]
  (when (string? s)
    (not-empty (str/trim s))))

(defn answer
  "The submitted value of one `question` given its ui `qstate`, or nil while
  it is unanswered: single → its option string (the user's own words when
  they picked Other…), multi → a vector of them, text → the string."
  [{:keys [type options]} {:keys [selected other-text text]}]
  (let [selected (or selected #{})
        other    (clean other-text)]
    (case type
      "text"   (clean text)
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
