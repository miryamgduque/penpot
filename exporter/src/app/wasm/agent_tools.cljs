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
   [app.common.agent.read :as car]
   [app.common.agent.tools :as cat]
   [app.common.agent.write :as caw]
   [app.common.data :as d]
   [app.common.fonts :as cfnt]
   [app.common.transit :as t]
   [app.common.types.shape.images :as images]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.wasm :as wasm]
   [app.wasm.layout :as layout]
   [app.wasm.render :as render]
   [app.wasm.serialize :as serialize]
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
  (let [run    (partial cat/run tool)
        engine (make-engine)
        first  (run view input (merge options (select-keys engine [:propagate :measure-text :parse-svg])))]
    (if-not @(:measured? engine)
      (p/resolved first)
      (->> (render/provision-scene-fonts! @(:scene engine))
           (p/fmap (fn [found?]
                     (if found?
                       (run view input (merge options (select-keys (make-engine) [:propagate :measure-text :parse-svg])))
                       {:fallback "a font this text uses is not available to the server"})))))))

;; --- render_board
;;
;; The one tool that returns pixels. A connected workspace renders with its
;; own engine; this is the same engine, headless, for a turn with no workspace.
;; What the turn token cannot reach is drawn without: a team's uploaded fonts
;; fall back to a stock one.

(defn- provision-images!
  "Loads into the engine the images the scene's shapes use."
  [token scene]
  (->> (remove wasm/image-cached? (images/scene-image-ids scene))
       (map (fn [id]
              (->> (command! "get-agent-tool-media" {:token token :id id})
                   (p/fmap (fn [{:keys [data]}]
                             (when (string? data)
                               (wasm/store-image! id (js/Buffer.from data "base64")))))
                   ;; a missing image leaves a blank fill, not a failed render
                   (p/merr (fn [_] (p/resolved nil))))))
       (p/all)))

(defn- base64
  [bytes]
  (.toString (js/Buffer.from bytes) "base64"))

(defn- render-target
  "One shape → its attachable image, or why not."
  [{:keys [id name error] :as target} scale]
  (if (some? error)
    target
    (try
      (let [png (base64 (wasm/render-shape-raster id scale :png))]
        (if (<= (count png) car/jpeg-threshold-b64-chars)
          {:name name :id (str id) :mediaType "image/png" :data png}
          (let [jpeg (base64 (wasm/render-shape-raster id scale :jpeg))
                [mtype data] (if (< (count jpeg) (count png))
                               ["image/jpeg" jpeg]
                               ["image/png" png])]
            (if (> (count data) car/max-render-b64-chars)
              {:name name :error car/render-too-large}
              {:name name :id (str id) :mediaType mtype :data data}))))
      (catch :default cause
        {:name name :error (or (ex-message cause) "render failed")}))))

(defn- render-board
  [{:keys [token input context]}]
  (let [ctx   (parse-json context)
        input (parse-json input)
        scale (car/render-scale (:scale input))]
    (->> (p/all [(render/ensure-module!)
                 (command! "get-agent-tool-page" {:token token :context context})])
         (p/mcat
          (fn [[_ {:keys [data page-id]}]]
            (let [objects (get-in data [:pages-index page-id :objects])
                  targets (car/render-targets objects (:names input) (:selectedIds ctx))]
              (if (empty? targets)
                (p/resolved {:content car/no-render-targets :isError true})
                (do
                  (serialize/serialize-scene! objects)
                  (->> (provision-images! token objects)
                       (p/mcat (fn [_] (render/provision-scene-fonts! objects)))
                       (p/fmap
                        (fn [_]
                          (let [results (mapv #(render-target % scale) targets)
                                good    (filterv :data results)
                                failed  (filterv :error results)]
                            (if (empty? good)
                              {:content (car/render-failure failed) :isError true}
                              {:content (js/JSON.stringify
                                         (clj->js (cond-> {:rendered (mapv #(select-keys % [:name :id]) good)
                                                           :scale scale}
                                                    (seq failed)
                                                    (assoc :failed (mapv #(select-keys % [:name :error]) failed)))))
                               :isError false
                               :images (mapv #(select-keys % [:mediaType :data]) good)})))))))))))))

(defn- run-page-tool
  "→ promise of `{:content json :isError bool}`, or `{:fallback why}` when the
  call is one this side does not handle yet."
  [{:keys [token tool input context]}]
  (let [ctx   (parse-json context)
        input (parse-json input)]
    (->> (p/all [(render/ensure-module!)
                 (command! "get-agent-tool-page" {:token token :context context})
                 (resolve-needs token tool input)])
         (p/mcat
          (fn [[_ {:keys [file-id page-id data libraries revn vern allowed-colors team-fonts enforced-rules]}
                resources]]
            (layout/install-bool-engine!)
            (let [;; which rules are active is the backend's to say: the
                  ;; caller cannot switch a mandatory one off
                  guard  {:enforced? (boolean (some #{caw/color-rule} enforced-rules))
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
                                         {:token token :revn revn :vern vern :tool tool :changes (:changes answer)})
                               (p/resolved nil))
                             (p/fmap (fn [committed]
                                       (cond-> {:content (js/JSON.stringify (clj->js (:result answer)))
                                                :isError false}
                                         ;; lets a workspace know when it has caught up
                                         (some? (:revn committed))
                                         (assoc :revn (:revn committed))))))))))))))))

(defn run
  "Runs one tool for the turn `token` names → promise of its answer."
  [{:keys [tool] :as call}]
  (if (= "render_board" tool)
    (render-board call)
    (run-page-tool call)))
