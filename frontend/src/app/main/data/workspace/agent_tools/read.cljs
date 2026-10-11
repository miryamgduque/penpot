;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.read
  "Read tools: read_design, find_shapes and render_board."
  (:require
   [app.common.agent.read :as car]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-skills :as ask]
   [app.main.data.workspace.agent-tools.agentic :as atg]
   [app.main.data.workspace.design-doc :as dd]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(declare uint8->base64)

;; --- read_design and find_shapes
;;
;; The logic lives in `app.common.agent.read`, shared with the server-side
;; tools. What is added here is what only the running workspace knows: the
;; page in view, the selection, the enabled skills and the open violations.

(defn find-shapes
  [query]
  (if-let [problem (car/find-shapes-problem query)]
    (rx/throw (ex-info problem {}))
    (rx/of (car/find-shapes (dsh/lookup-page-objects @st/state) query))))

(def library-components car/library-components)

(def variant-sets car/variant-sets)

(defn workspace-extras
  "The facts `read_design` reports that only a running workspace knows."
  [state]
  {:skills (ask/catalog-manifest state)
   ;; a boolean, not the text: the doc itself is already inlined in the system
   ;; prompt, and repeating 4k chars in every read_design result would
   ;; double-bill it
   :hasDesignDoc (some? (dd/get-doc state))
   :openViolations (count (atg/audit-violations state))})

(defn read-design
  []
  (let [state   @st/state
        file-id (:current-file-id state)]
    (car/read-design {:file-id file-id
                      :file-name (get-in state [:files file-id :name])
                      :data (dsh/lookup-file-data state)
                      :page-id (:current-page-id state)
                      :libraries (dsh/lookup-libraries state)
                      :selected (dsh/get-selected-ids state)}
                     (workspace-extras state))))

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
                                  (let [bytes (wasm.api/render-shape-pixels id scale :png)]
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
