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
   [app.common.agent.tools :as cat]
   [app.common.agent.write :as caw]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.changes :as cpc]
   [app.common.geom.point :as gpt]
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.shape-tree :as ctst]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as feat.fdata]
   [app.features.file-snapshots :as fsnap]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.storage :as sto]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

(def server-tools
  "The tools the backend runs itself. The agent service asks a connected
  workspace to run every other tool."
  #{"read_design" "find_shapes" "audit_file" "get_page_meta"
    "leave_comment" "list_comments" "save_version"})

(def ^:private fileless-tools
  #{"get_page_meta" "fetch_web_page" "turn_profile"})

(def ^:private service-tools
  "What the agent service asks for on its own account, to build a tool it
  runs itself. Not offered to the model."
  #{"fetch_web_page" "turn_profile"})

(def engine-tools
  "The tools that change a file. They need the render engine to lay things
  out, which the backend cannot run, so they run in the exporter: it reads the
  file and commits its changes through the two commands at the end of this
  namespace, with the same turn token."
  ;; and the one that shows the model a web page: the exporter's browser
  (conj (set (keys cat/tools)) "screenshot_page"))

;; A tool result is capped like the workspace caps it: an oversized result is
;; replaced by a small, valid object saying so, never cut mid-token.
(def ^:private max-result-chars 20000)

;; --- Turn token

(def ^:private turn-token-iss :agent-turn)

(defn turn-token
  "A claim that `profile-id` started an agent turn on `file-id`. Outlives the
  longest turn we expect; a tool call after that fails and the turn ends."
  [cfg profile-id file-id]
  (tokens/generate cfg {:iss turn-token-iss
                        :exp (ct/in-future {:hours 2})
                        :profile-id profile-id
                        :file-id file-id}))

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
  [_ {:keys [data page-id]} _ {:keys [enforcedRules]}]
  (let [enforced (into #{} (filter string?) enforcedRules)]
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
                    (mapv #(select-keys % [:font-id :font-family :font-weight :font-style])))))))

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
        media  (db/get* pool :file-media-object {:id id} {::db/remove-deleted false})
        object (when (and (some? media)
                          (:can-read (perms/get-file-read-permissions pool profile-id (:file-id media) nil)))
                 (sto/get-object storage (:media-id media)))]
    {:data (when (and (some? object) (<= (:size object 0) max-media-bytes))
             (.encodeToString (java.util.Base64/getEncoder)
                              ^bytes (sto/get-object-bytes storage object)))}))

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
   [:changes [:vector {:max 5000} cpc/schema:change]]])

(sv/defmethod ::commit-agent-tool-changes
  "Commits an engine tool's changes to the turn's file, as the turn's profile.
  It goes through the file-update command, so edit permission, validation and
  the notification to open workspaces are the ones every edit gets."
  {::rpc/auth false
   ::doc/added "2.20"
   ::sm/params schema:commit-agent-tool-changes}
  [cfg {:keys [token revn vern changes]}]
  (let [{:keys [profile-id file-id]} (verify-turn-token cfg token)]
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
    {:file-id file-id
     :revn (:revn (bfc/get-file cfg file-id :read-only? true :load-data? false))}))
