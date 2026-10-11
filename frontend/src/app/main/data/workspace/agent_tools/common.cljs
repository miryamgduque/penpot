;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.common
  "Shared vocabulary of the agent tool families: shape summaries and
  labels, the token-only-colors enforcement boundary, fill parsing, flex
  flow-order translation and the create-shape builder they all lean on."
  (:require
   [app.common.agent.read :as car]
   [app.common.agent.write :as caw]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; --- insert_image

(def http-url-re
  #"(?i)^https?://\S+$")

(defn tokens-lib
  [state]
  (some-> (dsh/lookup-file-data state) :tokens-lib))

;; --- Shared with the server
;;
;; The pure halves of the write tools — input validation, fills, strokes,
;; shadows, flow order — live in `app.common.agent.write`, shared with the
;; server-side tools. They keep their names here for the families that use
;; them.

(def shape-type caw/shape-type)
(def shape-label caw/shape-label)
(def labels caw/labels)
(def enum-problem caw/enum-problem)
(def normalize-hex caw/normalize-hex)
(def fill-colors caw/fill-colors)
(def fill-problem caw/fill-problem)
(def fill->shape caw/fill->shape)
(def input-colors caw/input-colors)
(def flow-append-index caw/flow-append-index)

(def text-aligns #{"left" "center" "right" "justify"})

;; The shape and token summaries the read tools are built from live in
;; `app.common.agent.read`, shared with the server-side tools. They keep their
;; names here for the families that use them.

(def searchable-types car/searchable-types)
(def bounded car/bounded)
(def fill-summary car/fill-summary)
(def effect-summary car/effect-summary)
(def summarize-shape car/summarize-shape)
(def tokens-by-type car/tokens-by-type)
(def shape-matches? car/shape-matches?)

;; --- Structural tools (create / modify / nest)
;;
;; Each mutation is emitted through the internal changes pipeline, so it is a
;; normal, undoable Penpot edit. Writes apply to the store synchronously (a
;; following read_design sees them); geometry via the transform modifiers and
;; WASM rendering settle on a later tick — hence the "verify with read_design"
;; note in each result.

(defn interrupt!
  "Clear any transient drawing/edition state before a structural mutation —
  operating mid-drawing leaves the path/draw overlay mounted with stale data
  and crashes its renderer."
  []
  (st/emit! :interrupt))

;; --- token-only-colors enforcement (tool boundary)
;;
;; When the file enforces `token-only-colors`,
;; the color-setting tools accept only colors that are a design token value or a
;; library color; a raw hex is rejected with a rule-tagged error the agent
;; recovers from by using create_token + apply_tokens. Which rules are
;; enforced is read from `[:ai-panel <file-id> :enforced-rules]` (populated by
;; the backend skills resolution — phase 07).

(defn allowed-colors
  "Set of normalized hexes allowed under token-only-colors: color-token values
  in active sets + the file's library colors."
  [state]
  (caw/allowed-colors (dsh/lookup-file-data state)))

(defn rule-enforced?
  [state rule]
  (when-let [file-id (:current-file-id state)]
    (contains? (dm/get-in state [:ai-panel file-id :enforced-rules]) rule)))

(defn color-guard
  "The token-only-colors state of the open file, as the shared tools take it."
  [state]
  (let [enforced? (boolean (rule-enforced? state caw/color-rule))]
    {:enforced? enforced?
     :allowed (when enforced? (allowed-colors state))}))

(defn color-violation
  "Returns a token-only-colors ex-info when `hex` is a raw color that the rule
  forbids, or nil when it is allowed / the rule is not enforced."
  [state hex]
  (when-let [message (caw/color-problem (color-guard state) hex)]
    (ex-info message {:rule caw/color-rule})))

(defn reflow-parent!
  "A child added to a laid-out board must flow into it, or it lands at its raw
  x/y outside the layout — a grid gallery stops being a gallery on the next
  card. `:layout/update` only reflows POSITIONS, so grid needs assign-cells
  first (it auto-adds a track for the orphan); flex just needs the reflow."
  [objects pid]
  (let [parent (get objects pid)]
    (cond
      (ctl/grid-layout? parent)
      (do (st/emit! (dwsh/update-shapes [pid]
                                        (fn [p objs] (-> (ctl/assign-cells p objs)
                                                         (ctl/reorder-grid-children)))
                                        {:with-objects? true}))
          (st/emit! (ptk/data-event :layout/update {:ids [pid]})))

      (ctl/flex-layout? parent)
      (st/emit! (ptk/data-event :layout/update {:ids [pid]})))))

;; --- Flow order
;;
;; Penpot lays flex children out in REVERSE `:shapes` order for `row`/`column`
;; (flex_layout/layout_data.cljc reverses the children before positioning) and
;; in vector order for the -reverse directions. The tools speak READING order
;; and translate here: "create A, then B" reads A-then-B on canvas, and
;; nest_shape's `index` is the position in the flow (0 = first). Without the
;; translation the model discovers the reversal the hard way and starts
;; authoring row-reverse layouts to compensate — semantically backwards files
;; (the Kahoot session, 2026-07-16).

(def nest-vector-index caw/nest-vector-index)

(defn create-shape
  [{:keys [type fill] :as input}]
  ;; via input-colors, not `fill` directly: both colour-setting tools share one
  ;; collector, so a param added later cannot quietly bypass the guard
  (if-let [problem (or (some-> (fill-problem fill) (->> (dm/str "create_shape: ")) (ex-info {}))
                       (some #(color-violation @st/state %) (input-colors input)))]
    (rx/throw problem)
    (let [state    @st/state
          page     (dsh/lookup-page state)
          objects  (:objects page)
          {:keys [shape parent index]} (caw/new-shape objects input)
          parent?  (some? parent)
          pid      (:id parent)
          changes  (-> (cb/empty-changes)
                       (cb/with-page page)
                       (cb/with-objects objects)
                       (cb/add-object shape (when (some? index) {:index index})))
          undo-id  (js/Symbol)]
      (interrupt!)
      ;; One undo step for the add AND its grid/flex reflow: without the
      ;; transaction ⌘Z reverts only the reflow and leaves the orphaned shape.
      (st/emit! (dwu/start-undo-transaction undo-id))
      (st/emit! (dch/commit-changes changes))
      (when parent?
        (reflow-parent! objects pid))
      (st/emit! (dwu/commit-undo-transaction undo-id))
      (rx/of {:id (dm/str (:id shape))
              :type type
              :parentId (when parent? (dm/str pid))
              :note "created — verify geometry with read_design"}))))
