;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-agent-tools-test
  "Pins the server-side agent tools: a tool runs for the profile and file its
  turn token names, with that profile's permissions as they are now, and
  reads the stored file."
  (:require
   [app.common.features :as cfeat]
   [app.common.json :as json]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.agent-tools :as agent-tools]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- add-rect!
  [profile file name & {:as attrs}]
  (let [page-id  (first (get-in file [:data :pages]))
        shape-id (uuid/random)
        out      (th/command! {::th/type :update-file
                               ::rpc/profile-id (:id profile)
                               :id (:id file)
                               :session-id (uuid/random)
                               :revn 0
                               :vern 0
                               :features cfeat/supported-features
                               :changes [{:type :add-obj
                                          :page-id page-id
                                          :id shape-id
                                          :parent-id uuid/zero
                                          :frame-id uuid/zero
                                          :components-v2 true
                                          :obj (cts/setup-shape
                                                (merge {:id shape-id
                                                        :name name
                                                        :frame-id uuid/zero
                                                        :parent-id uuid/zero
                                                        :type :rect}
                                                       attrs))}]})]
    (t/is (nil? (:error out)))
    shape-id))

(defn- run-tool!
  [token tool & {:keys [input context]}]
  (th/command! (cond-> {::th/type :run-agent-tool
                        :token token
                        :tool tool}
                 input   (assoc :input (json/encode input))
                 context (assoc :context (json/encode context)))))

(defn- content
  [out]
  (json/decode (get-in out [:result :content]) :key-fn keyword))

