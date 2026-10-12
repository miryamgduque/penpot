;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.backend
  "Calls to the backend made for an agent turn. They carry a turn token and
  no session: the backend answers for the profile and the file the token
  names, and for nothing else."
  (:require
   ["undici" :as http]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cf]
   [promesa.core :as p]))

(defn command!
  "Calls a backend command with transit in and out. A refusal is raised with
  the backend's own hint."
  [name params]
  (let [uri (-> (cf/get-internal-uri)
                (u/ensure-path-slash)
                (u/join (str "api/rpc/command/" name))
                (str))]
    (->> (p/do (http/fetch uri #js {:method "POST"
                                    :headers #js {"content-type" "application/transit+json"
                                                  "accept" "application/transit+json"}
                                    :body (t/encode-str params)}))
         (p/mcat (fn [^js response]
                   (->> (.text response)
                        (p/fmap (fn [body]
                                  (let [data (when (seq body) (t/decode-str body))]
                                    (if (= 200 (.-status response))
                                      data
                                      (throw (ex-info (or (:hint data) (str name " failed"))
                                                      {:code :agent-backend-refused
                                                       :status (.-status response)
                                                       :backend data}))))))))))))

