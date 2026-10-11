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
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.agent-tools :as agent-tools]
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
