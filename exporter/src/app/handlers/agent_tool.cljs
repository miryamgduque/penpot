;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.handlers.agent-tool
  "`POST /agent-tool`: runs one design agent tool that changes a page.

  Called by the agent service, not by a browser, and carries no session. What
  it is allowed to do is decided by the turn token in the request: the worker
  hands it to the backend, which answers only for the profile and file the
  token names."
  (:require
   [app.common.exceptions :as ex]
   [app.wasm.pool :as pool]
   [promesa.core :as p]))

(def ^:private tools #{"create_shape" "modify_shape"})

(defn handler
  [{:keys [:request/params] :as exchange}]
  (let [{:keys [token tool input context]} params]
    (when-not (and (string? token) (contains? tools tool))
      (ex/raise :type :validation
                :code :invalid-agent-tool-request
                :hint "expected a turn token and a tool this service runs"))
    (->> (if (zero? (pool/capacity))
           ;; no engine here: the caller runs the tool where it used to
           (p/resolved {:fallback "the render engine is not enabled on this exporter"})
           (pool/run-tool {:token token
                           :tool tool
                           :input (if (string? input) input "{}")
                           :context (if (string? context) context "{}")}))
         (p/fmap (fn [result]
                   (-> exchange
                       (assoc :response/status 200)
                       (assoc :response/headers {"content-type" "application/json"})
                       (assoc :response/body (js/JSON.stringify (clj->js result)))))))))
