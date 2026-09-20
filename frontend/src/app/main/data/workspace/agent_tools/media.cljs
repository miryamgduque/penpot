;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.media
  "External media tools: insert_image, Iconify icons and Google Fonts."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.types.container :as ctn]
   [app.common.types.fills :as types.fills]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-tools.common :as atc]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.svg-upload :as svg-up]
   [app.main.data.workspace.texts :as dwt-text]
   [app.main.data.workspace.undo :as dwu]
   [app.main.fonts :as fonts]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(defn insert-image-problem
  "Validation message for an insert_image call, nil when acceptable. Public for
  tests."
  [{:keys [url]}]
  (when-not (and (string? url) (re-matches atc/http-url-re url))
    (str "insert_image: url must be an absolute http(s) URL, e.g. "
         "https://picsum.photos/600/400.")))

(defn image-geometry
  "Final geometry for the image shape: intrinsic media size unless overridden;
  a single given dimension scales the other to keep the image's aspect ratio.
  Public for tests."
  [{:keys [x y width height]} media]
  (let [mw (:width media)
        mh (:height media)
        [w h] (cond
                (and width height) [width height]
                (some? width)      [width (js/Math.round (* mh (/ width mw)))]
                (some? height)     [(js/Math.round (* mw (/ height mh))) height]
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
  naming the fix where one is known. Public for tests."
  [code]
  (str "insert_image: "
       (or (get media-error-hints code)
           (str "the upload failed" (when code (str " (" (name code) ")"))))
       "."))

(defn insert-image
  [{:keys [url parentId] :as input}]
  (if-let [problem (insert-image-problem input)]
    (rx/throw (ex-info problem {}))
    (let [file-id (:current-file-id @st/state)
          nm      (or (:name input) "image")]
      (->> (rp/cmd! :create-file-media-object-from-url
                    {:name nm :file-id file-id :url url :is-local true})
           (rx/map
            (fn [media]
              (let [state    @st/state
                    page     (dsh/lookup-page state)
                    objects  (:objects page)
                    req-pid  (some-> parentId parse-uuid)
                    ;; same guards as create-shape: never inject into a
                    ;; component copy, and resolve a valid frame-id
                    pid      (when (and req-pid (contains? objects req-pid))
                               (:id (ctn/get-first-valid-parent objects req-pid)))
                    parent   (get objects pid)
                    parent?  (some? parent)
                    frame-id (when parent?
                               (if (cfh/frame-shape? parent) pid (:frame-id parent)))
                    geom     (image-geometry input media)
                    fills    (types.fills/create
                              {:fill-opacity 1
                               :fill-image {:width (:width media)
                                            :height (:height media)
                                            :mtype (:mtype media)
                                            :id (:id media)
                                            :keep-aspect-ratio true}})
                    shape    (cond-> (-> (cts/setup-shape
                                          (assoc geom :type :rect :name nm))
                                         (assoc :fills fills))
                               parent? (assoc :parent-id pid :frame-id frame-id))
                    changes  (-> (cb/empty-changes)
                                 (cb/with-page page)
                                 (cb/with-objects objects)
                                 (cb/add-object shape
                                                (when-let [idx (some-> parent atc/flow-append-index)]
                                                  {:index idx})))
                    undo-id  (js/Symbol)]
                (atc/interrupt!)
                (st/emit! (dwu/start-undo-transaction undo-id))
                (st/emit! (dch/commit-changes changes))
                (when parent?
                  (atc/reflow-parent! objects pid))
                (st/emit! (dwu/commit-undo-transaction undo-id))
                {:id (dm/str (:id shape))
                 :mediaId (dm/str (:id media))
                 :width (:width geom)
                 :height (:height geom)
                 :parentId (when parent? (dm/str pid))
                 :note "image inserted — verify geometry with read_design"})))
           (rx/catch
            (fn [cause]
              (rx/throw (ex-info (media-error-message (:code (ex-data cause)))
                                 {:cause-hint (ex-message cause)}))))))))

;; --- search_icons / insert_icon (Iconify)

(def ^:private iconify-base "https://api.iconify.design")

(def ^:private icon-id-re
  #"^[a-z0-9]+(?:-[a-z0-9]+)*:[a-z0-9]+(?:-[a-z0-9]+)*$")

(defn icon-id-problem
  "Validation message for an Iconify icon id, nil when acceptable. Public for
  tests."
  [icon]
  (when-not (and (string? icon) (re-matches icon-id-re icon))
    (str "insert_icon: icon must be an Iconify id in prefix:name form, e.g. "
         "lucide:house — find one with search_icons.")))

(defn icon-svg-url
  "The Iconify SVG endpoint for an already-validated icon id. Public for tests."
  [icon size]
  (let [[prefix nm] (str/split icon ":")]
    (str iconify-base "/" prefix "/" nm ".svg?height=" size)))

(defn icons-payload
  "Parses an Iconify search response body into the tool result. Caps the id
  list at `limit` — the API's own floor is 32, so it over-returns for smaller
  asks. Public for tests."
  [body limit]
  (let [data  (js/JSON.parse body)
        icons (vec (take limit (array-seq (.-icons ^js data))))]
    {:icons icons
     :total (.-total ^js data)
     :note  (if (seq icons)
              "insert with insert_icon {icon: \"prefix:name\"}"
              "no icons matched — try a broader, single-noun query")}))

(defn search-icons
  [{:keys [query limit]}]
  (if-not (and (string? query) (not (str/blank? query)))
    (rx/throw (ex-info "search_icons: query is required, e.g. {query: \"home\"}" {}))
    (let [limit (-> (or limit 24) (max 1) (min 64))]
      (->> (http/send! {:method :get
                        :uri (str iconify-base "/search?query="
                                  (js/encodeURIComponent query)
                                  ;; below the API's floor it ignores the param
                                  "&limit=" (max limit 32))
                        ;; Penpot's default x-frontend-version/x-client headers
                        ;; are not in Iconify's allowed-headers, so they trip a
                        ;; CORS preflight the API rejects — drop them
                        :omit-default-headers true})
           (rx/mapcat
            (fn [{:keys [status body]}]
              (if (= 200 status)
                (rx/of (icons-payload body limit))
                (rx/throw (ex-info (str "search_icons: the icon service answered "
                                        status " — try again shortly")
                                   {})))))))))

(defn insert-icon
  [{:keys [icon x y size] :as input}]
  (if-let [problem (icon-id-problem icon)]
    (rx/throw (ex-info problem {}))
    (let [size (-> (or size 24) (max 8) (min 512))
          nm   (or (:name input) icon)]
      (->> (http/send! {:method :get :uri (icon-svg-url icon size)
                        ;; see search-icons: drop the default headers to keep
                        ;; this a preflight-free simple CORS GET
                        :omit-default-headers true})
           (rx/mapcat
            (fn [{:keys [status body]}]
              (cond
                (not= 200 status)
                (rx/throw (ex-info (str "insert_icon: icon " icon " was not found "
                                        "— check the id with search_icons")
                                   {}))

                (not (dwm/valid-svg-string? body))
                (rx/throw (ex-info (str "insert_icon: the icon service did not "
                                        "return SVG — try another icon")
                                   {}))

                :else
                (dwm/svg->clj [nm body]))))
           (rx/map
            (fn [svg-data]
              (let [id  (uuid/next)
                    pos (gpt/point (or x 0) (or y 0))]
                (atc/interrupt!)
                ;; ignore-selection?: the agent's insert must not depend on
                ;; whatever the user happens to have selected
                (st/emit! (svg-up/add-svg-shapes id svg-data pos
                                                 {:change-selection? false
                                                  :ignore-selection? true}))
                {:id (dm/str id)
                 :icon icon
                 :size size
                 :note "icon inserted as vector shapes"})))))))

;; --- search_fonts / set_font

(defn font-search-results
  "Case-insensitive substring search over the fonts db by family name. Public
  for tests."
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
  map leaves the previous font's family or weight behind on the shape. Public
  for tests."
  [font variant]
  {:font-id (:id font)
   :font-family (:family font)
   :font-variant-id (:id variant)
   :font-weight (:weight variant)
   :font-style (:style variant)})

(defn variant-problem
  "Validation message when a requested variant does not exist on the font, nil
  when acceptable (or when no variant was requested). Public for tests."
  [font variant-id]
  (when (and (some? variant-id)
             (not (some #(= variant-id (:id %)) (:variants font))))
    (str "set_font: variant \"" variant-id "\" does not exist for "
         (:family font) " — available: "
         (str/join ", " (map :id (:variants font))) ".")))

(defn non-text-problem
  "Validation message when any id is unknown on this page or names a non-text
  shape, nil when all ids are text shapes. Public for tests."
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

(defn search-fonts
  [{:keys [query limit]}]
  (if-not (and (string? query) (not (str/blank? query)))
    (rx/throw (ex-info "search_fonts: query is required, e.g. {query: \"inter\"}" {}))
    (let [limit (-> (or limit 15) (max 1) (min 50))
          db    @fonts/fontsdb
          rows  (font-search-results db query limit)]
      (rx/of {:fonts rows
              :note (cond
                      (seq rows)
                      "apply with set_font {shapeIds, family (or fontId), variantId?}"

                      (not (some #(= :google (:backend %)) (vals db)))
                      (str "no match, and Google Fonts are disabled on this "
                           "instance — only built-in fonts are available")

                      :else
                      "no match — try a shorter substring of the family name")}))))

(defn set-font
  [{:keys [shapeIds family fontId variantId]}]
  (let [ids  (mapv #(some-> % parse-uuid) (or shapeIds []))
        font (cond
               (some? fontId) (fonts/get-font-data fontId)
               (some? family) (fonts/find-font-family family))]
    (cond
      (or (empty? ids) (some nil? ids))
      (rx/throw (ex-info "set_font: shapeIds must be a non-empty array of shape ids" {}))

      (and (nil? fontId) (nil? family))
      (rx/throw (ex-info "set_font: give a font `family` (or a `fontId`) — find one with search_fonts" {}))

      (nil? font)
      (rx/throw (ex-info (str "set_font: no font matches "
                              (or fontId (str "family \"" family "\""))
                              " — find the exact name with search_fonts")
                         {}))

      :else
      (let [objects (:objects (dsh/lookup-page @st/state))
            variant (if (some? variantId)
                      (fonts/get-variant font variantId)
                      (fonts/get-default-variant font))]
        (if-let [problem (or (non-text-problem objects ids)
                             (variant-problem font variantId))]
          (rx/throw (ex-info problem {}))
          (->> (rx/from (fonts/ensure-loaded! (:id font) (:id variant)))
               (rx/map
                (fn [_]
                  (st/emit! (dwt-text/update-all-attrs ids (font-attrs font variant)))
                  {:family (:family font)
                   :variant (:id variant)
                   :shapes (count ids)
                   :note "font applied — text re-measures asynchronously"}))
               (rx/catch
                (fn [_]
                  (rx/throw (ex-info (str "set_font: " (:family font)
                                          " could not be loaded from its provider "
                                          "— try another font")
                                     {}))))))))))
