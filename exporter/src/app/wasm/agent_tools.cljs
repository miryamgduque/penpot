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
   [app.common.agent.design-md :as dmd]
   [app.common.agent.read :as car]
   [app.common.agent.tools :as cat]
   [app.common.agent.write :as caw]
   [app.common.data :as d]
   [app.common.fonts :as cfnt]
   [app.common.tokens.resolver :as ctr]
   [app.common.types.shape.images :as images]
   [app.config :as cf]
   [app.wasm :as wasm]
   [app.wasm.backend :as backend]
   [app.wasm.layout :as layout]
   [app.wasm.render :as render]
   [app.wasm.serialize :as serialize]
   [app.wasm.svg-parse :as svg-parse]
   [cuerdas.core :as str]
   [promesa.core :as p]))

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
    :image (backend/command! "create-agent-tool-media"
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

(defn- font-files
  "How the engine gets a team's uploaded fonts with no session: the team's
  variants, and the backend command that hands a font file over for the turn."
  [token team-fonts]
  {:variants team-fonts
   :fetch-asset (fn [id]
                  (->> (backend/command! "get-agent-tool-font" {:token token :id id})
                       (p/fmap (fn [{:keys [data]}]
                                 (when (string? data)
                                   (.-buffer (js/Uint8Array. (js/Buffer.from data "base64"))))))
                       (p/merr (fn [_] (p/resolved nil)))))})

(defn- design-doc-problem
  "Why a document that opens with DESIGN.md frontmatter cannot be stored, or
  nil."
  [doc]
  (let [{:keys [error] :as parsed} (dmd/parse doc)]
    (or error
        (some->> (dmd/problems parsed) (str/join "; ")))))

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
  "The render engine as the shared tools take it, plus the pages it was asked
  to measure text on."
  []
  (let [measured (volatile! [])
        load!    (fn [objects] (layout/load-page! objects))]
    {:measured measured
     :parse-svg svg-parse/parse
     :propagate (fn [objects entries]
                  (load! objects)
                  (layout/propagate entries))
     :measure-text (fn [objects id]
                     (vswap! measured conj [id objects])
                     (load! objects)
                     (layout/measure-text id))}))

(defn- measured-scenes
  "The pages whose text was measured, each as it stood the last time: as few
  scenes as hold every measured text."
  [measured]
  (first
   (reduce (fn [[scenes covered] [id objects]]
             (if (contains? covered id)
               [scenes covered]
               [(conj scenes objects)
                (into covered (comp (filter #(= :text (:type %))) (map :id)) (vals objects))]))
           [[] #{}]
           (reverse measured))))

;; A tool is a plain function, and two things it may need are not there when
;; it first runs: what the file's tokens are worth, which the resolver library
;; answers asynchronously, and the fonts of the text it measures, which have
;; to be downloaded. So a tool is run until it has both: it says which tokens
;; it needs resolved, or measures with whatever fonts are loaded, and is run
;; again once they are in.
(def ^:private max-runs 5)

(defn- asked
  "What tells one question about tokens from another: their names, types
  and values. A tool that writes a token writes it anew on every run, with
  the time of that run on it, and is still asking the same thing."
  [tokens]
  (update-vals tokens #(select-keys % [:name :type :value])))

(defn- answer-of
  "Runs `tool` → promise of its answer."
  [tool view input options]
  (let [resolved (volatile! {})]
    (letfn [(attempt [runs fonts?]
              (let [engine (make-engine)
                    answer (cat/run tool view input
                                    (merge options
                                           (select-keys engine [:propagate :measure-text :parse-svg])
                                           {:resolve-tokens (fn [tokens] (get @resolved (asked tokens)))}))]
                (cond
                  (>= runs max-runs)
                  (p/resolved {:fallback "the tool did not settle on the server"})

                  (some? (:pending answer))
                  (let [tokens (-> answer :pending :tokens)]
                    (->> (ctr/resolve-tokens tokens)
                         (p/mcat (fn [worth]
                                   (vswap! resolved assoc (asked tokens) worth)
                                   (attempt (inc runs) fonts?)))))

                  (and (not fonts?) (seq @(:measured engine)))
                  (->> (render/provision-scenes-fonts! (measured-scenes @(:measured engine)) layout/load-page! (:font-files options))
                       (p/mcat (fn [found?]
                                 (if found?
                                   (attempt (inc runs) true)
                                   (p/resolved {:fallback "a font this text uses is not available to the server"})))))

                  :else
                  (p/resolved answer))))]
      (attempt 0 false))))

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
              (->> (backend/command! "get-agent-tool-media" {:token token :id id})
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
                 (backend/command! "get-agent-tool-page" {:token token :context context})])
         (p/mcat
          (fn [[_ {:keys [data page-id team-fonts]}]]
            (let [objects (get-in data [:pages-index page-id :objects])
                  targets (car/render-targets objects (:names input) (:selectedIds ctx))]
              (if (empty? targets)
                (p/resolved {:content car/no-render-targets :isError true})
                (do
                  (serialize/serialize-scene! objects)
                  (->> (provision-images! token objects)
                       (p/mcat (fn [_] (render/provision-scene-fonts! objects (font-files token team-fonts))))
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
                 (backend/command! "get-agent-tool-page" {:token token :context context})
                 (resolve-needs token tool input)])
         (p/mcat
          (fn [[_ {:keys [file-id page-id data libraries revn vern allowed-colors team-fonts enforced-rules
                          recipes]}
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
              (->> (answer-of tool view input {:guard guard
                                               :recipes recipes
                                               :resources resources
                                               :font-files (font-files token team-fonts)
                                               :check-design-doc design-doc-problem})
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
                               (backend/command! "commit-agent-tool-changes"
                                                 (cond-> {:token token :revn revn :vern vern :tool tool :changes (:changes answer)}
                                                   ;; what takes the changes back, so the turn can be reverted
                                                   (some? (caw/undo-of (:changes answer)))
                                                   (assoc :undo-changes (caw/undo-of (:changes answer)))))
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
