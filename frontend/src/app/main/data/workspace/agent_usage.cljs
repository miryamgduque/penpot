;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-usage
  "What the agent spent, as the panel's meter keeps it: tokens in total,
  and apart for each model that spent them, so each is priced at its own
  rate. Every place that adds to the meter adds with `add-usage`.")

(def empty-usage
  {:input-tokens 0 :output-tokens 0 :cache-read-tokens 0 :cache-write-tokens 0 :requests 0})

(def token-keys
  [:input-tokens :output-tokens :cache-read-tokens :cache-write-tokens :requests])

(defn attributed
  "`usage` marked as spent on `model` of `provider`: a side job's helper is
  not priced like the model the turn runs on. Usage with no model named
  stays unattributed, and is priced as the session's model."
  [usage provider model]
  (if (and (string? provider) (string? model))
    (let [tokens (select-keys usage token-keys)]
      (assoc tokens :by-model {(str provider ":" model)
                               (assoc tokens :provider provider :model model)}))
    usage))

(defn add-usage
  "Merges a round's usage into the running totals, keeping apart what was
  spent on each model."
  [a b]
  (let [a (or a empty-usage)
        b (or b empty-usage)]
    (cond-> (merge-with + (select-keys (merge empty-usage a) token-keys)
                        (select-keys (merge empty-usage b) token-keys))
      (or (:by-model a) (:by-model b))
      (assoc :by-model (merge-with (fn [x y]
                                     (merge (merge-with + (select-keys x token-keys)
                                                        (select-keys y token-keys))
                                            (select-keys x [:provider :model])))
                                   (:by-model a) (:by-model b))))))
