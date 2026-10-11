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
  [profile file name]
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
                                                {:id shape-id
                                                 :name name
                                                 :frame-id uuid/zero
                                                 :parent-id uuid/zero
                                                 :type :rect})}]})]
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
