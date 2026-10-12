;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-team-recipes-test
  "A team's own recipes: kept only when they are in the format, used over the
  built-in ones, handed to a turn on a file of that team, and saved from a
  board by the agent."
  (:require
   [app.common.agent.recipes :as cars]
   [app.common.features :as cfeat]
   [app.common.json :as json]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.agent-tools :as agent-tools]
   [app.rpc.commands.team-recipes :as team-recipes]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private chip
  {:recipe 1
   :name "pebble"
   :title "Pebble"
   :purpose "A small rounded label, to test with."
   :category "control"
   :tree {:type "board" :name "Pebble"
          :layout {:type "flex" :dir "row"}
          :tokens {:fill "color.surface-muted"}
          :children [{:type "text" :name "Label" :text "Pebble" :tokens {:fill "color.text"}}]}})

(defn- setup
  []
  (let [owner (th/create-profile* 1 {:is-active true})
        other (th/create-profile* 2 {:is-active true})
        file  (th/create-file* 1 {:profile-id (:id owner)
                                  :project-id (:default-project-id owner)})]
    {:owner owner
     :other other
     :team-id (:default-team-id owner)
     :file file
     :token (agent-tools/turn-token th/*system* (:id owner) (:id file))}))

(defn- save!
  [profile team-id recipe & {:as more}]
  (th/command! (merge {::th/type :save-team-recipe
                       ::rpc/profile-id (:id profile)
                       :team-id team-id
                       :content (if (string? recipe) recipe (json/encode recipe))}
                      more)))

(defn- listed
  [profile team-id]
  (:result (th/command! {::th/type :get-team-recipes
                         ::rpc/profile-id (:id profile)
                         :team-id team-id})))

(defn- hint
  [out]
  (:hint (ex-data (:error out))))

(t/deftest a-team-recipe-is-kept-listed-replaced-disabled-and-removed
  (let [{:keys [owner team-id]} (setup)]
    (t/is (empty? (listed owner team-id)))
    (let [out (save! owner team-id chip)]
      (t/is (nil? (:error out)))
      (t/is (= "pebble" (-> out :result :name)))
      (t/is (= "team" (-> out :result :origin)))
      (t/is (false? (-> out :result :overrides-built-in))))
    (t/testing "the recipe comes back as it went in"
      (t/is (= [chip] (mapv :recipe (listed owner team-id)))))
    (t/testing "saving the same name replaces it"
      (save! owner team-id (assoc chip :title "Pebble two"))
      (t/is (= ["Pebble two"] (mapv #(-> % :recipe :title) (listed owner team-id)))))
    (let [id (:id (first (listed owner team-id)))]
      (t/testing "disabled, it stays listed"
        (th/command! {::th/type :set-team-recipe-disabled ::rpc/profile-id (:id owner) :id id :disabled true})
        (t/is (= [true] (mapv :disabled (listed owner team-id)))))
      (t/testing "removed, it is gone"
        (th/command! {::th/type :delete-team-recipe ::rpc/profile-id (:id owner) :id id})
        (t/is (empty? (listed owner team-id)))))))

(t/deftest what-is-not-a-recipe-is-refused-and-the-problem-named
  (let [{:keys [owner team-id]} (setup)
        refused (fn [content] (let [out (save! owner team-id content)]
                                (t/is (= :invalid-recipe (:code (ex-data (:error out)))))
                                (hint out)))]
    (t/is (re-find #"not JSON for an object" (refused "alert(1)")))
    (t/is (re-find #"not JSON for an object" (refused "[1, 2]")))
    (t/is (re-find #"\"script\", which is not part of the format" (refused (assoc chip :script "alert(1)"))))
    (t/is (re-find #"tree.children\[0\] has \"onClick\"" (refused (assoc-in chip [:tree :children 0 :onClick] "x"))))
    (t/is (re-find #"names \"#ff0000\", which is not a role" (refused (assoc-in chip [:tree :tokens :fill] "#ff0000"))))
    (t/is (re-find #"there is no recipe of that name"
                   (refused (update-in chip [:tree :children] conj {:type "recipe" :use "nope"}))))
    (t/is (empty? (listed owner team-id)))
    (t/testing "a recipe may use a built-in one"
      (t/is (nil? (:error (save! owner team-id (update-in chip [:tree :children] conj
                                                          {:type "recipe" :use "badge"}))))))))

(t/deftest only-who-can-edit-the-team-changes-its-recipes
  (let [{:keys [owner other team-id]} (setup)]
    (t/is (some? (:error (save! other team-id chip))))
    (t/is (some? (:error (th/command! {::th/type :get-team-recipes ::rpc/profile-id (:id other) :team-id team-id}))))
    (save! owner team-id chip)
    (let [id (:id (first (listed owner team-id)))]
      (t/is (some? (:error (th/command! {::th/type :delete-team-recipe ::rpc/profile-id (:id other) :id id}))))
      (t/is (some? (:error (th/command! {::th/type :set-team-recipe-disabled ::rpc/profile-id (:id other)
                                         :id id :disabled true}))))
      (t/is (= 1 (count (listed owner team-id)))))))

(t/deftest a-team-recipe-stands-in-for-the-built-in-one-of-its-name
  (let [{:keys [owner team-id token]} (setup)
        mine  (assoc cars/button :purpose "The team's own button.")
        page  (fn [] (:result (th/command! {::th/type :get-agent-tool-page :token token})))]
    (t/is (= {} (:recipes (page))))
    (let [out (save! owner team-id mine :origin "imported" :origin-note "buttons.recipe.json")]
      (t/is (true? (-> out :result :overrides-built-in)))
      (t/is (= ["imported" "buttons.recipe.json"] ((juxt :origin :origin-note) (:result out)))))
    (t/testing "a turn on a file of the team is handed it"
      (t/is (= "The team's own button." (get-in (page) [:recipes "button" :purpose]))))
    (t/testing "disabled, the built-in one is back"
      (th/command! {::th/type :set-team-recipe-disabled ::rpc/profile-id (:id owner)
                    :id (:id (first (listed owner team-id))) :disabled true})
      (t/is (= {} (:recipes (page)))))))

(defn- add-objects!
  [profile file shapes]
  (let [page-id (first (get-in file [:data :pages]))
        out     (th/command! {::th/type :update-file
                              ::rpc/profile-id (:id profile)
                              :id (:id file)
                              :session-id (uuid/random)
                              :revn 0
                              :vern 0
                              :features cfeat/supported-features
                              :changes (mapv (fn [shape]
                                               {:type :add-obj
                                                :page-id page-id
                                                :id (:id shape)
                                                :parent-id (:parent-id shape)
                                                :frame-id (:frame-id shape)
                                                :components-v2 true
                                                :obj shape})
                                             shapes)})]
    (t/is (nil? (:error out)))))

(t/deftest the-agent-saves-a-board-as-a-team-recipe
  (let [{:keys [owner team-id file token]} (setup)
        board-id (uuid/random)
        text-id  (uuid/random)
        run      (fn [input]
                   (th/command! {::th/type :run-agent-tool :token token :tool "save_recipe"
                                 :input (json/encode input)}))
        content  (fn [out] (json/decode (get-in out [:result :content]) :key-fn keyword))]
    (add-objects! owner file
                  [(cts/setup-shape {:id board-id :type :frame :name "Note"
                                     :x 0 :y 0 :width 240 :height 80
                                     :parent-id uuid/zero :frame-id uuid/zero
                                     :layout :flex :layout-flex-dir :column
                                     :applied-tokens {:fill "color.surface"}})
                   (-> (cts/setup-shape {:id text-id :type :text :name "Words"
                                         :x 0 :y 0 :width 40 :height 20
                                         :parent-id board-id :frame-id board-id})
                       (update :content txt/change-text "Remember this" {}))])
    (t/testing "what is missing is named"
      (let [out (run {:shapeId (str board-id) :name "Sticky note"})]
        (t/is (true? (get-in out [:result :is-error])))
        (t/is (re-find #"save_recipe: recipe \"Sticky note\": needs a `name` in kebab-case"
                       (get-in out [:result :content])))))
    (let [out (run {:shapeId (str board-id) :name "sticky-note" :title "Sticky note"
                    :purpose "A short note stuck on a page." :category "container"
                    :params [{:name "words" :layer "Words"}]})]
      (t/is (false? (get-in out [:result :is-error])))
      (t/is (= "sticky-note" (:recipe (content out))))
      (t/is (= ["words"] (:params (content out)))))
    (let [[saved] (listed owner team-id)]
      (t/is (= "saved" (:origin saved)))
      (t/is (= (:name file) (:origin-note saved)))
      (t/is (= {:type "board" :name "Note" :width 240 :height 80
                :layout {:type "flex" :dir "column"}
                :tokens {:fill "color.surface"}
                :children [{:type "text" :name "Words" :text "{words}"}]}
               (-> saved :recipe :tree)))
      (t/is (= [{:name "words" :type "text" :default "Remember this"}] (-> saved :recipe :params))))))

(t/deftest the-measure-of-a-page-is-answered-to-whoever-holds-a-turn-token
  (let [{:keys [owner file token]} (setup)
        board-id (uuid/random)
        rect-id  (uuid/random)
        run      (fn [input]
                   (th/command! {::th/type :run-agent-tool :token token :tool "capability_usage"
                                 :input (json/encode input)}))]
    (add-objects! owner file
                  [(cts/setup-shape {:id board-id :type :frame :name "Row"
                                     :x 0 :y 0 :width 240 :height 80
                                     :parent-id uuid/zero :frame-id uuid/zero
                                     :layout :flex :layout-flex-dir :row
                                     :applied-tokens {:fill "color.surface"}})
                   (cts/setup-shape {:id rect-id :type :rect :name "Box"
                                     :x 0 :y 0 :width 10 :height 10
                                     :parent-id board-id :frame-id board-id})])
    (let [usage (json/decode (get-in (run {:names ["Row"]}) [:result :content]) :key-fn keyword)]
      (t/is (= 2 (-> usage :counts :layers)))
      (t/is (= 1.0 (-> usage :shares :autoLayout)))
      (t/is (= 0.5 (-> usage :shares :tokenBound)))
      (t/is (= 0.0 (-> usage :shares :components))))
    (t/is (re-find #"none of those boards" (get-in (run {:names ["Nope"]}) [:result :content])))))

;; --- The community source

(def ^:private index-uri "https://recipes.example.org/index.json")

(defn- with-community
  "Runs `f` with a community index at `index-uri` that serves `files`: url →
  text."
  [files f]
  (with-redefs [cf/get (let [orig cf/get]
                         (fn [k & more]
                           (if (= k :recipe-community-index-uri) index-uri (apply orig k more))))
                team-recipes/fetch-text! (fn [_ uri _] (get files (str uri)))]
    (f)))

(t/deftest a-community-recipe-is-copied-in-labelled-and-disabled
  (let [{:keys [owner other team-id token]} (setup)
        files {index-uri (json/encode {:recipes [{:name "pebble" :title "Pebble" :purpose "A small label."
                                                  :category "control" :author "Ada" :url "pebble.recipe.json"}
                                                 {:name "evil" :title "Evil" :url "evil.recipe.json"}
                                                 {:name "other-name" :title "Renamed" :url "pebble.recipe.json"}
                                                 {:title "No name"}]})
               "https://recipes.example.org/pebble.recipe.json" (json/encode chip)
               "https://recipes.example.org/evil.recipe.json" (json/encode (assoc chip :name "evil" :script "alert(1)"))}
        offered (fn [profile] (th/command! {::th/type :get-community-recipes ::rpc/profile-id (:id profile) :team-id team-id}))
        import! (fn [profile nm] (th/command! {::th/type :import-community-recipe ::rpc/profile-id (:id profile)
                                               :team-id team-id :name nm}))]
    (t/testing "with no index configured there is no community source"
      (t/is (= {:configured false :available false :source nil :recipes []} (:result (offered owner)))))
    (with-community files
      (fn []
        (t/testing "the index is listed, without what is not an entry"
          (let [result (:result (offered owner))]
            (t/is (true? (:configured result)))
            (t/is (= "recipes.example.org" (:source result)))
            (t/is (= ["pebble" "evil" "other-name"] (mapv :name (:recipes result))))
            (t/is (= {:name "pebble" :title "Pebble" :purpose "A small label." :category "control"
                      :author "Ada" :in-team false}
                     (first (:recipes result))))))
        (t/testing "only who can edit the team takes one"
          (t/is (some? (:error (import! other "pebble")))))
        (let [out (import! owner "pebble")]
          (t/is (nil? (:error out)))
          (t/is (= "community" (-> out :result :origin)))
          (t/is (= "recipes.example.org · Ada" (-> out :result :origin-note)))
          (t/is (true? (-> out :result :disabled)))
          (t/is (= chip (-> out :result :recipe))))
        (t/testing "disabled, a turn is not handed it; enabled, it is"
          (let [page (fn [] (:result (th/command! {::th/type :get-agent-tool-page :token token})))
                id   (:id (first (listed owner team-id)))]
            (t/is (= {} (:recipes (page))))
            (th/command! {::th/type :set-team-recipe-disabled ::rpc/profile-id (:id owner) :id id :disabled false})
            (t/is (= ["pebble"] (keys (:recipes (page)))))))
        (t/testing "the index then says the team has it"
          (t/is (true? (:in-team (first (:recipes (:result (offered owner))))))))
        (t/testing "a file with content outside the format is refused, and named"
          (let [out (import! owner "evil")]
            (t/is (= :invalid-recipe (:code (ex-data (:error out)))))
            (t/is (re-find #"\"script\", which is not part of the format" (hint out)))))
        (t/testing "a file that is not the recipe the index says is refused"
          (t/is (re-find #"the index calls it \"other-name\" and the file \"pebble\"" (hint (import! owner "other-name")))))
        (t/testing "a name the index does not have"
          (t/is (= :community-recipe-not-found (:code (ex-data (:error (import! owner "nope"))))))
          (t/is (= 1 (count (listed owner team-id)))))))))
