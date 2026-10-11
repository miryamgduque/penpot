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
   [app.common.data :as d]
   [app.common.fonts :as cfnt]
   [app.common.transit :as t]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.wasm.layout :as layout]
   [app.wasm.render :as render]
   [app.wasm.svg-parse :as svg-parse]
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

;; --- What a tool needs from outside the file

(def ^:private fetch-timeout 15000)

(defn- fetch-text
  "→ promise of `{:status :body}`; a request that fails answers status 0."
  [url]
  (->> (p/do (http/fetch url #js {:method "GET"
                                  :signal (js/AbortSignal.timeout fetch-timeout)}))
       (p/mcat (fn [^js response]
                 (->> (.text response)
                      (p/fmap (fn [body] {:status (.-status response) :body body})))))
       (p/merr (fn [_] (p/resolved {:status 0 :body ""})))))

(defn- resolve-need
  [token {:keys [kind url name]}]
  (case kind
    :image (command! "create-agent-tool-media"
                     (cond-> {:token token :url url}
                       (some? name) (assoc :name name)))
    :http  (fetch-text url)))

(defn- resolve-needs
  "→ promise of the `:resources` a tool reads: URL → what was fetched."
  [token tool input]
  (let [requests (into [] (d/distinct-xf :url)
                       (when-let [needs-of (get cat/needs tool)]
                         (needs-of input)))]
    (->> (p/all (map #(resolve-need token %) requests))
         (p/fmap (fn [answers]
                   (zipmap (map :url requests) answers))))))

;; --- Fonts

(def ^:private stock-fonts
  "The fonts every file can use, as the workspace registers them."
  (delay
    (-> (d/index-by :id (map #(assoc % :backend :builtin) cfnt/local-fonts))
        (cond-> (contains? cf/instance-flags :google-fonts-provider)
          (into (map (fn [font] [(:id font) (assoc font :backend :google)])) cfnt/catalog)))))

(defn- fonts-db
  "Font id → font, with the team's own uploads on top of the stock ones."
  [team-fonts]
  (->> (group-by :font-id team-fonts)
       (reduce-kv (fn [db font-id variants]
                    (let [id (str "custom-" font-id)]
                      (assoc db id
                             {:id id
                              :name (:font-family (first variants))
                              :family (:font-family (first variants))
                              :backend :custom
                              :variants (->> variants
                                             (map (fn [{:keys [font-style font-weight]}]
                                                    {:id (str font-style "-" font-weight)
                                                     :style font-style
                                                     :weight (str font-weight)}))
                                             (sort-by (juxt :weight #(if (= "normal" (:style %)) 1 2)))
                                             (vec))})))
                  @stock-fonts)))

(defn- make-engine
  "The render engine as the shared tools take it, plus what it was last shown
  and whether it was asked to measure text."
  []
  (let [scene     (volatile! nil)
        measured? (volatile! false)
        load!     (fn [objects]
                    (vreset! scene objects)
                    (layout/load-page! objects))]
    {:scene scene
     :measured? measured?
     :parse-svg svg-parse/parse
     :propagate (fn [objects entries]
                  (load! objects)
                  (layout/propagate entries))
     :measure-text (fn [objects id]
                     (vreset! measured? true)
                     (load! objects)
                     (layout/measure-text id))}))

(defn- answer-of
  "Runs `tool` → promise of its answer. A tool that measures text is run
  twice: the first run shows the engine the text it will measure, the fonts
  that text uses are then loaded, and the second run measures with them."
  [tool view input options]
  (let [run    (get cat/tools tool)
        engine (make-engine)
        first  (run view input (merge options (select-keys engine [:propagate :measure-text :parse-svg])))]
    (if-not @(:measured? engine)
      (p/resolved first)
      (->> (render/provision-scene-fonts! @(:scene engine))
           (p/fmap (fn [found?]
                     (if found?
                       (run view input (merge options (select-keys (make-engine) [:propagate :measure-text :parse-svg])))
                       {:fallback "a font this text uses is not available to the server"})))))))

(defn run
  "→ promise of `{:content json :isError bool}`, or `{:fallback why}` when the
  call is one this side does not handle yet."
  [{:keys [token tool input context]}]
  (let [ctx   (parse-json context)
        input (parse-json input)]
    (->> (p/all [(render/ensure-module!)
                 (command! "get-agent-tool-page" {:token token :context context})
                 (resolve-needs token tool input)])
         (p/mcat
          (fn [[_ {:keys [file-id page-id data libraries revn vern allowed-colors team-fonts]} resources]]
            (layout/install-bool-engine!)
            (let [guard  {:enforced? (boolean (some #{caw/color-rule} (:enforcedRules ctx)))
                          :allowed allowed-colors}
                  view   {:file-id file-id
                          :data data
                          :libraries (assoc libraries file-id {:id file-id :data data})
                          :page-id page-id
                          :objects (get-in data [:pages-index page-id :objects])
                          :fonts (fonts-db team-fonts)
                          ;; what the user has selected, when a workspace said
                          :selected (into [] (keep #(when (string? %) (parse-uuid %))) (:selectedIds ctx))}]
              (->> (answer-of tool view input {:guard guard :resources resources})
                   (p/mcat
                    (fn [answer]
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
                                         (assoc :revn (:revn committed))))))))))))))))
