;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.agent-tools
  "Worker side of the design agent's page-changing tools.

  One call: read the page from the backend, run the shared tool on it with
  this worker's render-wasm module as the layout engine, commit the changes.
  The tool logic is `app.common.agent.write`, the same code the workspace
  uses; what this namespace adds is the engine and the two backend calls.

  The turn token is the only credential: the backend answers both calls for
  the profile and the file it names, and for nothing else."
  (:require
   ["undici" :as http]
   [app.common.agent.tools :as cat]
   [app.common.agent.write :as caw]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.wasm.layout :as layout]
   [app.wasm.render :as render]
   [promesa.core :as p]))

(defn- command!
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

(defn- parse-json
  [s]
  (try
    (let [v (js->clj (js/JSON.parse s) :keywordize-keys true)]
      (if (map? v) v {}))
    (catch :default _ {})))

(defn- engine
  "The layout engine as the shared tools take it: lay `objects` out and say
  what `entries` move."
  [objects entries]
  (layout/load-page! objects)
  (layout/propagate entries))

(defn run
  "→ promise of `{:content json :isError bool}`, or `{:fallback why}` when the
  call is one this side does not handle yet."
  [{:keys [token tool input context]}]
  (let [ctx (parse-json context)]
    (->> (p/all [(render/ensure-module!)
                 (command! "get-agent-tool-page" {:token token :context context})])
         (p/mcat
          (fn [[_ {:keys [file-id page-id data libraries revn vern allowed-colors]}]]
            (let [guard  {:enforced? (boolean (some #{caw/color-rule} (:enforcedRules ctx)))
                          :allowed allowed-colors}
                  run    (get cat/tools tool)
                  answer (run {:file-id file-id
                               :data data
                               :libraries (assoc libraries file-id {:id file-id :data data})
                               :page-id page-id
                               :objects (get-in data [:pages-index page-id :objects])}
                              (parse-json input)
                              {:guard guard :propagate engine})]
              (cond
                (some? (:fallback answer))
                (p/resolved {:fallback (:fallback answer)})

                (some? (:problem answer))
                (p/resolved {:content (:problem answer)
                             :isError true
                             :rule (:rule answer)})

                :else
                (->> (if (seq (:changes answer))
                       (command! "commit-agent-tool-changes"
                                 {:token token :revn revn :vern vern :changes (:changes answer)})
                       (p/resolved nil))
                     (p/fmap (fn [committed]
                               (cond-> {:content (js/JSON.stringify (clj->js (:result answer)))
                                        :isError false}
                                 ;; lets a workspace know when it has caught up
                                 (some? (:revn committed))
                                 (assoc :revn (:revn committed)))))))))))))
