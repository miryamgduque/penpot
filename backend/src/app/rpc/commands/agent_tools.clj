;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.agent-tools
  "Design agent tools that run on the server, without a browser.

  The agent service has no session of its own. When the backend forwards a
  turn to it, it also hands over a turn token: a short-lived, encrypted claim
  naming the profile that started the turn and the file it is working on. The
  service presents that token to run a tool, and the tool runs with exactly
  that profile's permissions on exactly that file.

  The tools themselves are the shared implementations in
  `app.common.agent.*`, the same code the workspace runs. What is added here
  is loading the file and its libraries from storage."
  (:require
   [app.binfile.common :as bfc]
   [app.common.agent.read :as car]
   [app.common.agent.recipe-save :as cars-save]
   [app.common.agent.recipes :as cars]
   [app.common.agent.tools :as cat]
   [app.common.agent.usage :as cau]
   [app.common.agent.write :as caw]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.geom.point :as gpt]
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.components-list :as ctkl]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as feat.fdata]
   [app.features.file-snapshots :as fsnap]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.team-recipes :as team-recipes]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.storage :as sto]
   [app.tokens :as tokens]
   [app.util.blob :as blob]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def server-tools
  "The tools the backend runs itself. The agent service asks a connected
  workspace to run every other tool."
  #{"read_design" "find_shapes" "audit_file" "get_page_meta"
    "leave_comment" "list_comments" "save_version" "save_recipe"
    "list_shared_libraries" "link_library"})

