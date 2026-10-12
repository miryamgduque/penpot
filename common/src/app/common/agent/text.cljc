;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.text
  "The design agent's text tools — create_text and set_text — as pure
  functions; see `app.common.agent.write` for what a tool takes and answers.

  A text sizes itself to its words, and only the render engine can say how
  big the words are. So besides `:propagate` these tools take `:measure-text`
  — `(fn [objects id])` → `{:width :height}` — and answer `{:fallback}` when
  it is not there."
  (:require
   [app.common.agent.write :as caw]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [cuerdas.core :as str]))

(def text-aligns #{"left" "center" "right" "justify"})

(def ^:private no-measure
  "text cannot be measured on the server without the render engine")

;; --- create_text

(defn create-text
  [{:keys [page-id objects]} {:keys [text x y parentId fill align] :as input}
   {:keys [propagate measure-text] :as engine}]
  (if-let [problem (or (when (or (not (string? text)) (empty? text))
                         "create_text: text must be a non-empty string")
                       (caw/enum-problem "create_text" "align" align text-aligns))]
    {:problem problem}
    (if (nil? measure-text)
      {:fallback no-measure}
      (let [nm       (:name input)
            req-pid  (some-> parentId parse-uuid)
            pid      (when (and req-pid (contains? objects req-pid))
                       (:id (ctn/get-first-valid-parent objects req-pid)))
            parent   (get objects pid)
            frame-id (when parent
                       (if (cfh/frame-shape? parent) pid (:frame-id parent)))
            shape    (-> (cts/setup-shape
                          (cond-> {:type :text
                                   :x (or x 0) :y (or y 0)
                                   :width 1 :height 1
                                   :grow-type :auto-width}
                            nm (assoc :name nm)))
                         (update :content txt/change-text text
                                 (cond-> {:fills [{:fill-color (or fill "#000000") :fill-opacity 1}]}
                                   align (assoc :text-align align)))
                         (dissoc :position-data)
                         (cond-> parent (assoc :parent-id pid :frame-id frame-id)))
            index    (some-> parent caw/flow-append-index)]
        {:changes (-> (caw/page-changes page-id objects)
                      (cb/add-object shape (when (some? index) {:index index}))
                      (cond-> (ctl/grid-layout? parent)
                        (caw/seat-in-grid pid))
                      (caw/fit-text engine (:id shape))
                      (cond-> (ctl/any-layout? parent)
                        (caw/reflow-all propagate [pid]))
                      (caw/redo))
         :result {:id (dm/str (:id shape))
                  :note "text created — size settles async; verify with read_design"}}))))

;; --- set_text

(defn text-problem
  "Why `set_text` cannot run, or nil."
  [objects id {:keys [text align]}]
  (let [shape (get objects id)]
    (cond
      (nil? id)
      "set_text: shapeId is required"

      (nil? shape)
      (dm/str "set_text: no shape on this page with id " (str id) " — check read_design")

      (not (cfh/text-shape? shape))
      (dm/str "set_text: " (caw/shape-label shape) " is a "
              (some-> (:type shape) name)
              ", not a text shape — note modify_shape's `name` renames the LAYER,"
              " it does not change the words; create text with create_text")

      (and (nil? text) (nil? align))
      "set_text: nothing to change — pass text (the words) and/or align"

      :else
      (caw/enum-problem "set_text" "align" align text-aligns))))

(defn- set-paragraph-attrs
  [shape attrs]
  (txt/update-text-content shape txt/is-paragraph-node?
                           (fn [node attrs] (reduce-kv assoc node attrs))
                           attrs))

(def ^:private max-texts 120)

(defn texts-into
  "Appends to `changes` what gives each text of `entries` — `[id words]` —
  its words, sized to them."
  [changes entries engine]
  (reduce (fn [changes [id text]]
            (-> changes
                (cb/update-shapes [id] #(update % :content txt/change-text text))
                (caw/fit-text engine id)))
          changes
          entries))

(defn set-texts
  "The words of many texts in ONE call. All-or-nothing: every entry is checked
  before any text changes."
  [{:keys [page-id objects]} {:keys [texts]} {:keys [measure-text] :as engine}]
  (let [entries  (when (sequential? texts)
                   (mapv (fn [{:keys [shapeId text]}] [(when (string? shapeId) (parse-uuid shapeId)) text])
                         (filter map? texts)))
        problems (into []
                       (keep-indexed
                        (fn [i [id text]]
                          (when-let [problem (or (text-problem objects id {:text text})
                                                 (when-not (string? text) "set_text: text is not text"))]
                            (dm/str "texts[" i "]: " (str/replace problem #"^set_text: " "")))))
                       entries)]
    (cond
      (or (empty? entries) (not= (count entries) (count texts)))
      {:problem "set_texts: pass texts — an array of {shapeId, text}"}

      (> (count entries) max-texts)
      {:problem (dm/str "set_texts: at most " max-texts " texts per call")}

      (seq problems)
      {:problem (dm/str "set_texts: " (str/join " | " problems))}

      (nil? measure-text)
      {:fallback no-measure}

      :else
      {:changes (-> (caw/page-changes page-id objects)
                    (texts-into entries engine)
                    (caw/redo))
       :result {:set (count entries)
                :note "texts updated — sizes settle async; verify with render_board"}})))

(defn set-text
  [{:keys [page-id objects]} {:keys [shapeId text align]} {:keys [measure-text] :as engine}]
  (let [id (some-> shapeId parse-uuid)]
    (if-let [problem (text-problem objects id {:text text :align align})]
      {:problem problem}
      (if (nil? measure-text)
        {:fallback no-measure}
        {:changes (-> (caw/page-changes page-id objects)
                      (cond-> (some? text)
                        (cb/update-shapes [id] #(update % :content txt/change-text text)))
                      (cond-> (some? align)
                        (cb/update-shapes [id] #(set-paragraph-attrs % {:text-align align})))
                      (caw/fit-text engine id)
                      (caw/redo))
         :result {:id shapeId
                  :note (str "text updated — size settles async. Font size, family and "
                             "weight are design tokens: author one with create_token "
                             "(fontSizes) and bind it with apply_tokens rather than "
                             "hardcoding. Verify with read_design.")}}))))