(defn- setup
  []
  (let [owner (th/create-profile* 1 {:is-active true})
        file  (th/create-file* 1 {:profile-id (:id owner)
                                  :project-id (:default-project-id owner)})]
    {:owner owner
     :file file
     :token (agent-tools/turn-token th/*system* (:id owner) (:id file))}))

(t/deftest read-design-reads-the-stored-file
  (let [{:keys [owner file token]} (setup)
        shape-id (add-rect! owner file "hero-card")
        out      (run-tool! token "read_design")
        result   (content out)]
    (t/is (nil? (:error out)))
    (t/is (false? (get-in out [:result :is-error])))
    (t/is (= (:name file) (:file result)))
    (t/is (= ["hero-card"] (mapv :name (:shapes result))))
    (t/is (= (str shape-id) (:id (first (:shapes result)))))
    (t/is (= [true] (mapv :current (:pages result))))
    ;; nothing is selected and nothing is known about the workspace
    (t/is (= [] (:selection result)))
    (t/is (false? (:hasDesignDoc result)))
    (t/is (not (contains? result :skills)))))

(t/deftest read-design-takes-the-selection-and-extras-from-the-workspace
  (let [{:keys [owner file token]} (setup)
        shape-id (add-rect! owner file "hero-card")
        out      (run-tool! token "read_design"
                            :context {:selectedIds [(str shape-id) "not-a-uuid"]
                                      :extras {:openViolations 3 :ignored "x"}})
        result   (content out)]
    (t/is (= ["hero-card"] (mapv :name (:selection result))))
    (t/is (= 3 (:openViolations result)))
    (t/is (not (contains? result :ignored)))))

(t/deftest find-shapes-finds-by-name-and-names-a-bad-query
  (let [{:keys [owner file token]} (setup)]
    (add-rect! owner file "hero-card")

    (t/testing "a match"
      (let [result (content (run-tool! token "find_shapes" :input {:name "HERO"}))]
        (t/is (= 1 (:found result)))
        (t/is (= ["hero-card"] (mapv :name (:matches result))))))

    (t/testing "an empty query is a problem the model can act on, not a failure"
      (let [out (run-tool! token "find_shapes" :input {})]
        (t/is (nil? (:error out)))
        (t/is (true? (get-in out [:result :is-error])))
        (t/is (re-find #"pass a name and/or a type" (get-in out [:result :content])))))

    (t/testing "an unknown type is named"
      (let [out (run-tool! token "find_shapes" :input {:type "widget"})]
        (t/is (true? (get-in out [:result :is-error])))
        (t/is (re-find #"\"widget\" is not a shape type" (get-in out [:result :content])))))))

(t/deftest a-tool-runs-with-the-permissions-the-profile-has-now
  (let [{:keys [file]} (setup)
        stranger (th/create-profile* 2 {:is-active true})]

    (t/testing "a token for a profile that cannot read the file opens nothing"
      (let [token (agent-tools/turn-token th/*system* (:id stranger) (:id file))
            out   (run-tool! token "read_design")]
        (t/is (some? (:error out)))
        (t/is (th/ex-of-type? (:error out) :not-found))))))

(t/deftest a-bad-token-or-an-unknown-tool-is-refused
  (let [{:keys [token]} (setup)]
    (t/testing "a token that is not ours"
      (let [out (run-tool! "not-a-token" "read_design")]
        (t/is (th/ex-of-code? (:error out) :invalid-turn-token))))

    (t/testing "a tool the server does not run"
      (let [out (run-tool! token "delete_shape")]
        (t/is (th/ex-of-type? (:error out) :validation))))))

(defn- rect-change
  [page-id shape-id name]
  {:type :add-obj
   :page-id page-id
   :id shape-id
   :parent-id uuid/zero
   :frame-id uuid/zero
   :components-v2 true
   :obj (cts/setup-shape {:id shape-id
                          :name name
                          :frame-id uuid/zero
                          :parent-id uuid/zero
                          :type :rect})})

(t/deftest the-engine-tools-read-a-page-and-commit-to-it
  (let [{:keys [owner file token]} (setup)
        first-id (add-rect! owner file "hero-card")
        page     (th/command! {::th/type :get-agent-tool-page :token token})
        result   (:result page)]

    (t/testing "the page comes with what a commit needs"
      (t/is (nil? (:error page)))
      (t/is (= (:id file) (:file-id result)))
      (t/is (= (first (get-in file [:data :pages])) (:page-id result)))
      (t/is (= 1 (:revn result)))
      (t/is (= "hero-card" (get-in result [:data :pages-index (:page-id result) :objects first-id :name])))
      (t/is (= {} (:libraries result)))
      (t/is (set? (:allowed-colors result))))

    (t/testing "a commit lands in the file, as the profile of the turn"
      (let [shape-id (uuid/random)
            out      (th/command! {::th/type :commit-agent-tool-changes
                                   :token token
                                   :revn (:revn result)
                                   :vern (:vern result)
                                   :changes [(rect-change (:page-id result) shape-id "from-the-engine")]})
            names    (->> (content (run-tool! token "read_design")) :shapes (map :name) set)]
        (t/is (nil? (:error out)))
        (t/is (= #{"hero-card" "from-the-engine"} names))))))

(t/deftest the-engine-tools-need-edit-permission
  (let [{:keys [file]} (setup)
        stranger (th/create-profile* 2 {:is-active true})
        token    (agent-tools/turn-token th/*system* (:id stranger) (:id file))
        page-id  (first (get-in file [:data :pages]))]

    (t/testing "a profile that cannot edit the file gets no page"
      (let [out (th/command! {::th/type :get-agent-tool-page :token token})]
        (t/is (th/ex-of-type? (:error out) :not-found))))

    (t/testing "and cannot commit to it"
      (let [out (th/command! {::th/type :commit-agent-tool-changes
                              :token token
                              :revn 0
                              :vern 0
                              :changes [(rect-change page-id (uuid/random) "intruder")]})]
        (t/is (th/ex-of-type? (:error out) :not-found))))

    (t/testing "a made-up token commits nothing"
      (let [out (th/command! {::th/type :commit-agent-tool-changes
                              :token "not-a-token"
                              :revn 0
                              :vern 0
                              :changes []})]
        (t/is (th/ex-of-code? (:error out) :invalid-turn-token))))))

(t/deftest comments-and-versions-are-written-as-the-turn-profile
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        ;; a board under the comment: it is the frame the thread hangs from
        board   (add-rect! profile file "board" :type :frame :x 0 :y 0 :width 100 :height 100)
        token   (agent-tools/turn-token th/*system* (:id profile) (:id file))]

    (t/testing "a comment lands on the page in view, and can be listed"
      (t/is (re-find #"content is required"
                     (get-in (run-tool! token "leave_comment" :input {:content "  "}) [:result :content])))
      (let [out (run-tool! token "leave_comment" :input {:content "agent: check this" :x 10 :y 20})]
        (t/is (nil? (:error out)))
        (t/is (false? (get-in out [:result :is-error])))
        (t/is (= [board] (map :frame-id (th/db-query :comment-thread {:file-id (:id file)})))))
      (let [listed (content (run-tool! token "list_comments"))]
        (t/is (= ["agent: check this"] (mapv :content (:comments listed))))
        (t/is (= [10 20] (mapv #(int (get (first (:comments listed)) %)) [:x :y])))))

    (t/testing "a version is saved under an agent label"
      (t/is (re-find #"label is required"
                     (get-in (run-tool! token "save_version" :input {}) [:result :content])))
      (let [saved (content (run-tool! token "save_version" :input {:label "before surgery"}))]
        (t/is (= "agent: before surgery" (:label saved)))
        (t/is (= ["agent: before surgery"]
                 (->> (th/db-query :file-change {:file-id (:id file)})
                      (keep :label)
                      (filter #(re-find #"^agent:" %)))))))))

(t/deftest audit-file-checks-the-rules-the-workspace-says-are-active
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        _       (add-rect! profile file "Rectangle 3")
        token   (agent-tools/turn-token th/*system* (:id profile) (:id file))]
    ;; no active rule, nothing to report
    (t/is (= 0 (:count (content (run-tool! token "audit_file")))))
    (let [audit (content (run-tool! token "audit_file" :context {:enforcedRules ["layer-naming"]}))]
      (t/is (= 1 (:count audit)))
      (t/is (= ["layer-naming"] (mapv :rule (:violations audit))))
      (t/is (= "Rectangle 3" (-> audit :violations first :shapeName))))
    (t/testing "a web tool refuses a url that is not one, without touching the file"
      (t/is (re-find #"get_page_meta: url must be an absolute"
                     (get-in (run-tool! token "get_page_meta" :input {:url "nope"}) [:result :content]))))))

(t/deftest media-is-handed-over-only-for-a-valid-turn
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        token   (agent-tools/turn-token th/*system* (:id profile) (:id file))]
    ;; an image that does not exist is not an error: the render goes on without
    (t/is (= {:data nil}
             (:result (th/command! {::th/type :get-agent-tool-media :token token :id (uuid/random)}))))
    (t/is (= :invalid-turn-token
             (-> (th/command! {::th/type :get-agent-tool-media :token "nope" :id (uuid/random)})
                 :error ex-data :code)))))

(t/deftest a-mandatory-rule-is-active-whatever-the-caller-says
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        token   (agent-tools/turn-token th/*system* (:id profile) (:id file))
        rules   (fn [context]
                  (-> (th/command! (cond-> {::th/type :get-agent-tool-page :token token}
                                     context (assoc :context (json/encode context))))
                      :result :enforced-rules set))]
    ;; the workspace's own choice is taken as given
    (t/is (= #{} (rules nil)))
    (t/is (= #{"layer-naming"} (rules {:enforcedRules ["layer-naming"]})))

    (th/db-insert! :design-skill {:team-id (:default-team-id profile)
                                  :name "token-only-colors"
                                  :kind "rule"
                                  :enforcement "enforced"
                                  :is-mandatory true})
    (th/db-insert! :design-skill {:team-id (:default-team-id profile)
                                  :name "layer-naming"
                                  :kind "rule"
                                  :enforcement "advisory"
                                  :is-mandatory false})
    ;; a mandatory rule of the team is active even when the caller leaves it out
    (t/is (= #{"token-only-colors"} (rules nil)))
    (t/is (= #{"token-only-colors"} (rules {:enforcedRules []})))
    (t/is (= #{"token-only-colors" "layer-naming"} (rules {:enforcedRules ["layer-naming"]})))))

(t/deftest what-a-turn-changes-is-recorded-and-can-be-listed
  (let [profile  (th/create-profile* 1 {:is-active true})
        other    (th/create-profile* 2 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared false})
        page-id  (first (get-in file [:data :pages]))
        turn-id  (uuid/random)
        token    (agent-tools/turn-token th/*system* (:id profile) (:id file)
                                         {:turn-id turn-id :conversation-id "chat-1"})
        commit!  (fn [token tool name]
                   (let [id   (uuid/random)
                         page (:result (th/command! {::th/type :get-agent-tool-page :token token}))]
                     (th/command! {::th/type :commit-agent-tool-changes
                                   :token token
                                   :revn (:revn page)
                                   :vern (:vern page)
                                   :tool tool
                                   :changes [{:type :add-obj
                                              :page-id page-id
                                              :id id
                                              :parent-id uuid/zero
                                              :frame-id uuid/zero
                                              :obj (cts/setup-shape {:id id :name name :type :rect
                                                                     :frame-id uuid/zero :parent-id uuid/zero})}]})
                     id))
        listed   (fn [profile & {:as params}]
                   (th/command! (merge {::th/type :get-agent-changes
                                        ::rpc/profile-id (:id profile)
                                        :file-id (:id file)}
                                       params)))
        a        (commit! token "create_shape" "a")
        b        (commit! token "create_shape" "b")
        ;; a later turn of the same conversation
        token-2  (agent-tools/turn-token th/*system* (:id profile) (:id file)
                                         {:turn-id (uuid/random) :conversation-id "chat-1"})
        c        (commit! token-2 "create_line" "c")
        ;; a token that names no turn commits, and leaves no record
        _        (commit! (agent-tools/turn-token th/*system* (:id profile) (:id file)) "create_shape" "d")]

    (t/testing "a turn's changes, in order, with the tool and the shapes"
      (let [rows (:result (listed profile :turn-id turn-id))]
        (t/is (= ["create_shape" "create_shape"] (mapv :tool rows)))
        (t/is (= [[a] [b]] (mapv :shape-ids rows)))
        (t/is (= #{"chat-1"} (set (map :conversation-id rows))))
        (t/is (= #{(:id profile)} (set (map :profile-id rows))))
        (t/is (apply < (map :revn rows)))
        (t/is (every? #(nil? (:changes %)) rows))))

    (t/testing "a conversation's changes span its turns"
      (let [rows (:result (listed profile :conversation-id "chat-1" :with-changes true))]
        (t/is (= [[a] [b] [c]] (mapv :shape-ids rows)))
        (t/is (= :add-obj (-> rows last :changes first :type)))))

    (t/testing "only for someone who can read the file"
      (t/is (= :not-found (-> (listed other :turn-id turn-id) :error ex-data :type))))))

(t/deftest a-team-font-is-handed-over-only-to-a-turn-on-that-team
  (let [profile (th/create-profile* 1 {:is-active true})
        other   (th/create-profile* 2 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        theirs  (th/create-file* 2 {:profile-id (:id other)
                                    :project-id (:default-project-id other)
                                    :is-shared false})
        storage (:app.storage/storage th/*system*)
        object  (sto/put-object! storage {::sto/content (sto/content (.getBytes "not really a font" "UTF-8"))
                                          :content-type "font/ttf"
                                          :bucket "team-font-variant"})
        font-id (uuid/random)
        _       (th/db-insert! :team-font-variant {:id (uuid/random)
                                                   :team-id (:default-team-id profile)
                                                   :profile-id (:id profile)
                                                   :font-id font-id
                                                   :font-family "House Sans"
                                                   :font-weight 400
                                                   :font-style "normal"
                                                   :ttf-file-id (:id object)})
        token   (agent-tools/turn-token th/*system* (:id profile) (:id file))
        font!   (fn [token id]
                  (:result (th/command! {::th/type :get-agent-tool-font :token token :id id})))]

    (t/testing "the page says which fonts the team has, with the file to ask for"
      (t/is (= [{:font-id font-id :font-family "House Sans" :font-weight 400 :font-style "normal" :ttf-file-id (:id object)}]
               (-> (th/command! {::th/type :get-agent-tool-page :token token}) :result :team-fonts))))

    (t/testing "the font's bytes, for a turn on a file of that team"
      (t/is (= "not really a font"
               (String. (.decode (java.util.Base64/getDecoder) ^String (:data (font! token (:id object)))) "UTF-8"))))

    (t/testing "not for a turn on another team's file, nor for a file that is not a font"
      (t/is (= {:data nil}
               (font! (agent-tools/turn-token th/*system* (:id other) (:id theirs)) (:id object))))
      (t/is (= {:data nil} (font! token (uuid/random)))))))

(t/deftest the-service-asks-whether-a-file-has-a-page
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        page-id (first (get-in file [:data :pages]))
        token   (agent-tools/turn-token th/*system* (:id profile) (:id file))
        check   (fn [input] (:result (run-tool! token "check_page" :input input)))]
    (t/is (= {:pageId (str page-id) :name "Page 1"}
             (json/decode (:content (check {:pageId (str page-id)})) :key-fn keyword)))
    (t/is (re-find #"no page with id .* in this file" (:content (check {:pageId (str (uuid/random))}))))
    (t/is (re-find #"pageId is required" (:content (check {}))))
    ;; and read_design says which page the tools are on
    (let [design (content (run-tool! token "read_design"))]
      (t/is (= "Page 1" (:page design)))
      (t/is (= [{:id (str page-id) :name "Page 1" :current true}] (:pages design))))))

;; --- Taking a turn back

(defn- shape-change
  [page-id id name]
  {:type :add-obj :page-id page-id :id id :parent-id uuid/zero :frame-id uuid/zero
   :obj (cts/setup-shape {:id id :name name :type :rect :frame-id uuid/zero :parent-id uuid/zero})})

(defn- rename-change
  [page-id id name]
  {:type :mod-obj :page-id page-id :id id
   :operations [{:type :set :attr :name :val name}]})

(t/deftest a-turn-can-be-taken-back-and-the-revert-too
  (let [profile (th/create-profile* 1 {:is-active true})
        other   (th/create-profile* 2 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        page-id (first (get-in file [:data :pages]))
        kept    (add-rect! profile file "kept")
        turn-id (uuid/random)
        token   (agent-tools/turn-token th/*system* (:id profile) (:id file)
                                        {:turn-id turn-id :conversation-id "chat-1"})
        commit! (fn [tool changes undo]
                  (let [page (:result (th/command! {::th/type :get-agent-tool-page :token token}))
                        out  (th/command! (cond-> {::th/type :commit-agent-tool-changes
                                                   :token token
                                                   :revn (:revn page)
                                                   :vern (:vern page)
                                                   :tool tool
                                                   :changes changes}
                                            undo (assoc :undo-changes undo)))]
                    (t/is (nil? (:error out)))))
        names   (fn []
                  (let [page (:result (th/command! {::th/type :get-agent-tool-page :token token}))]
                    (->> (get-in page [:data :pages-index page-id :objects])
                         (vals)
                         (keep :name)
                         (remove #{"Root Frame"})
                         (set))))
        revert! (fn [profile turn & {:as opts}]
                  (th/command! (merge {::th/type :revert-agent-turn
                                       ::rpc/profile-id (:id profile)
                                       :file-id (:id file)
                                       :turn-id turn}
                                      opts)))
        a       (uuid/random)
        b       (uuid/random)]

    ;; the turn: two shapes, then the first renamed
    (commit! "create_shape" [(shape-change page-id a "a")] [{:type :del-obj :page-id page-id :id a}])
    (commit! "create_shape" [(shape-change page-id b "b")] [{:type :del-obj :page-id page-id :id b}])
    (commit! "modify_shape" [(rename-change page-id a "a2")] [(rename-change page-id a "a")])
    (t/is (= #{"kept" "a2" "b"} (names)))

    (t/testing "the listing says the turn can be reverted"
      (let [rows (:result (th/command! {::th/type :get-agent-changes ::rpc/profile-id (:id profile)
                                        :file-id (:id file) :turn-id turn-id}))]
        (t/is (= [true true true] (mapv :can-revert rows)))
        (t/is (every? (comp nil? :reverted-at) rows))))

    (t/testing "only by someone who can edit the file"
      (t/is (= :not-found (-> (revert! other turn-id) :error ex-data :type))))

    (t/testing "reverting leaves what the turn did not touch"
      (let [out (revert! profile turn-id)]
        (t/is (nil? (:error out)))
        (t/is (= 3 (get-in out [:result :reverted])))
        (t/is (= #{"kept"} (names)))

        (t/testing "it cannot be reverted twice"
          (t/is (= :nothing-to-revert (-> (revert! profile turn-id) :error ex-data :code))))

        (t/testing "the revert is a recorded change that can itself be taken back"
          (let [revert-turn (get-in out [:result :turn-id])
                rows        (:result (th/command! {::th/type :get-agent-changes ::rpc/profile-id (:id profile)
                                                   :file-id (:id file) :turn-id revert-turn}))]
            (t/is (= ["revert_turn"] (mapv :tool rows)))
            (t/is (nil? (:error (revert! profile revert-turn))))
            (t/is (= #{"kept" "a2" "b"} (names)))))))))

(t/deftest a-revert-asks-before-overwriting-a-later-edit
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        page-id (first (get-in file [:data :pages]))
        turn-id (uuid/random)
        token   (agent-tools/turn-token th/*system* (:id profile) (:id file)
                                        {:turn-id turn-id :conversation-id "chat-1"})
        page    (fn [] (:result (th/command! {::th/type :get-agent-tool-page :token token})))
        a       (uuid/random)
        b       (uuid/random)
        _       (let [p (page)]
                  (th/command! {::th/type :commit-agent-tool-changes :token token
                                :revn (:revn p) :vern (:vern p) :tool "create_shape"
                                :changes [(shape-change page-id a "a") (shape-change page-id b "b")]
                                :undo-changes [{:type :del-obj :page-id page-id :id b}
                                               {:type :del-obj :page-id page-id :id a}]}))
        ;; then the user renames one of them
        _       (let [p (page)]
                  (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id profile)
                                :id (:id file)
                                :session-id (uuid/random)
                                :revn (:revn p)
                                :vern (:vern p)
                                :features cfeat/supported-features
                                :changes [(rename-change page-id a "mine now")]}))
        revert! (fn [& {:as opts}]
                  (th/command! (merge {::th/type :revert-agent-turn
                                       ::rpc/profile-id (:id profile)
                                       :file-id (:id file)
                                       :turn-id turn-id}
                                      opts)))
        names   (fn [] (->> (get-in (page) [:data :pages-index page-id :objects]) vals (keep :name) set))]

    (t/testing "it says which shape was edited since, and changes nothing"
      (let [out (:result (revert!))]
        (t/is (true? (:conflict out)))
        (t/is (= [{:id a :name "mine now"}] (:shapes out)))
        (t/is (false? (:unknown out)))
        (t/is (contains? (names) "mine now"))))

    (t/testing "told to go ahead, it reverts"
      (let [out (revert! :force true)]
        (t/is (nil? (:error out)))
        (t/is (= #{"Root Frame"} (names)))))))

;; --- Shared libraries

(t/deftest the-teams-shared-libraries-are-listed-and-linked-only-when-the-user-agreed
  (let [{:keys [owner file token]} (setup)
        library (th/create-file* 2 {:profile-id (:id owner)
                                    :project-id (:default-project-id owner)
                                    :is-shared true})
        other   (th/create-profile* 2 {:is-active true})
        foreign (th/create-file* 3 {:profile-id (:id other)
                                    :project-id (:default-project-id other)
                                    :is-shared true})
        listed  (fn [] (content (run-tool! token "list_shared_libraries")))
        rels    (fn [] (th/db-query :file-library-rel {:file-id (:id file)}))]
    (t/testing "listed: the team's, not another team's, and none linked yet"
      (let [out (listed)]
        (t/is (= [(str (:id library))] (mapv :fileId (:libraries out))))
        (t/is (= [false] (mapv :linked (:libraries out))))
        (t/is (= 0 (-> out :libraries first :components)))
        (t/is (= 0 (-> out :libraries first :roleTokens)))
        (t/is (pos? (-> out :libraries first :roles)))
        (t/is (re-find #"only after the user says yes" (:note out)))))
    (t/testing "without the user's yes nothing is linked"
      (let [out (run-tool! token "link_library" :input {:fileId (str (:id library))})]
        (t/is (true? (get-in out [:result :is-error])))
        (t/is (re-find #"nothing was linked" (get-in out [:result :content])))
        (t/is (empty? (rels)))))
    (t/testing "another team's library is not linked even with it"
      (let [out (run-tool! token "link_library" :input {:fileId (str (:id foreign)) :userAgreed true})]
        (t/is (or (some? (:error out)) (true? (get-in out [:result :is-error]))))
        (t/is (empty? (rels)))))
    (t/testing "with it, it is linked, and listed as linked"
      (let [out (content (run-tool! token "link_library" :input {:fileId (str (:id library)) :userAgreed true}))]
        (t/is (true? (:linked out)))
        (t/is (= [(:id library)] (mapv :library-file-id (rels))))
        (t/is (= [true] (mapv :linked (:libraries (listed)))))
        (t/is (true? (:already (content (run-tool! token "link_library" :input {:fileId (str (:id library)) :userAgreed true})))))))))

(t/deftest a-shared-library-is-read-once-per-revision
  (let [{:keys [owner token]} (setup)
        library (th/create-file* 2 {:profile-id (:id owner)
                                    :project-id (:default-project-id owner)
                                    :is-shared true})
        loads   (atom 0)
        load    @#'agent-tools/load-whole-file
        listed  (fn [] (content (run-tool! token "list_shared_libraries")))]
    ;; the test files' ids repeat from test to test
    (reset! @#'agent-tools/library-contents-cache {})
    (with-redefs [agent-tools/load-whole-file (fn [cfg id]
                                                (when (= id (:id library)) (swap! loads inc))
                                                (load cfg id))]
      (t/testing "asked twice, the library is read once"
        (listed)
        (listed)
        (t/is (= 1 @loads)))
      (t/testing "once it changes, it is read again"
        (db/update! th/*pool* :file {:revn 7} {:id (:id library)})
        (t/is (= [(str (:id library))] (mapv :fileId (:libraries (listed)))))
        (t/is (= 2 @loads))))))

(t/deftest an-old-record-of-the-agents-changes-is-removed
  (let [{:keys [file]} (setup)
        record! (fn [turn-id days-ago]
                  (db/insert! th/*pool* :file-agent-change
                              {:file-id (:id file)
                               :conversation-id "c1"
                               :turn-id turn-id
                               :tool "create_shape"
                               :revn 1
                               :changes (blob/encode [])
                               :created-at (ct/in-past {:days days-ago})}))
        count!  (fn [] (count (th/db-query :file-agent-change {:file-id (:id file)})))
        old     (uuid/next)
        recent  (uuid/next)
        across  (uuid/next)]
    (record! old 40)
    (record! recent 2)
    ;; a turn with a commit on each side of the cut
    (record! across 31)
    (record! across 29)
    (t/is (= 4 (count!)))
    (t/testing "past the default 30 days a turn goes; a recent one, and one still in use, stay whole"
      (t/is (= 1 (th/run-task! :agent-changes-gc {})))
      (t/is (= 3 (count!)))
      (t/is (= 2 (count (th/db-query :file-agent-change {:turn-id across})))))
    (t/testing "the age is the instance's to set"
      (t/is (= 3 (th/run-task! :agent-changes-gc {:max-age (ct/duration {:days 1})})))
      (t/is (= 0 (count!))))))

(t/deftest a-commit-is-recorded-at-the-revision-it-made
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        page-id (first (get-in file [:data :pages]))
        turn-id (uuid/random)
        token   (agent-tools/turn-token th/*system* (:id profile) (:id file)
                                        {:turn-id turn-id :conversation-id "chat-1"})
        page    (:result (th/command! {::th/type :get-agent-tool-page :token token}))
        out     (th/command! {::th/type :commit-agent-tool-changes
                              :token token
                              :revn (:revn page)
                              :vern (:vern page)
                              :tool "create_shape"
                              :changes [(rect-change page-id (uuid/random) "from-the-agent")]})
        made    (-> out :result :revn)]
    (t/is (nil? (:error out)))
    (t/is (= (inc (:revn page)) made))
    ;; somebody edits the file afterwards
    (add-rect! profile file "theirs")
    (let [rows (:result (th/command! {::th/type :get-agent-changes ::rpc/profile-id (:id profile)
                                      :file-id (:id file) :turn-id turn-id}))]
      (t/is (= [made] (mapv :revn rows))))))

(t/deftest a-commit-and-its-record-land-together-or-not-at-all
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        page-id (first (get-in file [:data :pages]))
        token   (agent-tools/turn-token th/*system* (:id profile) (:id file)
                                        {:turn-id (uuid/random) :conversation-id "chat-1"})
        page    (:result (th/command! {::th/type :get-agent-tool-page :token token}))
        insert! db/insert!]
    ;; the record cannot be written
    (with-redefs [db/insert! (fn [conn table & args]
                               (if (= :file-agent-change table)
                                 (throw (ex-info "record refused" {:type :internal}))
                                 (apply insert! conn table args)))]
      (let [out (th/command! {::th/type :commit-agent-tool-changes
                              :token token
                              :revn (:revn page)
                              :vern (:vern page)
                              :tool "create_shape"
                              :changes [(rect-change page-id (uuid/random) "unrecorded")]})]
        (t/is (some? (:error out)))))
    ;; so the change did not land either
    (let [after (:result (th/command! {::th/type :get-agent-tool-page :token token}))]
      (t/is (= (:revn page) (:revn after)))
      (t/is (not-any? #(= "unrecorded" (:name %))
                      (vals (get-in after [:data :pages-index page-id :objects])))))))