(def ^:private fileless-tools
  #{"get_page_meta" "fetch_web_page" "turn_profile"})

(def ^:private service-tools
  "What the agent service asks for on its own account, to build a tool it
  runs itself. Not offered to the model."
  #{"fetch_web_page" "turn_profile" "check_page" "capability_usage"})

(def engine-tools
  "The tools that change a file. They need the render engine to lay things
  out, which the backend cannot run, so they run in the exporter: it reads the
  file and commits its changes through the two commands at the end of this
  namespace, with the same turn token."
  ;; and the two that need the exporter's browser: the screenshot of a web
  ;; page, and handoff code, which the frontend's render page generates
  (conj (set (keys cat/tools)) "screenshot_page" "generate_code"))

;; A tool result is capped like the workspace caps it: an oversized result is
;; replaced by a small, valid object saying so, never cut mid-token.
(def ^:private max-result-chars 20000)

;; --- Turn token

(def ^:private turn-token-iss :agent-turn)

(defn turn-token
  "A claim that `profile-id` started an agent turn on `file-id`. Outlives the
  longest turn we expect; a tool call after that fails and the turn ends.

  `turn` says which turn of which conversation it is — `{:turn-id
  :conversation-id}` — so what the agent changes can be told apart later. A
  token made without it still works; its changes are not recorded."
  ([cfg profile-id file-id]
   (turn-token cfg profile-id file-id nil))
  ([cfg profile-id file-id {:keys [turn-id conversation-id]}]
   (tokens/generate cfg (cond-> {:iss turn-token-iss
                                 :exp (ct/in-future {:hours 2})
                                 :profile-id profile-id
                                 :file-id file-id}
                          (uuid? turn-id)
                          (assoc :turn-id turn-id)

                          (string? conversation-id)
                          (assoc :conversation-id conversation-id)))))

(defn- verify-turn-token
  [cfg token]
  (let [claims (try
                 (tokens/verify cfg {:token token :iss turn-token-iss})
                 (catch Throwable _
                   (ex/raise :type :validation
                             :code :invalid-turn-token
                             :hint "the agent turn token is not valid")))]
    (when-not (and (uuid? (:profile-id claims)) (uuid? (:file-id claims)))
      (ex/raise :type :validation
                :code :invalid-turn-token
                :hint "the agent turn token is not valid"))
    claims))

;; --- Loading what a tool looks at

(defn- parse-ids
  [ids]
  (into [] (keep #(when (string? %) (uuid/parse* %))) ids))

(defn- load-whole-file
  "A file with all of its data in memory. Stored files keep large parts
  behind lazy references; the tools walk whole pages, so they are resolved
  up front."
  [cfg file-id]
  (some->> (bfc/get-file cfg file-id :read-only? true :throw-if-not-exists? false)
           (feat.fdata/realize cfg)))

(defn- load-view
  "The file as the read tools want it: its data, its libraries and the page in
  view. `context` is what a connected workspace reported — the page the user
  is on and what they have selected. With no workspace there is no page in
  view, so the first page stands in, and nothing is selected."
  [cfg file-id {:keys [pageId selectedIds]}]
  (let [file    (load-whole-file cfg file-id)
        data    (:data file)
        libs    (->> (bfc/get-file-libraries cfg file-id)
                     (map :id)
                     (remove #(= file-id %))
                     (keep #(load-whole-file cfg %))
                     (map (juxt :id identity))
                     (into {file-id file}))
        page-id (or (some->> pageId str uuid/parse* (#(when (contains? (:pages-index data) %) %)))
                    (first (:pages data)))]
    {:file-id file-id
     :file-name (:name file)
     :data data
     :page-id page-id
     :libraries libs
     :selected (parse-ids selectedIds)}))

;; --- Design rules
;;
;; A rule is active on a file when the workspace that started the turn says
;; so, or when it is mandatory: an enabled rule of the instance or of the
;; file's team marked as such. The first is the user's choice; the second is
;; not, so it is read here, from the database, on every call.

(def ^:private sql:mandatory-rules
  "SELECT s.name
     FROM design_skill AS s
    WHERE s.kind = 'rule'
      AND s.is_mandatory IS TRUE
      AND s.is_enabled IS TRUE
      AND (s.team_id IS NULL OR s.team_id = ?)
      AND NOT EXISTS (SELECT 1
                        FROM design_skill_override AS o
                       WHERE o.team_id = ?
                         AND o.skill_name = s.name
                         AND o.is_enabled IS FALSE)")

(def ^:private sql:file-team
  "SELECT p.team_id
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE f.id = ?")

(defn- enforced-rules
  "The names of the rules active on the file for this call."
  [cfg file-id {:keys [enforcedRules]}]
  (let [team-id (:team-id (db/exec-one! cfg [sql:file-team file-id]))]
    (-> #{}
        (into (filter string?) enforcedRules)
        (into (map :name) (db/exec! cfg [sql:mandatory-rules team-id team-id])))))

;; --- Tools

(defmulti ^:private run-tool
  "→ the tool's result as data. A problem the model can act on is raised as
  `:type :agent-tool`, with the message it should read as the hint."
  (fn [tool _view _input _context] tool))

(defmethod run-tool "read_design"
  [_ view _ {:keys [extras]}]
  (car/read-design view
                   (-> (select-keys extras [:skills :openViolations])
                       (assoc :hasDesignDoc (car/has-design-doc? (:data view))))))

(defmethod run-tool "find_shapes"
  [_ {:keys [data page-id]} input _]
  (let [query (select-keys input [:name :type])]
    (when-let [problem (car/find-shapes-problem query)]
      (ex/raise :type :agent-tool :hint problem))
    (car/find-shapes (get-in data [:pages-index page-id :objects]) query)))

(defmethod run-tool "audit_file"
  [_ {:keys [file-id data page-id]} _ {:keys [::cfg] :as context}]
  (let [enforced (enforced-rules cfg file-id context)]
    (car/audit-result
     (car/audit-violations (get-in data [:pages-index page-id :objects])
                           {:enforced enforced
                            :allowed (when (contains? enforced caw/color-rule)
                                       (caw/allowed-colors data))
                            :normalize caw/normalize-hex}))))

;; --- The web
;;
;; A page is fetched by the backend, under its own network rules. Its text is
;; written by whoever owns the page, so it never goes to the model that holds
;; the design tools: `get_page_meta` returns the parsed fields only, and the
;; text is handed to the agent service, which digests it with a model that has
;; no tools.

(def ^:private http-url-re #"(?i)^https?://\S+$")

(def ^:private fetch-web-page-fn
  (delay
    (->> (sv/scan-ns 'app.rpc.commands.agent-web)
         (some (fn [[f mdata]]
                 (when (= "fetch-web-page" (::sv/name mdata)) f))))))

(def ^:private fetch-error-hints
  {:ssrf-blocked-target
   "the host is private or blocked by this Penpot instance"
   :unable-to-fetch-page
   "the page could not be fetched (unreachable host, timeout, or error status)"
   :content-type-not-allowed
   "the url is not an html or plain-text page"})

(defn- fetch-web-page
  "→ the fetched page. A failure is raised as the message the model reads,
  under the name of the `tool` it called."
  [cfg tool url]
  (when-not (and (string? url) (re-matches http-url-re url))
    (ex/raise :type :agent-tool
              :hint (str tool ": url must be an absolute http(s) URL, e.g. https://example.com")))
  (try
    (@fetch-web-page-fn cfg {:url (u/uri url)})
    (catch clojure.lang.ExceptionInfo cause
      (let [{:keys [type code]} (ex-data cause)]
        (if (contains? #{:validation :restriction} type)
          (ex/raise :type :agent-tool
                    :hint (str tool ": "
                               (or (get fetch-error-hints code)
                                   (str "the fetch failed" (when code (str " (" (name code) ")"))))
                               "."))
          (throw cause))))))

(defmethod run-tool "get_page_meta"
  [_ _ {:keys [url]} {:keys [::cfg]}]
  (let [{:keys [title meta]} (fetch-web-page cfg "get_page_meta" url)]
    {:title title
     :meta meta
     :note (str "metadata only — fetch_page answers content "
                "questions, screenshot_page shows the visual")}))

;; With no workspace connected there is no view to move: switching page is the
;; agent service remembering which page the conversation's tools act on. This
;; tells it whether the file has that page.
(defmethod run-tool "check_page"
  [_ {:keys [data]} {:keys [pageId]} _]
  (let [page-id (when (string? pageId) (uuid/parse* pageId))]
    (cond
      (nil? page-id)
      (ex/raise :type :agent-tool
                :hint "switch_page: pageId is required (see the pages in read_design)")

      (not (contains? (:pages-index data) page-id))
      (ex/raise :type :agent-tool
                :hint (str "switch_page: no page with id " pageId " in this file — see read_design"))

      :else
      {:pageId (str page-id)
       :name (get-in data [:pages-index page-id :name])})))

;; How much of the page, or of the boards named, uses layouts, tokens and
;; components: what the recipes are measured against. For whoever measures,
;; not for the model.
(defmethod run-tool "capability_usage"
  [_ {:keys [file-id data page-id libraries]} {:keys [names shapeIds]} _]
  (let [objects (get-in data [:pages-index page-id :objects])
        named   (set (filter string? names))
        roots   (-> (into [] (keep #(when (string? %) (uuid/parse* %))) shapeIds)
                    (into (comp (filter #(contains? named (:name %))) (map :id)) (vals objects)))]
    (when (and (or (seq names) (seq shapeIds)) (empty? (filter #(contains? objects %) roots)))
      (ex/raise :type :agent-tool :hint "capability_usage: none of those boards is on this page"))
    (-> (cau/capability-usage objects roots file-id)
        ;; and what a design system is held against
        (assoc :system (cau/system-usage objects roots)
               :file (let [lib (:tokens-lib data)]
                       {:tokens (count (some-> lib ctob/get-all-tokens-map))
                        :themes (count (some->> lib ctob/get-themes
                                                (remove #(= ctob/hidden-theme-name (ctob/get-name %)))))
                        :linkedLibraries (count (remove #(= file-id %) (keys libraries)))})))))

(defmethod run-tool "turn_profile"
  [_ _ _ {:keys [::profile-id]}]
  {:profileId (str profile-id)})

(defmethod run-tool "fetch_web_page"
  [_ _ {:keys [url]} {:keys [::cfg]}]
  (fetch-web-page cfg "fetch_page" url))

;; --- Comments and versions
;;
;; Not file changes: they go through the commands the workspace itself calls,
;; run for the profile that started the turn, so permissions, quotas and
;; notifications are the ones every comment and version gets.

(defn- command-fn
  "A command as declared, before the RPC layer wraps it."
  [ns-sym command]
  (->> (sv/scan-ns ns-sym)
       (some (fn [[f mdata]]
               (when (= command (::sv/name mdata)) f)))))

(def ^:private create-comment-thread-fn
  (delay (command-fn 'app.rpc.commands.comments "create-comment-thread")))

(def ^:private get-comment-threads-fn
  (delay (command-fn 'app.rpc.commands.comments "get-comment-threads")))

(def ^:private create-file-snapshot-fn
  (delay (command-fn 'app.rpc.commands.files-snapshot "create-file-snapshot")))

(defmethod run-tool "leave_comment"
  [_ {:keys [file-id page-id data]} {:keys [content x y]} {:keys [::cfg ::profile-id]}]
  (let [clean (str/trim (str content))]
    (when (str/blank? clean)
      (ex/raise :type :agent-tool :hint "leave_comment: content is required"))
    (let [position (gpt/point (or x 0) (or y 0))
          objects  (get-in data [:pages-index page-id :objects])]
      (@create-comment-thread-fn cfg {::rpc/profile-id profile-id
                                      ::rpc/request-at (ct/now)
                                      :file-id file-id
                                      :page-id page-id
                                      :position position
                                      ;; the default validator of this lookup
                                      ;; takes no argument, which only
                                      ;; JavaScript lets through
                                      :frame-id (ctst/get-frame-id-by-position
                                                 objects position {:validator (constantly true)})
                                      :content clean})
      {:note (str "comment posted at (" (or x 0) ", " (or y 0) ") — it is written "
                  "as the current user and notifies collaborators. Make sure the "
                  "content says it is from the design agent. Verify in the Comments "
                  "panel.")})))

(defmethod run-tool "list_comments"
  [_ {:keys [file-id]} _ {:keys [::cfg ::profile-id]}]
  {:comments (mapv (fn [thread]
                     (cond-> {:seqn (:seqn thread)
                              :content (:content thread)}
                       (:position thread)
                       (assoc :x (:x (:position thread)) :y (:y (:position thread)))

                       (:count-comments thread)
                       (assoc :replies (dec (:count-comments thread)))))
                   (@get-comment-threads-fn cfg {::rpc/profile-id profile-id :file-id file-id}))})

;; --- save_recipe
;;
;; --- Shared libraries
;;
;; A team's shared libraries are a design system it already has. The agent
;; may look at them freely; linking one changes what the file depends on, so
;; it happens only when the call says the user agreed.

(def ^:private sql:shared-files
  "SELECT f.id, f.name
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE f.is_shared = true
      AND f.deleted_at IS NULL
      AND p.deleted_at IS NULL
      AND p.team_id = ?
    ORDER BY f.name")

(def ^:private max-component-names 40)

(defn- library-contents
  "What a shared library offers: its components by name, and how many
  tokens, colors and typographies it has."
  [cfg id]
  (when-let [data (:data (load-whole-file cfg id))]
    (let [names (->> (ctkl/components-seq data)
                     (map (fn [{:keys [path name]}] (if (str/blank? path) name (str path " / " name))))
                     (distinct)
                     (sort))]
      (cond-> {:components (count names)
               :componentNames (vec (take max-component-names names))
               :tokens (count (some-> (:tokens-lib data) ctob/get-all-tokens-map))
               :colors (count (:colors data))
               :typographies (count (:typographies data))}
        (< max-component-names (count names))
        (assoc :moreComponents (- (count names) max-component-names))))))

(defmethod run-tool "list_shared_libraries"
  [_ {:keys [file-id libraries]} _ {:keys [::cfg ::profile-id]}]
  (let [team-id (:team-id (db/exec-one! cfg [sql:file-team file-id]))
        linked  (disj (set (keys libraries)) file-id)
        _       (teams/check-read-permissions! cfg profile-id team-id)
        found   (->> (db/exec! cfg [sql:shared-files team-id])
                     (remove #(= file-id (:id %)))
                     (mapv (fn [{:keys [id name]}]
                             (merge {:fileId (str id) :name name :linked (contains? linked id)}
                                    (library-contents cfg id)))))]
    {:libraries found
     :note (cond
             (empty? found)
             "this team has no shared libraries — build on a token pack instead (list_token_packs)"

             (some :linked found)
             (str "a linked library is the design system to build on: use its components "
                  "(read_design lists them, create_instance places one) before any recipe")

             :else
             (str "none is linked to this file. If one fits, PROPOSE linking it — name it and say "
                  "what it offers — and call link_library only after the user says yes"))}))

(defmethod run-tool "link_library"
  [_ {:keys [file-id libraries]} {:keys [fileId userAgreed]} {:keys [::cfg ::profile-id]}]
  (let [library-id (some-> fileId str uuid/parse*)]
    (cond
      (nil? library-id)
      (ex/raise :type :agent-tool
                :hint "link_library: pass fileId — a library from list_shared_libraries")

      (not (true? userAgreed))
      (ex/raise :type :agent-tool
                :hint (str "link_library: nothing was linked. Linking a library changes what this file "
                           "depends on, so the user decides: propose it, wait for their yes, then call "
                           "again with userAgreed: true. Never set it on your own."))

      (= library-id file-id)
      (ex/raise :type :agent-tool :hint "link_library: a file cannot be linked to itself")

      (contains? libraries library-id)
      {:linked true :already true :fileId (str library-id)
       :note "this library was linked already — its components are in read_design"}

      :else
      (db/tx-run! cfg
                  (fn [{:keys [::db/conn] :as cfg}]
                    (let [library (db/exec-one! conn ["SELECT f.id, f.name, f.is_shared FROM file AS f WHERE f.id = ? AND f.deleted_at IS NULL"
                                                      library-id])]
                      (when-not (:is-shared library)
                        (ex/raise :type :agent-tool
                                  :hint "link_library: no shared library has that id — list_shared_libraries says which there are"))
                      (files/check-edition-permissions! conn profile-id file-id)
                      (files/check-read-permissions! conn profile-id library-id)
                      (#'files/check-library-team-ownership! conn file-id library-id)
                      (when (contains? (bfc/get-libraries cfg [library-id]) file-id)
                        (ex/raise :type :agent-tool
                                  :hint "link_library: that library uses this file, so linking it would go in a circle"))
                      (files/link-file-to-library conn {:file-id file-id :library-id library-id})
                      {:linked true
                       :fileId (str library-id)
                       :name (:name library)
                       :note (str "linked. Its components are yours to place: read_design lists them with "
                                  "their ids, create_instance {componentId, fileId} places one. Prefer them "
                                  "over recipes wherever one matches. If this file has no tokens of its "
                                  "own and the library has, take them: apply_token_pack {library: \""
                                  library-id "\"}. A workspace that was open shows the library after a reload.")}))))))

;; The reverse of build_recipe: a board of the file written down as a recipe
;; and kept with the file's team, where every member's agent finds it.

(defmethod run-tool "save_recipe"
  [_ {:keys [file-id file-name data page-id libraries selected]} {:keys [shapeId] :as input}
   {:keys [::cfg ::profile-id]}]
  (let [objects (get-in data [:pages-index page-id :objects])
        id      (or (some-> shapeId str uuid/parse*)
                    (first (filter #(contains? objects %) selected)))
        team-id (:team-id (db/exec-one! cfg [sql:file-team file-id]))]
    (when (nil? id)
      (ex/raise :type :agent-tool
                :hint "save_recipe: pass shapeId — the board to save — or select it first"))
    (db/tx-run! cfg
                (fn [{:keys [::db/conn]}]
                  (teams/check-edition-permissions! conn profile-id team-id)
                  (let [recipes (merge cars/builtin (team-recipes/enabled-recipes conn team-id))
                        saved   (cars-save/from-board objects libraries id
                                                      (select-keys input [:name :title :purpose :category
                                                                          :keywords :params :slots])
                                                      recipes)]
                    (when (some? (:problem saved))
                      (ex/raise :type :agent-tool :hint (str "save_recipe: " (:problem saved))))
                    (team-recipes/store! conn team-id profile-id (:recipe saved)
                                         {:origin "saved" :origin-note file-name})
                    (cond-> {:recipe (-> saved :recipe :name)
                             :params (mapv :name (-> saved :recipe :params))
                             :replaces (cond
                                         (contains? cars/builtin (-> saved :recipe :name)) "the built-in recipe of that name, for this team"
                                         (contains? recipes (-> saved :recipe :name)) "the team's recipe of that name"
                                         :else nil)
                             :note (str "saved for the team — build it with build_recipe {recipe: \""
                                        (-> saved :recipe :name) "\"}. Colors and other values written "
                                        "on the shapes are not part of a recipe: it keeps structure, "
                                        "words and the tokens that are roles.")}
                      (seq (:notes saved)) (assoc :notes (:notes saved))))))))

;; A turn token lasts two hours; that is the window "this session" means here.
(def ^:private max-agent-versions 10)

(defn- recent-agent-versions
  [cfg profile-id file-id]
  (let [since (ct/in-past {:hours 2})]
    (->> (fsnap/get-visible-snapshots cfg file-id)
         (filter #(and (= profile-id (:profile-id %))
                       (str/starts-with? (str (:label %)) "agent:")
                       (ct/is-after? (:created-at %) since)))
         (count))))

(defmethod run-tool "save_version"
  [_ {:keys [file-id]} {:keys [label]} {:keys [::cfg ::profile-id]}]
  (let [clean (str/trim (str label))
        full  (if (str/starts-with? clean "agent:") clean (str "agent: " clean))]
    (cond
      (str/blank? clean)
      (ex/raise :type :agent-tool
                :hint (str "save_version: label is required — say what the snapshot is for,"
                           " e.g. \"before variant surgery\""))

      (>= (recent-agent-versions cfg profile-id file-id) max-agent-versions)
      (ex/raise :type :agent-tool
                :hint (str "save_version: already saved " max-agent-versions " snapshots this"
                           " session — that is plenty of checkpoints. Prune or restore in the"
                           " History panel."))

      :else
      (do
        (@create-file-snapshot-fn cfg {::rpc/profile-id profile-id :file-id file-id :label full})
        {:label full
         :note (str "snapshot saved — it is in the History panel › versions. "
                    "Restoring a version is the user's move there, not mine.")}))))

(defn- result->content
  [result]
  (let [s (json/encode result)]
    (if (> (count s) max-result-chars)
      (json/encode
       {:truncated true
        :chars (count s)
        :limit max-result-chars
        :error (str "This result was " (count s) " characters, over the "
                    max-result-chars " limit, so none of it was returned "
                    "— a partial result would be indistinguishable from a "
                    "complete one. Narrow the request and try again: "
                    "read_design with a smaller depth, or find_shapes with a "
                    "name/type query.")})
      s)))

(defn- decode-json
  [s]
  (try
    (let [v (json/decode s :key-fn keyword)]
      (if (map? v) v {}))
    (catch Throwable _ {})))

(def ^:private schema:run-agent-tool
  [:map {:title "run-agent-tool"}
   [:token [:string {:max 5000}]]
   [:tool [::sm/one-of {:format "string"} (into server-tools service-tools)]]
   ;; JSON, as the model wrote it
   [:input {:optional true} [:string {:max 100000}]]
   ;; JSON: what a connected workspace reported about the page in view
   [:context {:optional true} [:string {:max 200000}]]])

(sv/defmethod ::run-agent-tool
  "Runs one design agent tool on the server for the turn named by `token`."
  {::rpc/auth false
   ::doc/added "2.20"
   ::sm/params schema:run-agent-tool}
  [cfg {:keys [token tool input context]}]
  (let [{:keys [profile-id file-id]} (verify-turn-token cfg token)]
    ;; the token says who started the turn; whether they may still read the
    ;; file is checked now, on every call
    (files/check-read-permissions! cfg profile-id file-id)
    (let [context (-> (decode-json context)
                      (assoc ::cfg cfg ::profile-id profile-id))
          ;; the web tools and the service's own questions are not about
          ;; the file: nothing to load
          view    (when-not (contains? fileless-tools tool)
                    (load-view cfg file-id context))]
      (try
        {:content (let [result (run-tool tool view (decode-json input) context)]
                    (if (contains? service-tools tool)
                      (json/encode result)
                      (result->content result)))
         :is-error false}
        (catch clojure.lang.ExceptionInfo cause
          (if (= :agent-tool (:type (ex-data cause)))
            {:content (:hint (ex-data cause)) :is-error true}
            (throw cause)))))))

;; The exporter holds the render engine but no session. These two commands are
;; all it can do with a turn token: read the page the turn is about, and
;; commit changes to that file as the profile that started the turn.

;; --- For the engine tools: a page out, changes in

(defn- team-fonts
  "The fonts the file's team has uploaded, as the font tools list them."
  [cfg project-id]
  (db/run! cfg
           (fn [{:keys [::db/conn]}]
             (let [project (db/get-by-id conn :project project-id {:columns [:id :team-id]})]
               (->> (db/query conn :team-font-variant
                              {:team-id (:team-id project) :deleted-at nil})
                    ;; `:ttf-file-id` names the font's file: the exporter asks for
                    ;; it by that id when it has to measure or draw text in it
                    (mapv #(select-keys % [:font-id :font-family :font-weight :font-style :ttf-file-id])))))))

(def ^:private schema:get-agent-tool-page
  [:map {:title "get-agent-tool-page"}
   [:token [:string {:max 5000}]]
   [:context {:optional true} [:string {:max 200000}]]])

(sv/defmethod ::get-agent-tool-page
  "The page an engine tool works on, with what it needs to commit against it."
  {::rpc/auth false
   ::doc/added "2.20"
   ::sm/params schema:get-agent-tool-page}
  [cfg {:keys [token context]}]
  (let [{:keys [profile-id file-id]} (verify-turn-token cfg token)]
    (files/check-edition-permissions! cfg profile-id file-id)
    (let [context (decode-json context)
          {:keys [data page-id libraries]} (load-view cfg file-id context)
          file    (bfc/get-file cfg file-id :read-only? true :load-data? false)]
      {:file-id file-id
       :page-id page-id
       :team-fonts (team-fonts cfg (:project-id file))
       ;; the team's own recipes, used over the built-in ones
       :recipes (team-recipes/enabled-recipes cfg (:team-id (db/exec-one! cfg [sql:file-team file-id])))
       ;; the design rules the tool is held to
       :enforced-rules (vec (enforced-rules cfg file-id context))
       :revn (:revn file)
       :vern (:vern file)
       :data data
       ;; the linked libraries, for the tools that use their components
       :libraries (into {}
                        (keep (fn [[id library]]
                                (when (not= id file-id)
                                  [id (select-keys library [:id :name :data])])))
                        libraries)
       ;; what token-only-colors would let through, were it enforced
       :allowed-colors (caw/allowed-colors data)})))

;; An image a tool inserts is downloaded and stored by the backend, under the
;; same limits and network rules as an upload from the workspace.
(def ^:private create-media-fn
  (delay
    (->> (sv/scan-ns 'app.rpc.commands.media)
         (some (fn [[f mdata]]
                 (when (= "create-file-media-object-from-url" (::sv/name mdata)) f))))))

(def ^:private schema:create-agent-tool-media
  [:map {:title "create-agent-tool-media"}
   [:token [:string {:max 5000}]]
   [:url [:string {:max 2048}]]
   [:name {:optional true} [:string {:max 250}]]])

(sv/defmethod ::create-agent-tool-media
  "Stores the image at `url` in the turn's file, as the turn's profile. A
  download the model can fix by asking differently answers `{:error code}`."
  {::rpc/auth false
   ::doc/added "2.20"
   ::sm/params schema:create-agent-tool-media}
  [cfg {:keys [token url name]}]
  (let [{:keys [profile-id file-id]} (verify-turn-token cfg token)]
    (try
      (-> (@create-media-fn cfg (cond-> {::rpc/profile-id profile-id
                                         :file-id file-id
                                         :is-local true
                                         :url (u/uri url)}
                                  (some? name) (assoc :name name)))
          (select-keys [:id :width :height :mtype]))
      (catch clojure.lang.ExceptionInfo cause
        (let [{:keys [type code]} (ex-data cause)]
          (if (and (contains? #{:validation :restriction} type) (keyword? code))
            {:error code}
            (throw cause)))))))

;; The exporter renders a board for the model with no session to download the
;; images in it. This hands them over, to whoever holds the turn token and
;; only if the turn's profile can read the file the image belongs to.

(def ^:private max-media-bytes (* 8 1024 1024))

(defn- object-data
  "A storage object's bytes, base64 encoded, or nil when there is none or it
  is too large to hand over this way."
  [storage object-id]
  (when-let [object (some->> object-id (sto/get-object storage))]
    (when (<= (:size object 0) max-media-bytes)
      (.encodeToString (java.util.Base64/getEncoder)
                       ^bytes (sto/get-object-bytes storage object)))))

(def ^:private schema:get-agent-tool-media
  [:map {:title "get-agent-tool-media"}
   [:token [:string {:max 5000}]]
   [:id ::sm/uuid]])

(sv/defmethod ::get-agent-tool-media
  "The bytes of an image a shape of the turn's file uses, base64 encoded, or
  nothing when there is no such image, it is not the profile's to read, or it
  is too large to hand over this way."
  {::rpc/auth false
   ::doc/added "2.20"
   ::sm/params schema:get-agent-tool-media}
  [{:keys [::sto/storage ::db/pool] :as cfg} {:keys [token id]}]
  (let [{:keys [profile-id]} (verify-turn-token cfg token)
        media (db/get* pool :file-media-object {:id id} {::db/remove-deleted false})]
    {:data (when (and (some? media)
                      (:can-read (perms/get-file-read-permissions pool profile-id (:file-id media) nil)))
             (object-data storage (:media-id media)))}))

;; Fonts the same way: a team's uploaded font can only be downloaded with a
;; session, and the exporter needs it to measure or draw text set in it.

(def ^:private sql:team-font-file
  "SELECT v.ttf_file_id
     FROM team_font_variant AS v
     JOIN project AS p ON (p.team_id = v.team_id)
     JOIN file AS f ON (f.project_id = p.id)
    WHERE f.id = ?
      AND v.ttf_file_id = ?
      AND v.deleted_at IS NULL
    LIMIT 1")

(def ^:private schema:get-agent-tool-font
  [:map {:title "get-agent-tool-font"}
   [:token [:string {:max 5000}]]
   [:id ::sm/uuid]])

(sv/defmethod ::get-agent-tool-font
  "The bytes of a font the team of the turn's file uploaded, by the id of its
  TTF file, base64 encoded; nothing when that team has no such font."
  {::rpc/auth false
   ::doc/added "2.20"
   ::sm/params schema:get-agent-tool-font}
  [{:keys [::sto/storage] :as cfg} {:keys [token id]}]
  (let [{:keys [profile-id file-id]} (verify-turn-token cfg token)]
    (files/check-read-permissions! cfg profile-id file-id)
    {:data (when (some? (db/exec-one! cfg [sql:team-font-file file-id id]))
             (object-data storage id))}))

;; The file-update command as declared, before the RPC layer wraps it: the
;; exporter's commit runs it for the profile the turn token names.
(def ^:private update-file-fn
  (delay
    (->> (sv/scan-ns 'app.rpc.commands.files-update)
         (some (fn [[f mdata]]
                 (when (= "update-file" (::sv/name mdata)) f))))))

(def ^:private schema:commit-agent-tool-changes
  [:map {:title "commit-agent-tool-changes"}
   [:token [:string {:max 5000}]]
   [:revn {:min 0} ::sm/int]
   [:vern {:min 0} ::sm/int]
   ;; the tool that made the changes, for the record of what the agent did
   [:tool {:optional true} [:string {:max 100}]]
   [:changes [:vector {:max 5000} cpc/schema:change]]
   ;; the changes that take them back, kept so the turn can be reverted
   [:undo-changes {:optional true} [:vector {:max 20000} cpc/schema:change]]])

(sv/defmethod ::commit-agent-tool-changes
  "Commits an engine tool's changes to the turn's file, as the turn's profile.
  It goes through the file-update command, so edit permission, validation and
  the notification to open workspaces are the ones every edit gets."
  {::rpc/auth false
   ::doc/added "2.20"
   ::sm/params schema:commit-agent-tool-changes}
  [cfg {:keys [token revn vern tool changes undo-changes]}]
  (let [{:keys [profile-id file-id turn-id conversation-id]} (verify-turn-token cfg token)]
    (db/tx-run! cfg
                (fn [cfg]
                  (@update-file-fn cfg {::rpc/profile-id profile-id
                                        :id file-id
                                        ;; a session of its own, so every open
                                        ;; workspace applies the change
                                        :session-id (uuid/next)
                                        :revn revn
                                        :vern vern
                                        :features cfeat/supported-features
                                        :changes changes})))
    ;; the revision the file is at now, so a workspace can tell when it has
    ;; caught up with this commit
    (let [revn (:revn (bfc/get-file cfg file-id :read-only? true :load-data? false))]
      ;; and the record of it: an edit the agent made, in which turn of which
      ;; conversation, with which tool
      (when (and (uuid? turn-id) (string? conversation-id))
        (db/insert! cfg :file-agent-change
                    {:file-id file-id
                     :profile-id profile-id
                     :conversation-id conversation-id
                     :turn-id turn-id
                     :tool (or tool "unknown")
                     :revn revn
                     :changes (blob/encode changes)
                     :undo-changes (some-> undo-changes blob/encode)}
                    {::db/return-keys false}))
      {:file-id file-id
       :revn revn})))

;; --- What the agent changed

(def ^:private sql:agent-changes
  "SELECT c.id, c.profile_id, c.conversation_id, c.turn_id, c.tool, c.revn,
          c.changes, c.created_at, c.reverted_at,
          (c.undo_changes IS NOT NULL) AS can_revert
     FROM file_agent_change AS c
    WHERE c.file_id = ?
      AND (?::uuid IS NULL OR c.turn_id = ?::uuid)
      AND (?::text IS NULL OR c.conversation_id = ?::text)
    ORDER BY c.created_at ASC
    LIMIT 2000")

(def ^:private schema:get-agent-changes
  [:and
   [:map {:title "get-agent-changes"}
    [:file-id ::sm/uuid]
    [:turn-id {:optional true} ::sm/uuid]
    [:conversation-id {:optional true} [:string {:max 100}]]
    ;; the changes themselves, not only what and when
    [:with-changes {:optional true} ::sm/boolean]]
   [::sm/contains-any #{:turn-id :conversation-id}]])

(sv/defmethod ::get-agent-changes
  "The commits the design agent made to a file in one turn, or in every turn
  of one conversation, oldest first. For whoever can read the file."
  {::doc/added "2.20"
   ::sm/params schema:get-agent-changes}
  [cfg {:keys [::rpc/profile-id file-id turn-id conversation-id with-changes]}]
  (files/check-read-permissions! cfg profile-id file-id)
  (->> (db/exec! cfg [sql:agent-changes file-id turn-id turn-id conversation-id conversation-id])
       (mapv (fn [{:keys [changes] :as row}]
               (let [changes (blob/decode changes)]
                 (cond-> (-> (dissoc row :changes)
                             (assoc :change-count (count changes))
                             ;; the shapes it touched, to find them on the canvas
                             (assoc :shape-ids (into [] (comp (keep :id) (distinct) (take 200)) changes)))
                   with-changes (assoc :changes changes)))))))


;; --- Taking a turn back
;;
;; An edit the agent makes on the server is not in anybody's undo history: it
;; reaches every open workspace as another collaborator's would. This is the
;; way back. A turn's commits each carry the changes that undo them; reverting
;; the turn applies those, newest commit first, as one change to the file.
;;
;; What somebody did afterwards to a shape the turn touched would be
;; overwritten, so that is looked for first and answered instead of reverting,
;; unless the caller says to go ahead. The file's change log is where it is
;; looked for, and that log only reaches back about an hour: for an older
;; turn the answer is that it cannot be told.

(def ^:private sql:turn-changes
  "SELECT c.id, c.conversation_id, c.tool, c.revn, c.changes, c.undo_changes
     FROM file_agent_change AS c
    WHERE c.file_id = ?
      AND c.turn_id = ?
      AND c.reverted_at IS NULL
    ORDER BY c.revn ASC, c.created_at ASC")

(def ^:private sql:later-file-changes
  "SELECT c.revn, c.changes
     FROM file_change AS c
    WHERE c.file_id = ?
      AND c.revn > ?
      AND c.changes IS NOT NULL
    ORDER BY c.revn ASC")

(defn- touched-shapes
  "The ids of the shapes a list of changes is about."
  [changes]
  (into #{}
        (mapcat (fn [{:keys [id shapes]}]
                  (cond-> (vec shapes)
                    (uuid? id) (conj id))))
        changes))

(defn- later-edits
  "What was done to the file after revision `revn` that touches `ids` →
  `{:shapes ids :unknown? bool}`. `:unknown?` says the change log no longer
  reaches back that far."
  [cfg file-id revn current-revn ids]
  (let [rows   (db/exec! cfg [sql:later-file-changes file-id revn])
        logged (into #{} (map :revn) rows)]
    {:shapes (into #{}
                   (comp (mapcat (comp touched-shapes blob/decode :changes))
                         (filter ids))
                   rows)
     ;; every revision after the turn has to be in the log, or one is missing
     :unknown? (not (every? logged (range (inc revn) (inc current-revn))))}))

(defn- shape-names
  [cfg file-id ids]
  (let [data (:data (load-whole-file cfg file-id))]
    (into []
          (comp (mapcat (comp vals :objects val))
                (filter #(contains? ids (:id %)))
                (map (fn [shape] {:id (:id shape) :name (:name shape)}))
                (take 20))
          (:pages-index data))))

(def ^:private schema:revert-agent-turn
  [:map {:title "revert-agent-turn"}
   [:file-id ::sm/uuid]
   [:turn-id ::sm/uuid]
   ;; revert even if somebody edited what the turn touched since
   [:force {:optional true} ::sm/boolean]])

(sv/defmethod ::revert-agent-turn
  "Takes back what the agent changed in one turn, as one change to the file.
  Answers `{:conflict true …}` and changes nothing when what the turn touched
  was edited afterwards, unless `force`. The revert is recorded as a turn of
  its own, so it can be reverted the same way."
  {::doc/added "2.20"
   ::sm/params schema:revert-agent-turn}
  [cfg {:keys [::rpc/profile-id file-id turn-id force]}]
  (files/check-edition-permissions! cfg profile-id file-id)
  (let [rows (db/exec! cfg [sql:turn-changes file-id turn-id])
        file (bfc/get-file cfg file-id :read-only? true :load-data? false)]
    (cond
      (empty? rows)
      (ex/raise :type :validation
                :code :nothing-to-revert
                :hint "this turn changed nothing, or was already reverted")

      (some (comp nil? :undo-changes) rows)
      (ex/raise :type :validation
                :code :turn-not-revertable
                :hint "this turn was recorded without the changes that undo it")

      :else
      (let [rows   (mapv #(-> % (update :changes blob/decode) (update :undo-changes blob/decode)) rows)
            ids    (touched-shapes (mapcat :changes rows))
            later  (later-edits cfg file-id (:revn (peek rows)) (:revn file) ids)]
        (if (and (not force) (or (seq (:shapes later)) (:unknown? later)))
          {:conflict true
           ;; the shapes edited since, or none named when it cannot be told
           :shapes (shape-names cfg file-id (:shapes later))
           :unknown (:unknown? later)}
          (let [;; newest commit first: each undoes on top of the ones after it
                undo (into [] (mapcat :undo-changes) (rseq rows))
                redo (into [] (mapcat :changes) rows)
                revert-turn (uuid/next)]
            (try
              (db/tx-run! cfg
                          (fn [cfg]
                            (@update-file-fn cfg {::rpc/profile-id profile-id
                                                  :id file-id
                                                  :session-id (uuid/next)
                                                  :revn (:revn file)
                                                  :vern (:vern file)
                                                  :features cfeat/supported-features
                                                  :changes undo})))
              (catch clojure.lang.ExceptionInfo cause
                (if (= :validation (:type (ex-data cause)))
                  (ex/raise :type :validation
                            :code :revert-does-not-apply
                            :hint (str "the file has changed too much since for this turn to be "
                                       "taken back cleanly; restore a version from the history instead")
                            :cause cause)
                  (throw cause))))
            (let [revn (:revn (bfc/get-file cfg file-id :read-only? true :load-data? false))]
              (db/update! cfg :file-agent-change
                          {:reverted-at (ct/now)}
                          {:file-id file-id :turn-id turn-id}
                          {::db/return-keys false})
              ;; the revert is a change like the others: recorded, and with
              ;; what undoes it, which is the turn itself
              (db/insert! cfg :file-agent-change
                          {:file-id file-id
                           :profile-id profile-id
                           :conversation-id (:conversation-id (first rows))
                           :turn-id revert-turn
                           :tool "revert_turn"
                           :revn revn
                           :changes (blob/encode undo)
                           :undo-changes (blob/encode redo)}
                          {::db/return-keys false})
              {:reverted (count rows)
               :turn-id revert-turn
               :revn revn})))))))
