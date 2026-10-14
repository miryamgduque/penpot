;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.tool-failure
  "What an agent tool that failed answers.

  A tool can fail on the change it was asked for — a shape the file
  rejects, a value the schema does not take. That is the model's to read
  and correct, like any refusal, and the worker that ran it is fine. Every
  other failure fails the call and the worker is replaced: a turn token
  the backend refused, and above all the engine itself breaking, which can
  leave the worker's engine in a state no later call should meet.")

(def ^:private refused
  "Failures about who asks, not about the change."
  #{:agent-backend-refused :invalid-turn-token})

(defn- the-change?
  "Whether a failure says the change itself was not acceptable: it failed
  validation. Nothing else is taken for the tool's."
  [{:keys [type code]}]
  (and (not (contains? refused code))
       (or (= :data-validation code)
           (= :validation type))))

(defn answer
  "The answer a tool that threw with `cause` gives the model, or nil when
  the failure is not the tool's."
  [tool cause]
  (let [{:keys [code hint] :as data} (ex-data cause)]
    (when (the-change? data)
      {:content (str tool ": "
                     (or hint (ex-message cause) "the change could not be made")
                     (when (= :data-validation code)
                       " — the change would make the file invalid; check the values you passed"))
       :isError true})))
