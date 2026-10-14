;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.media
  "The design agent's media tools — images, Iconify icons and fonts — as pure
  functions; see `app.common.agent.write` for what a tool takes and answers.

  These tools use things from outside the file: an image has to be uploaded,
  an icon downloaded. A pure function cannot do that, so each such tool says
  what it needs first (`*-needs` → requests), the host fetches it, and the
  tool finds the answers under `:resources` in its options, keyed by URL:

  - `{:kind :image :url :name}` → the stored media `{:id :width :height
    :mtype}`, or `{:error code}`
  - `{:kind :http :url}` → `{:status :body}`

  The font tools read `:fonts` from the view: font id → font, as the workspace
  registers them (`:id :family :backend :variants`)."
  (:require
   [app.common.agent.structure :as cas]
   [app.common.agent.write :as caw]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.json :as json]
   [app.common.math :as mth]
   [app.common.types.container :as ctn]
   [app.common.types.fills :as types.fills]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [cuerdas.core :as str]))

(def http-url-re #"(?i)^https?://\S+$")

(def ^:private no-resources
  "what this tool needs from outside the file was not fetched")

;; --- insert_image

(defn insert-image-problem
  "Validation message for an insert_image call, nil when acceptable."
  [{:keys [url]}]
  (when-not (and (string? url) (re-matches http-url-re url))
    (str "insert_image: url must be an absolute http(s) URL, e.g. "
         "https://picsum.photos/600/400.")))

(defn image-geometry
  "Final geometry for the image shape: intrinsic media size unless overridden;
  a single given dimension scales the other to keep the image's aspect ratio."
  [{:keys [x y width height]} media]
  (let [mw (:width media)
        mh (:height media)
        [w h] (cond
                (and width height) [width height]
                (some? width)      [width (mth/round (* mh (/ width mw)))]
                (some? height)     [(mth/round (* mw (/ height mh))) height]
                :else              [mw mh])]
    {:x (or x 0) :y (or y 0) :width w :height h}))

(def ^:private media-error-hints
  {:ssrf-blocked-target
   "the host is private or blocked by this Penpot instance; use a public image URL"
   :unknown-size
   (str "the server sent no content-length header, which Penpot requires; "
        "picsum.photos, placehold.co and api.dicebear.com all send it")
   :media-type-not-allowed
   (str "the URL did not return a supported image type; for placehold.co "
        "request an explicit format, e.g. .../600x400.png")
   :media-max-file-size-reached
   "the image exceeds this instance's media size limit; request smaller dimensions"
   :unable-to-download-image
   "the image could not be downloaded (unreachable host, timeout, or error status)"})

(defn media-error-message
  "One-line agent-facing message for a media download/processing failure,
  naming the fix where one is known."
  [code]
  (str "insert_image: "
       (or (get media-error-hints code)
           (str "the upload failed" (when code (str " (" (name code) ")"))))
       "."))

(defn image-fill
  "The fills of a shape that shows the stored image `media`."
  [media]
  (types.fills/create
   {:fill-opacity 1
    :fill-image {:width (:width media)
                 :height (:height media)
                 :mtype (:mtype media)
                 :id (:id media)
                 :keep-aspect-ratio true}}))

(defn insert-image-needs
  [{:keys [url] :as input}]
  (when (nil? (insert-image-problem input))
    [{:kind :image :url url :name (or (:name input) "image")}]))

(defn insert-image
  [{:keys [page-id objects]} {:keys [url parentId] :as input} {:keys [propagate resources]}]
  (let [media (get resources url)]
    (cond
      (some? (insert-image-problem input))
      {:problem (insert-image-problem input)}

      (nil? media)
      {:fallback no-resources}

      (contains? media :error)
      {:problem (media-error-message (:error media))}

      :else
      (let [nm       (or (:name input) "image")
            req-pid  (some-> parentId parse-uuid)
            ;; same guards as create_shape: never inject into a component
            ;; copy, and resolve a valid frame-id
            pid      (when (and req-pid (contains? objects req-pid))
                       (:id (ctn/get-first-valid-parent objects req-pid)))
            parent   (get objects pid)
            frame-id (when parent
                       (if (cfh/frame-shape? parent) pid (:frame-id parent)))
            geom     (image-geometry input media)
            shape    (cond-> (-> (cts/setup-shape (assoc geom :type :rect :name nm))
                                 (assoc :fills (image-fill media)))
                       parent (assoc :parent-id pid :frame-id frame-id))
            index    (some-> parent caw/flow-append-index)]
        {:changes (-> (caw/page-changes page-id objects)
                      (cb/add-object shape (when (some? index) {:index index}))
                      (cond-> (ctl/grid-layout? parent)
                        (caw/seat-in-grid pid))
                      (cond-> (ctl/any-layout? parent)
                        (caw/reflow-all propagate [pid]))
                      (caw/redo))
         :result {:id (dm/str (:id shape))
                  :mediaId (dm/str (:id media))
                  :width (:width geom)
                  :height (:height geom)
                  :parentId (when parent (dm/str pid))
                  :note "image inserted — verify geometry with read_design"}}))))

;; --- search_icons / insert_icon (Iconify)

(def ^:private iconify-base "https://api.iconify.design")

(def ^:private icon-id-re
  #"^[a-z0-9]+(?:-[a-z0-9]+)*:[a-z0-9]+(?:-[a-z0-9]+)*$")

(defn icon-id-problem
  "Validation message for an Iconify icon id, nil when acceptable."
  [icon]
  (when-not (and (string? icon) (re-matches icon-id-re icon))
    (str "insert_icon: icon must be an Iconify id in prefix:name form, e.g. "
         "lucide:house — find one with search_icons.")))

(defn icon-size
  [size]
  (-> (or size 24) (max 8) (min 512)))

(defn icon-svg-url
  "The Iconify SVG endpoint for an already-validated icon id."
  [icon size]
  (let [[prefix nm] (str/split icon ":")]
    (str iconify-base "/" prefix "/" nm ".svg?height=" size)))

(defn icons-limit
  [limit]
  (-> (or limit 24) (max 1) (min 64)))

(defn- encode-query
  [s]
  #?(:cljs (js/encodeURIComponent s)
     :clj  (-> (java.net.URLEncoder/encode ^String s "UTF-8")
               (str/replace "+" "%20"))))

(defn icons-search-url
  [query limit]
  (str iconify-base "/search?query=" (encode-query query)
       ;; below the API's floor it ignores the param
       "&limit=" (max limit 32)))

(defn icons-result
  "The tool result for a parsed Iconify search response. Caps the id list at
  `limit` — the API's own floor is 32, so it over-returns for smaller asks."
  [{:keys [icons total]} limit]
  (let [icons (vec (take limit icons))]
    {:icons icons
     :total total
     :note  (if (seq icons)
              "insert with insert_icon {icon: \"prefix:name\"}"
              "no icons matched — try a broader, single-noun query")}))

(defn- search-icons-problem
  [{:keys [query]}]
  (when-not (and (string? query) (not (str/blank? query)))
    "search_icons: query is required, e.g. {query: \"home\"}"))

(defn search-icons-needs
  [{:keys [query limit] :as input}]
  (when (nil? (search-icons-problem input))
    [{:kind :http :url (icons-search-url query (icons-limit limit))}]))

(defn search-icons
  [_ {:keys [query limit] :as input} {:keys [resources]}]
  (if-let [problem (search-icons-problem input)]
    {:problem problem}
    (let [limit (icons-limit limit)
          {:keys [status body] :as answer} (get resources (icons-search-url query limit))
          data  (when (= 200 status)
                  (try (json/decode body :key-fn keyword)
                       (catch #?(:clj Throwable :cljs :default) _ nil)))]
      (cond
        (nil? answer)
        {:fallback no-resources}

        (not (map? data))
        {:problem (str "search_icons: the icon service answered " status
                       " — try again shortly")}

        :else
        {:result (icons-result data limit)}))))

(defn insert-icon-needs
  [{:keys [icon size]}]
  (when (nil? (icon-id-problem icon))
    [{:kind :http :url (icon-svg-url icon (icon-size size))}]))

(defn insert-icon
  [view {:keys [icon x y size] :as input} {:keys [resources parse-svg] :as engine}]
  (if-let [problem (icon-id-problem icon)]
    {:problem problem}
    (let [size (icon-size size)
          {:keys [status body] :as answer} (get resources (icon-svg-url icon size))]
      (cond
        (or (nil? answer) (nil? parse-svg))
        {:fallback no-resources}

        (not= 200 status)
        {:problem (str "insert_icon: icon " icon " was not found "
                       "— check the id with search_icons")}

        :else
        (if-let [svg-data (when (string? body) (parse-svg body))]
          (let [{:keys [id changes]}
                (cas/import-svg view svg-data (or (:name input) icon) x y engine)]
            {:changes (caw/redo changes)
             :result {:id (dm/str id)
                      :icon icon
                      :size size
                      :note "icon inserted as vector shapes"}})
          {:problem (str "insert_icon: the icon service did not "
                         "return SVG — try another icon")})))))

;; --- search_fonts / set_font

(defn font-search-results
  "Case-insensitive substring search over the fonts db by family name."
  [db query limit]
  (let [q (str/lower query)]
    (->> (vals db)
         (filter #(str/includes? (str/lower (:family %)) q))
         (sort-by :family)
         (take limit)
         (mapv (fn [{:keys [id family backend variants]}]
                 {:id id
                  :family family
                  :backend (name (or backend :unknown))
                  :variants (mapv :id variants)})))))

(defn font-attrs
  "The full text attr map for a font+variant. All five keys always — a partial
  map leaves the previous font's family or weight behind on the shape."
  [font variant]
  {:font-id (:id font)
   :font-family (:family font)
   :font-variant-id (:id variant)
   :font-weight (:weight variant)
   :font-style (:style variant)})

(defn variant-problem
  "Validation message when a requested variant does not exist on the font, nil
  when acceptable (or when no variant was requested)."
  [font variant-id]
  (when (and (some? variant-id)
             (not (some #(= variant-id (:id %)) (:variants font))))
    (str "set_font: variant \"" variant-id "\" does not exist for "
         (:family font) " — available: "
         (str/join ", " (map :id (:variants font))) ".")))

(defn non-text-problem
  "Validation message when any id is unknown on this page or names a non-text
  shape, nil when all ids are text shapes."
  [objects ids]
  (let [missing  (remove #(contains? objects %) ids)
        non-text (filter #(some->> (get-in objects [% :type]) (not= :text)) ids)]
    (cond
      (seq missing)
      (str "set_font: shape id(s) "
           (str/join ", " (map str missing))
           " do not exist on this page — check with find_shapes.")

      (seq non-text)
      (let [names (map #(or (get-in objects [% :name]) (str %)) non-text)]
        (str "set_font: " (str/join ", " names)
             (if (= 1 (count names)) " is not a text shape" " are not text shapes")
             " — set_font only applies to text.")))))

(defn find-font-family
  "Case insensitive lookup of a font by family."
  [db family]
  (let [family (str/lower family)]
    (d/seek #(= family (str/lower (:family %))) (vals db))))

(defn default-variant
  [{:keys [variants]}]
  (or (d/seek #(or (= (:id %) "regular") (= (:name %) "regular")) variants)
      (first variants)))

(defn font-variant
  [font variant-id]
  (or (d/seek #(= (:id %) variant-id) (:variants font))
      (default-variant font)))

(defn search-fonts
  [{:keys [fonts]} {:keys [query limit]} _]
  (cond
    (not (and (string? query) (not (str/blank? query))))
    {:problem "search_fonts: query is required, e.g. {query: \"inter\"}"}

    (nil? fonts)
    {:fallback "the font list is not available on the server here"}

    :else
    (let [limit (-> (or limit 15) (max 1) (min 50))
          rows  (font-search-results fonts query limit)]
      {:result {:fonts rows
                :note (cond
                        (seq rows)
                        "apply with set_font {shapeIds, family (or fontId), variantId?}"

                        (not (some #(= :google (:backend %)) (vals fonts)))
                        (str "no match, and Google Fonts are disabled on this "
                             "instance — only built-in fonts are available")

                        :else
                        "no match — try a shorter substring of the family name")}})))

(defn- text-node?
  [node]
  (or (txt/is-text-node? node) (txt/is-paragraph-node? node)))

(defn set-font-into
  "Appends to `changes` what gives text `id` the font `attrs` and sizes it to
  its words again."
  [changes id attrs engine]
  (-> changes
      (cb/update-shapes [id] #(txt/update-text-content % text-node? d/txt-merge attrs))
      (caw/fit-text engine id)))

(defn set-font-problem
  "Why a font request cannot be met → `{:problem}`, `{:fallback}` or nil."
  [fonts {:keys [family fontId]}]
  (let [font (when (some? fonts)
               (cond
                 (some? fontId) (get fonts fontId)
                 (some? family) (find-font-family fonts family)))]
    (cond
      (and (nil? fontId) (nil? family))
      {:problem "set_font: give a font `family` (or a `fontId`) — find one with search_fonts"}

      (nil? fonts)
      {:fallback "the font list is not available on the server here"}

      (nil? font)
      {:problem (str "set_font: no font matches "
                     (or fontId (str "family \"" family "\""))
                     " — find the exact name with search_fonts")})))

(defn set-font
  [{:keys [page-id objects fonts]} {:keys [shapeIds family fontId variantId] :as input}
   {:keys [measure-text] :as engine}]
  (let [ids (mapv #(when (string? %) (parse-uuid %)) (or shapeIds []))]
    (if (or (empty? ids) (some nil? ids))
      {:problem "set_font: shapeIds must be a non-empty array of shape ids"}
      (or (set-font-problem fonts input)
          (let [font    (if (some? fontId) (get fonts fontId) (find-font-family fonts family))
                variant (font-variant font variantId)]
            (if-let [problem (or (non-text-problem objects ids)
                                 (variant-problem font variantId))]
              {:problem problem}
              (if (nil? measure-text)
                {:fallback "text cannot be measured on the server without the render engine"}
                {:changes (caw/redo
                           (reduce #(set-font-into %1 %2 (font-attrs font variant) engine)
                                   (caw/page-changes page-id objects)
                                   ids))
                 :result {:family (:family font)
                          :variant (:id variant)
                          :shapes (count ids)
                          :note "font applied — text re-measures asynchronously"}})))))))
