;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.read
  "Read tools: read_design, find_shapes and render_board."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.variant :as cfv]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-skills :as ask]
   [app.main.data.workspace.agent-tools.agentic :as atg]
   [app.main.data.workspace.agent-tools.common :as atc]
   [app.main.data.workspace.agent-tools.tokens :as att]
   [app.main.data.workspace.design-doc :as dd]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(declare uint8->base64)

;; --- read_design

;; --- read_design's budget
;;
;; Tool results are hard-capped at 20k chars (`agent/max-tool-result-chars`), and
;; `read_design` is the most-called tool we have. Every list it returns is
;; bounded, and every bound announces itself: a list silently cut is read as the
;; whole file, which is worse than a short list that says it is short.

(def ^:private max-listed 60)

(defn find-shapes
  [{:keys [name type] :as query}]
  (if-let [problem
           (cond
             (and (nil? name) (nil? type))
             (str "find_shapes: pass a name and/or a type to search for — an empty"
                  " query would return the whole file, which is what read_design"
                  " is for")

             ;; an unmapped type matches nothing, and "0 found" reads as "none
             ;; exist" — say it was the query that was wrong
             (and (some? type) (nil? (get atc/searchable-types (str/lower type))))
             (dm/str "find_shapes: \"" type "\" is not a shape type — use one of: "
                     (str/join ", " (sort (distinct (keys atc/searchable-types))))))]
    (rx/throw (ex-info problem {}))
    (let [objects (dsh/lookup-page-objects @st/state)
          hits    (->> (vals objects)
                       (filter #(atc/shape-matches? % query))
                       (sort-by :name))
          [items omitted] (atc/bounded hits max-listed "matches")]
      (rx/of (cond-> {:matches (mapv #(atc/summarize-shape objects (:id %) {:look? true}) items)
                      :found (count hits)}
               omitted (assoc :omitted omitted))))))

(defn library-components
  "Every component the agent can instantiate — this file's and every connected
  library's. Variant members are omitted: they are already listed under
  `:variants` with their component ids, and repeating them here would double the
  payload on a set-heavy file."
  [libraries current-file-id]
  (->> libraries
       (mapcat (fn [[file-id file]]
                 (->> (ctkl/components-seq (:data file))
                      (remove ctc/is-variant?)
                      (map (fn [component]
                             (cond-> {:componentId (dm/str (:id component))
                                      :name (:name component)}
                               ;; the local file is the default target, so saying
                               ;; so on every entry is payload for nothing
                               (not= file-id current-file-id)
                               (assoc :fileId (dm/str file-id)
                                      :library (:name file))))))))
       (vec)))

(defn variant-sets
  "Every variant container on the page with its members and their properties —
  the structural view `create_variant` writes and Phase 03/04 target.

  Scans the whole objects map rather than the top level: a container relocated
  into a board still exists, and an agent that cannot see it rebuilds a set that
  is already there. Member order is `find-variant-components`' own (it reverses
  the container's child order deliberately) — do not re-sort it."
  [data objects]
  (->> objects
       (keep (fn [[id shape]]
               (when (ctc/is-variant-container? shape)
                 {:variantId (dm/str id)
                  :name (:name shape)
                  :members (->> (cfv/find-variant-components data objects id)
                                (remove nil?)
                                (mapv (fn [component]
                                        {:componentId (dm/str (:id component))
                                         :name (:name component)
                                         :properties (mapv #(select-keys % [:name :value])
                                                           (:variant-properties component))})))})))
       (vec)))

(defn read-design
  []
  (let [state    @st/state
        file-id  (:current-file-id state)
        page     (dsh/lookup-page state)
        objects  (dsh/lookup-page-objects state)
        data     (dsh/lookup-file-data state)
        top-ids  (get-in objects [uuid/zero :shapes])
        selected (dsh/get-selected-ids state)
        variants (variant-sets data objects)
        comps    (library-components (dsh/lookup-libraries state) file-id)

        [shapes shapes-omitted]     (atc/bounded top-ids max-listed "top-level shapes")
        [variants variants-omitted] (atc/bounded variants max-listed "variant sets")
        [comps comps-omitted]       (atc/bounded comps max-listed "components")

        omitted (cond-> {}
                  shapes-omitted   (assoc :shapes shapes-omitted)
                  variants-omitted (assoc :variants variants-omitted)
                  comps-omitted    (assoc :components comps-omitted))]
    (cond-> {:file (get-in state [:files file-id :name])
             :page (:name page)
             ;; every page, so the agent knows others exist and can switch/create;
             ;; the shape lists above are still just THIS page's
             :pages (let [cur (:current-page-id state)]
                      (mapv (fn [pid]
                              (cond-> {:id (dm/str pid)
                                       :name (get-in data [:pages-index pid :name])}
                                (= pid cur) (assoc :current true)))
                            (:pages data)))
             :selection (mapv #(atc/summarize-shape objects % {:look? true}) selected)
             :shapes (mapv #(atc/summarize-shape objects %) shapes)
             :variants variants
             :components comps
             :tokens (atc/tokens-by-type (some-> data :tokens-lib ctob/get-tokens-in-active-sets vals))
             :skills (ask/catalog-manifest state)
             ;; a boolean, not the text: the doc itself is already inlined in
             ;; the system prompt, and repeating 4k chars in every read_design
             ;; result would double-bill it
             :hasDesignDoc (some? (dd/get-doc state))
             :openViolations (count (atg/audit-violations state))}
      ;; sets/themes only when the file has any — a single-set file pays nothing
      (seq (att/sets-and-themes state))
      (merge (att/sets-and-themes state))

      ;; only when something was actually held back — an always-present "nothing
      ;; omitted" key is payload for nothing
      (seq omitted) (assoc :omitted omitted))))

;; --- render_board
;;
;; The one tool that returns pixels. `render-shape-pixels` draws to a dedicated
;; export surface rather than the viewport, so a board that is scrolled away or
;; zoomed past renders identically — verified byte-identical in Phase 01.
;;
;; It is per-SHAPE: `_render_shape_pixels` takes `(id, scale)` and there is no
;; rectangle, so there is no "render this region" and no "render the page" (the
;; root frame is 0.01×0.01 and returns a 1×1 PNG, silently).

(def ^:private max-render-boards 5)

;; Phase 01 measured scale 2 as the sweet spot: text already legible, ~15kB,
;; ~23ms. Scale 8 costs a 112ms synchronous main-thread block and buys nothing
;; visible.
(def ^:private default-render-scale 2)

;; Image budgets: past the soft threshold a render re-encodes as JPEG (a
;; PHOTOGRAPHIC board compresses ~10×; crisp UI renders rarely cross it); past
;; the hard budget it is refused with the fix named. One oversized render must
;; never kill the NEXT request — the 4M-char payload cap aborted the NYT
;; session twice on photo-filled grid renders.
(def ^:private jpeg-threshold-b64-chars 300000)

(def ^:private max-render-b64-chars 1000000)

(defn- bytes->jpeg-b64
  "PNG pixels → JPEG q0.8 base64. Decodes via Blob + createImageBitmap (no
  fetch, CSP-quiet). Any failure resolves to nil — the caller keeps the PNG."
  [bytes]
  (->> (rx/from
        (-> (js/createImageBitmap (js/Blob. #js [bytes] #js {:type "image/png"}))
            (.then (fn [bitmap]
                     (let [canvas (js/OffscreenCanvas. (.-width bitmap) (.-height bitmap))]
                       (.drawImage (.getContext canvas "2d") bitmap 0 0)
                       (.convertToBlob canvas #js {:type "image/jpeg" :quality 0.8}))))
            (.then (fn [blob]
                     (js/Promise.
                      (fn [resolve reject]
                        (let [reader (js/FileReader.)]
                          (set! (.-onload reader)
                                #(let [s (.-result reader)]
                                   (resolve (subs s (inc (str/index-of s ","))))))
                          (set! (.-onerror reader) reject)
                          (.readAsDataURL reader blob))))))))
       (rx/catch (fn [_] (rx/of nil)))))

(defn- encode-render
  "One rendered board → its attachable image entry (or a refusal)."
  [{:keys [bytes] :as r}]
  (let [png (uint8->base64 bytes)
        r   (dissoc r :bytes)]
    (if (<= (count png) jpeg-threshold-b64-chars)
      (rx/of (assoc r :mtype "image/png" :data png))
      (->> (bytes->jpeg-b64 bytes)
           (rx/map
            (fn [jpeg]
              (let [[mtype data] (if (and jpeg (< (count jpeg) (count png)))
                                   ["image/jpeg" jpeg]
                                   ["image/png" png])]
                (if (> (count data) max-render-b64-chars)
                  {:name (:name r)
                   :error (str "render too large to attach even compressed — "
                               "render a smaller shape (one section, not the "
                               "whole screen) or pass scale 1")}
                  (assoc r :mtype mtype :data data)))))))))

(defn- uint8->base64
  "The WASM side hands back raw PNG bytes; the wire wants base64.

  Chunked because `String.fromCharCode` is variadic — spreading a whole
  200kB image across the argument list overflows the stack. 32k is comfortably
  under every engine's limit."
  [^js bytes]
  (let [len (.-length bytes)
        buf (js/Array.)]
    (loop [i 0]
      (when (< i len)
        (let [end (min len (+ i 32768))]
          (.push buf (.apply js/String.fromCharCode nil (.subarray bytes i end)))
          (recur end))))
    (js/btoa (.join buf ""))))

(defn- render-available?
  "The real precondition is the WASM renderer being live for this file — NOT
  `:wasm-export`, which gates Penpot's own export feature, is undeclared in
  `all-flags`, and is off everywhere. Without render-wasm the shape tree is not
  loaded and the call aborts the WASM module rather than returning."
  [state]
  (features/active-feature? state "render-wasm/v1"))

(defn- resolve-board
  "Accepts an id or a name. Names are what the model actually has — `read_design`
  reports them — and it should not have to care that Penpot thinks in uuids."
  [objects term]
  ;; `parse*` and not `uuid/uuid`: the latter is documented UNSAFE and will
  ;; happily build a nonsense uuid out of a board called \"Card\"
  (or (some->> (uuid/parse* term) (get objects) :id)
      (->> (vals objects)
           (filter #(= term (:name %)))
           (first)
           (:id))))

(defn render-board
  [{:keys [names scale]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        scale   (-> (or scale default-render-scale) (max 1) (min 4))
        terms   (or (seq names)
                    (->> (dsh/get-selected-ids state) (mapv str)))]
    (cond
      (not (render-available? state))
      (rx/throw (ex-info (str "Rendering is unavailable: this file is using the SVG renderer. "
                              "The user can switch it on in Settings › Options › "
                              "\"Use WebGL renderer\". Carry on with read_design instead.")
                         {}))

      (empty? terms)
      (rx/throw (ex-info "Nothing to render: pass board names, or ask the user to select something." {}))

      :else
      (let [terms   (vec (take max-render-boards terms))
            results (mapv (fn [term]
                            (let [id (resolve-board objects term)]
                              (cond
                                (nil? id)
                                {:name term :error "no shape with that name or id"}

                                ;; The page root has no dimensions, so rendering
                                ;; it returns a 1×1 PNG with no error at all —
                                ;; the agent would "see the page", get a single
                                ;; grey pixel, and describe it in good faith.
                                ;; Better a refusal that names the alternative.
                                (= id uuid/zero)
                                {:name term
                                 :error (str "the page as a whole cannot be rendered — "
                                             "name the boards you want instead, e.g. "
                                             (->> (get-in objects [uuid/zero :shapes])
                                                  (keep #(:name (get objects %)))
                                                  (take 3)
                                                  (str/join ", ")))}

                                :else
                                (try
                                  (let [bytes (wasm.api/render-shape-pixels id scale)]
                                    {:name (or (:name (get objects id)) term)
                                     :id (str id)
                                     ;; raw PNG pixels; encoding decides the format
                                     :bytes bytes})
                                  (catch :default cause
                                    {:name term :error (or (ex-message cause) "render failed")})))))
                          terms)
            ok      (filterv :bytes results)
            failed  (filterv :error results)]
        (if (empty? ok)
          (rx/throw (ex-info (str "Could not render: "
                                  (str/join "; " (map #(str (:name %) " — " (:error %)) failed)))
                             {}))
          ;; `:images` is lifted out before the rest is JSON-stringified into the
          ;; tool result — otherwise 200kB of base64 would be truncated into the
          ;; 20k-char content string and the model would see a mangled prefix.
          (->> (reduce (fn [acc r] (rx/concat acc (encode-render r))) (rx/empty) ok)
               (rx/reduce conj [])
               (rx/map
                (fn [encoded]
                  (let [good   (filterv :data encoded)
                        over   (filterv :error encoded)
                        failed (into failed over)]
                    (if (empty? good)
                      (throw (ex-info (str "Could not render: "
                                           (str/join "; " (map #(str (:name %) " — " (:error %)) failed)))
                                      {}))
                      {:images (mapv #(select-keys % [:mtype :data]) good)
                       :rendered (mapv #(select-keys % [:name :id]) good)
                       :failed (when (seq failed) (mapv #(select-keys % [:name :error]) failed))
                       :scale scale}))))))))))
