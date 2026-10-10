;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools.document
  "Document-level tools: pages, comments, versions, undo/redo and
  handoff codegen."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.uuid :as uuid]
   [app.main.data.comments :as dc]
   [app.main.data.common :as dcm]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-tools.common :as atc]
   [app.main.data.workspace.agent-tools.structure :as ats]
   [app.main.data.workspace.pages :as dwpg]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.versions :as dwv-ver]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.code-gen :as cg]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

;; --- Undo / redo
;;
;; `dwu/undo` / `dwu/redo` are what ⌘Z/⌘⇧Z dispatch. Two guards matter: `undo`
;; no-ops on an empty stack (index -1) and while a text/path editor session is
;; open ("editors handle their own undo's") — both would read as success to a
;; thin passthrough.
;;
;; The undo stack is SHARED and per-session: the user's manual edits and the
;; agent's interleave under one profile, so there is no cheap, reliable way to
;; prove the top entry is the agent's own (direct-commit tagging does not reach
;; the delegated events most tools use). Rather than pretend, this tool discloses
;; that it reverts the single most-recent change — normally the agent's own last
;; action. Full ownership tracking (watermark/tags) is a flagged follow-up.

(defn undo-problem
  "Why `undo_change` cannot run right now, or nil. Pure over the relevant state."
  [{:keys [edition drawing items index]}]
  (cond
    (or (some? edition) (some? (:object drawing)))
    "undo_change: a text or path editor is open — it handles its own undo. Close it first."

    (or (empty? items) (= index -1))
    "undo_change: nothing to undo."))

(defn undo-change
  []
  (let [state (deref st/state)
        undo  (:workspace-undo state)
        ctx   {:edition (get-in state [:workspace-local :edition])
               :drawing (get state :workspace-drawing)
               :items   (:items undo)
               :index   (or (:index undo) (dec (count (:items undo))))}]
    (if-let [problem (undo-problem ctx)]
      (rx/throw (ex-info problem {}))
      (do
        (atc/interrupt!)
        (st/emit! dwu/undo)
        (rx/of {:note (str "undid the most recent change on the shared undo stack — "
                           "normally your own last action, but the user's if they "
                           "just edited. Verify with read_design; redo_change puts "
                           "it back.")})))))

(defn redo-change
  []
  (atc/interrupt!)
  (st/emit! dwu/redo)
  (rx/of {:note "redid the most recently undone change. Verify with read_design."}))

;; --- Handoff codegen
;;
;; The whole method of `penpot-design-to-code-review`: read what Penpot says this
;; design IS, compare it to the code that shipped. Its own script calls
;; generateMarkup AND generateStyle together and returns `{markup, style, …}`, so
;; this is one tool returning both rather than two the agent must pair up.
;;
;; Comparison is why size matters here more than elsewhere: a half stylesheet
;; does not degrade the review, it produces confident findings about rules that
;; were never truncated in reality. Phase 15's backstop refuses oversized results
;; outright, so the failure is loud — this tool just narrows the ask first.

