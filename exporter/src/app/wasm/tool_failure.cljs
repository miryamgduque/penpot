;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.tool-failure
  "What an agent tool that failed answers.

  A tool can fail on the change it was asked for — a shape the file
  rejects, a value the schema does not take. That is the model's to read
  and correct, like any refusal, and the worker that ran it is fine. Other
  failures are not the tool's: a turn token the backend refused, or the
  engine itself breaking. Those still fail the call, and the worker is
  replaced.")

(def ^:private not-the-tools
  "Failures that are about who asks or the engine, not about the change."
  #{:agent-backend-refused :invalid-turn-token :worker-exited})

(defn answer
  "The answer a tool that threw with `cause` gives the model, or nil when
  the failure is not the tool's."
  [tool cause]
  (let [{:keys [type code hint]} (ex-data cause)]
    (when (and (some? type) (not (contains? not-the-tools code)))
      {:content (str tool ": "
                     (or hint (ex-message cause) "the change could not be made")
                     (when (= :data-validation code)
                       " — the change would make the file invalid; check the values you passed"))
       :isError true})))