(def ^:private markup-types #{"html" "svg"})

(defn code-problem
  "Why `generate_code` cannot run, or nil. Pure."
  [objects ids type]
  (or (when (empty? ids)
        (str "generate_code: nothing to inspect — pass shapeIds, or select the"
             " shapes you want the markup for"))
      (ats/ids-problem "generate_code" objects ids)
      (when (and (some? type) (not (contains? markup-types type)))
        (dm/str "generate_code: \"" type "\" is not a markup type — use one of: "
                (str/join ", " (sort markup-types))))))

(defn generate-code
  [{:keys [shapeIds type includeChildren]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (if (seq shapeIds)
                  (into [] (comp (keep parse-uuid) (distinct)) shapeIds)
                  (vec (dsh/get-selected-ids state)))
        type    (or type "html")]
    (if-let [problem (code-problem objects ids type)]
      (rx/throw (ex-info problem {}))
      (let [roots    (mapv #(get objects %) ids)
            ;; children? defaults true, as the plugin's does — a component's own
            ;; styles without its children is rarely what a review wants
            kids?    (not (false? includeChildren))
            resolved (cond->> (cfh/clean-loops objects roots)
                       kids? (mapcat #(cfh/get-children-with-self objects (:id %))))
            markup   (cg/generate-formatted-markup-code objects type resolved)
            style    (cg/generate-style-code objects "css" roots resolved nil)]
        (rx/of {:markup markup
                :style style
                :shapes (count resolved)
                :note (str "Penpot's own view of these shapes — compare it against "
                           "the shipped code rather than reading the canvas. If the "
                           "result is refused as too large, inspect fewer shapes or "
                           "pass includeChildren: false.")})))))

;; --- Pages
;;
;; The agent lived its whole life on the current page. read_design now lists every
;; page; create_page makes a new one (the classic "playground" a skills session
;; should make its mess on) without switching; switch_page navigates — which moves
;; the user's canvas, so it discloses loudly. All other tools still operate on the
;; CURRENT page (option a); threading a pageId through every problem-checker is the
;; better end state but a far larger change. delete_page is out: it is delete_shape
;; times everything on the page, and duplicate + rename cover tidiness.

(defn create-page
  [{:keys [name]}]
  (let [state   @st/state
        file-id (:current-file-id state)
        id      (uuid/next)]
    (atc/interrupt!)
    ;; create-page auto-names "Page N"; rename after if a name was asked for
    (st/emit! (dwpg/create-page {:page-id id :file-id file-id}))
    (when (and (string? name) (not (str/blank? name)))
      (st/emit! (dwpg/rename-page id (str/trim name))))
    (rx/of {:pageId (dm/str id)
            :note (str "page created" (when name (str " named \"" (str/trim name) "\""))
                       " — you are NOT on it; the other tools still act on the current "
                       "page. Use switch_page to move there.")})))

(defn switch-page
  [{:keys [pageId]}]
  (let [state (deref st/state)
        pid   (some-> pageId parse-uuid)
        known (some? (get-in (dsh/lookup-file-data state) [:pages-index pid]))]
    (cond
      (nil? pid)
      (rx/throw (ex-info "switch_page: pageId is required (see the pages in read_design)" {}))

      (not known)
      (rx/throw (ex-info (dm/str "switch_page: no page with id " pageId " in this file — see read_design") {}))

      :else
      (do
        (st/emit! (dcm/go-to-workspace :page-id pid))
        (rx/of {:note (str "switched — this MOVED the user's canvas to that page. The other "
                           "tools now act on it. Say so, since the user's view changed.")})))))

;; --- Comments (outward-facing)
;;
;; The plan's first OUTWARD-facing tool: a comment is written as the current user
;; and notifies collaborators. It is the artifact a real review produces — a
;; finding pinned where the problem is, not ephemeral chat text. The governance
;; is in the description: make authorship explicit, and only leave comments when
;; the user asked for a review left ON THE FILE.

(defn leave-comment
  [{:keys [content x y]}]
  (let [state   @st/state
        clean   (str/trim (str content))]
    (if (str/blank? clean)
      (rx/throw (ex-info "leave_comment: content is required" {}))
      (do
        (atc/interrupt!)
        ;; fire-and-report: the thread is created via the backend async. identity
        ;; + false = don't pop the comment editor open in the user's face.
        (st/emit! (dc/create-thread-on-workspace
                   {:page-id (:current-page-id state)
                    :file-id (:current-file-id state)
                    :position (gpt/point (or x 0) (or y 0))
                    :content clean}
                   identity false))
        (rx/of {:note (str "comment posted at (" (or x 0) ", " (or y 0) ") — it is written "
                           "as the current user and notifies collaborators. Make sure the "
                           "content says it is from the design agent. Verify in the Comments "
                           "panel.")})))))

(defn list-comments
  [_]
  (let [file-id (:current-file-id @st/state)]
    (->> (rp/cmd! :get-comment-threads {:file-id file-id})
         (rx/map (fn [threads]
                   {:comments (mapv (fn [t]
                                      (cond-> {:seqn (:seqn t)
                                               :content (:content t)}
                                        (:position t) (assoc :x (:x (:position t)) :y (:y (:position t)))
                                        (:count-comments t) (assoc :replies (dec (:count-comments t)))))
                                    threads)})))))

;; --- Save a version
;;
;; The cheapest insurance in the plan. gotcha #12's prescribed defence is manual
;; ("duplicate the file before risky work"); this is the first-class answer —
;; `create-version-from-plugins` force-persists and snapshots. Pairs with undo
;; exactly where undo is weakest: undo covers the last step, a snapshot covers
;; the next hundred. Restore is deliberately OUT — that is the user's move in the
;; History panel, with their own eyes on what they lose.

(defonce ^:private agent-version-count (atom 0))

(def ^:private max-agent-versions 10)

(defn save-version
  [{:keys [label]}]
  (let [file-id (:current-file-id @st/state)
        clean   (str/trim (str label))
        full    (if (str/starts-with? clean "agent:") clean (str "agent: " clean))]
    (cond
      (str/blank? clean)
      (rx/throw (ex-info (str "save_version: label is required — say what the snapshot is for,"
                              " e.g. \"before variant surgery\"") {}))

      (>= (deref agent-version-count) max-agent-versions)
      (rx/throw (ex-info (str "save_version: already saved " max-agent-versions " snapshots this"
                              " session — that is plenty of checkpoints. Prune or restore in the"
                              " History panel.") {}))

      :else
      (do
        (swap! agent-version-count inc)
        (atc/interrupt!)
        ;; fire-and-report: the snapshot resolves async through the backend. The
        ;; result discloses where to find it rather than blocking on the round trip.
        (st/emit! (dwv-ver/create-version-from-plugins file-id full (fn [_]) (fn [_])))
        (rx/of {:label full
                :note (str "snapshot requested — it appears in the History panel › versions "
                           "once the backend saves it (the file force-persists first). "
                           "Restoring a version is the user's move there, not mine.")})))))
