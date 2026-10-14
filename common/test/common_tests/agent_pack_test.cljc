;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.agent-pack-test
  (:require
   #?(:clj [app.common.json :as json])
   [app.common.agent.bind :as cab]
   [app.common.agent.pack :as cak]
   [app.common.agent.pack-make :as cakm]
   [app.common.agent.packs :as caks]
   [app.common.agent.recipe :as car]
   [app.common.agent.tools :as cat]
   [app.common.files.changes :as cfc]
   [app.common.schema :as sm]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private pack caks/neutral)

(defn- problem
  [f & args]
  (cak/problem (apply f pack args)))

(t/deftest the-starter-packs-are-valid-and-fill-every-role
  (t/is (= ["neutral" "bold" "soft"] (mapv :name caks/all)))
  (doseq [pack caks/all]
    (t/testing (:name pack)
      (t/is (nil? (cak/problem pack)))
      ;; a light and a dark theme, each with a value for every role
      (t/is (= ["Light" "Dark"] (mapv :name (:themes pack))))
      (doseq [theme (:themes pack)]
        (t/is (= (set (keys car/roles)) (set (keys (cak/theme-values pack theme))))))
      ;; every pair of text and ground can be read, in both
      (t/is (= [] (cak/contrast-problems pack))))))

(t/deftest the-starter-packs-differ-where-it-shows
  (let [light (fn [pack] (cak/theme-values pack (first (:themes pack))))]
    (doseq [role ["color.primary" "radius.md" "font-family.heading" "shadow.md"]]
      (t/is (= 3 (count (into #{} (map #(get (light %) role)) caks/all))) role))))

(t/deftest a-malformed-pack-is-told-what-is-wrong
  (t/is (re-find #"a pack is a map" (cak/problem [])))
  (t/is (re-find #"needs `pack`: 1" (problem dissoc :pack)))
  (t/is (re-find #"needs a `name` in kebab-case" (problem assoc :name "My Pack")))
  (t/is (re-find #"needs a `purpose`" (problem dissoc :purpose)))
  (t/is (re-find #"the pack has \"components\", which is not part of the format" (problem assoc :components [])))
  (t/is (re-find #"needs `sets`" (problem assoc :sets [])))
  (t/is (re-find #"has two sets called \"core\"" (problem update :sets conj (first (:sets pack)))))
  (t/is (re-find #"sets\[0\] \(core\) has no tokens" (problem assoc-in [:sets 0 :tokens] {})))
  (t/testing "values"
    (t/is (re-find #"modes/light.color.primary is not a color"
                   (problem assoc-in [:sets 1 :tokens "color.primary"] "blue")))
    (t/is (re-find #"core.spacing.md is not a number"
                   (problem assoc-in [:sets 0 :tokens "spacing.md"] "wide")))
    (t/is (re-find #"modes/light.shadow.md is not a shadow"
                   (problem assoc-in [:sets 1 :tokens "shadow.md"] "0 4px 12px black")))
    (t/is (re-find #"core.font-family.body is not a font family"
                   (problem assoc-in [:sets 0 :tokens "font-family.body"] 12)))
    (t/is (re-find #"core.color.primary is a role, so it is written as its value alone"
                   (problem assoc-in [:sets 0 :tokens "color.primary"] {:type "color" :value "#000000"}))))
  (t/testing "tokens that are not roles say their type"
    (t/is (re-find #"core.blue.600 is not a role, so it says its type"
                   (problem assoc-in [:sets 0 :tokens "blue.600"] "#2563eb")))
    (t/is (re-find #"core.blue.600 has the type \"hue\""
                   (problem assoc-in [:sets 0 :tokens "blue.600"] {:type "hue" :value "#2563eb"})))
    (t/is (nil? (-> pack
                    (assoc-in [:sets 0 :tokens "blue.600"] {:type "color" :value "#2563eb"})
                    (assoc-in [:sets 1 :tokens "color.primary"] "{blue.600}")
                    (cak/problem)))))
  (t/testing "themes"
    (t/is (re-find #"needs `themes`" (problem assoc :themes [])))
    (t/is (re-find #"theme \"Light\" turns on the set \"modes/sepia\", which the pack does not have"
                   (problem assoc-in [:themes 0 :sets] ["core" "modes/sepia"])))
    (t/is (re-find #"theme \"Dark\" leaves 1 role\(s\) without a value: color.info"
                   (problem update-in [:sets 2 :tokens] dissoc "color.info")))
    (t/is (re-find #"in theme \"Light\", \"color.primary\" refers to \"blue.600\", which it does not hold"
                   (problem assoc-in [:sets 1 :tokens "color.primary"] "{blue.600}")))
    (t/is (re-find #"refers back to itself"
                   (problem update-in [:sets 1 :tokens] assoc "color.primary" "{color.info}" "color.info" "{color.primary}")))
    (t/is (re-find #"has the set \"extra\", which no theme turns on"
                   (problem update :sets conj {:name "extra" :tokens {"spacing.md" 10}})))
    (t/is (re-find #"has the default \"Sepia\", which is not one of its themes: Light, Dark"
                   (problem assoc :default "Sepia")))))

(t/deftest a-pack-is-data-only
  (t/is (re-find #"a pack is data only" (problem assoc :purpose (fn [] "x"))))
  (t/is (re-find #"a pack is data only" (problem assoc-in [:sets 0 :tokens "spacing.md"] #?(:clj (Object.) :cljs (js/Date.)))))
  ;; as it arrives from JSON: keyword keys, names of tokens among them
  (let [json (-> pack
                 (update :sets (fn [sets] (mapv (fn [s] (update s :tokens #(into {} (map (fn [[k v]] [(keyword k) v])) %))) sets))))]
    (t/is (nil? (cak/problem json)))
    (t/is (= "#2563eb" (get (cak/theme-values json (first (:themes json))) "color.primary")))))

(t/deftest contrast-is-the-wcag-ratio
  (t/is (= 21.0 (cak/contrast "#000000" "#ffffff")))
  (t/is (= 1.0 (cak/contrast "#fff" "rgb(255, 255, 255)")))
  (t/is (nil? (cak/contrast "rgba(0, 0, 0, 0.5)" "#ffffff")))
  (let [faint (assoc-in pack [:sets 1 :tokens "color.text-muted"] "#c8c8cc")]
    (t/is (nil? (cak/problem faint)))
    (t/is (= [{:theme "Light" :text "color.text-muted" :ground "color.background"}
              {:theme "Light" :text "color.text-muted" :ground "color.surface"}]
             (mapv #(dissoc % :ratio) (cak/contrast-problems faint))))
    (t/is (< 1 (:ratio (first (cak/contrast-problems faint))) 4.5))))

;; --- A pack from a few choices

(def ^:private choices
  {:name "relay" :title "Relay" :purpose "Calm and exact, for people who plan work."
   :primary "#4338ca" :bodyFont "Inter"})

(t/deftest a-few-choices-make-a-whole-pack
  (doseq [primary ["#4338ca" "#c6ff3d" "#ff5a36" "#111111" "#ffffff" "#7dd3fc" "#f59e0b" "#0f766e"]
          more    [{} {:corners "sharp" :shadow "hard" :typeScale "large" :headingFont "Space Grotesk"}
                   {:corners "round" :density "roomy" :neutral "warm"}
                   {:density "compact" :shadow "none" :neutral "#2a6fb8"}]]
    (let [pack (cakm/make (merge choices {:primary primary} more))]
      (t/testing (str primary " " more)
        (t/is (nil? (cak/problem pack)))
        (t/is (= [] (cak/contrast-problems pack))))))
  (let [pack  (cakm/make (assoc choices :corners "sharp" :shadow "hard" :headingFont "Space Grotesk"))
        light (cak/theme-values pack (first (:themes pack)))
        dark  (cak/theme-values pack (second (:themes pack)))]
    ;; a color that can be read as it is stays as it is
    (t/is (= "#4338ca" (get light "color.primary")))
    (t/is (not= (get light "color.primary") (get dark "color.primary")))
    (t/is (= [{:family "Space Grotesk"} {:family "Inter"}] (:fonts pack)))
    (t/is (= "Space Grotesk, sans-serif" (get light "font-family.heading")))
    (t/is (= 0 (get light "radius.sm")))
    ;; a hard shadow is the ink, with no blur, and the borders thicken
    (t/is (= [{:offsetX 4 :offsetY 4 :blur 0 :spread 0 :color (get light "color.text")}] (get light "shadow.md")))
    (t/is (= 2 (get light "border-width.sm")))))

(t/deftest the-choices-are-checked
  (let [problem #(cakm/choices-problem (apply assoc choices %&))]
    (t/is (nil? (cakm/choices-problem choices)))
    (t/is (re-find #"needs `primary`" (problem :primary "indigo")))
    (t/is (re-find #"needs a `name` in kebab-case" (problem :name "Relay App")))
    (t/is (re-find #"needs `bodyFont`" (problem :bodyFont nil)))
    (t/is (re-find #"corners is one of: round, sharp, soft" (problem :corners "pill")))
    (t/is (re-find #"shadow is one of: hard, none, soft" (problem :shadow "big")))
    (t/is (re-find #"neutral is plain, cool, warm, brand" (problem :neutral "grey")))))

;; --- Applying a pack to a file

(def ^:private box-id (uuid/next))

(def ^:private empty-file
  (let [data    (ctf/make-file-data (uuid/next) (uuid/next))
        page-id (first (:pages data))]
    (update-in data [:pages-index page-id :objects]
               (fn [objects]
                 (-> objects
                     (update-in [uuid/zero :shapes] conj box-id)
                     (assoc box-id (cts/setup-shape {:id box-id :type :rect :name "box" :x 0 :y 0 :width 50 :height 50
                                                     :parent-id uuid/zero :frame-id uuid/zero})))))))

(defn- view
  [data]
  (let [page-id (first (:pages data))]
    {:file-id (:id data) :data data :page-id page-id :objects (get-in data [:pages-index page-id :objects])
     :libraries {}}))

(def ^:private engine
  {:propagate (fn [_ _] {})
   :measure-text (fn [_ _] {:width 90 :height 30})})

(defn- run
  [data tool input]
  (cat/run tool (view data) input engine))

(defn- commit
  [data out]
  (cfc/process-changes data (:changes out)))

(defn- active
  [data]
  (ctob/get-tokens-in-active-sets (:tokens-lib data)))

(defn- box-fill
  [data]
  (-> (get-in data [:pages-index (first (:pages data)) :objects box-id]) :fills first :fill-color))

(t/deftest a-pack-gives-an-empty-file-its-sets-themes-and-an-active-theme
  (let [out   (run empty-file "apply_token_pack" {:pack "bold"})
        after (commit empty-file out)
        lib   (:tokens-lib after)]
    (t/is (= {:applied true :pack "bold" :theme "Light" :was "empty" :roles (count car/roles)}
             (select-keys (:result out) [:applied :pack :theme :was :roles])))
    (t/is (= ["core" "modes/light" "modes/dark"] (mapv ctob/get-name (ctob/get-sets lib))))
    (t/is (= #{"Light" "Dark"} (into #{} (comp (map ctob/get-name) (remove #{ctob/hidden-theme-name})) (ctob/get-themes lib))))
    ;; the light theme is on: every role resolves, to its light value
    (t/is (= (set (keys car/roles)) (set (keys (active after)))))
    (t/is (= "#4f2bff" (:value (get (active after) "color.primary"))))
    (t/is (= ["Space Grotesk" "sans-serif"] (:value (get (active after) "font-family.heading"))))
    (t/is (= ["4" "4" "0"] ((juxt :offset-x :offset-y :blur) (first (:value (get (active after) "shadow.md"))))))
    (t/testing "and the other theme is a switch away"
      (let [dark (commit after (run after "activate_theme" {:name "Dark"}))]
        (t/is (= "#c6ff3d" (:value (get (active dark) "color.primary"))))))
    (t/testing "or is the one turned on"
      (let [dark (commit empty-file (run empty-file "apply_token_pack" {:pack "bold" :theme "Dark"}))]
        (t/is (= "#c6ff3d" (:value (get (active dark) "color.primary"))))))))

(t/deftest a-file-with-tokens-is-not-touched-until-the-user-chooses
  (let [own   (-> empty-file
                  (as-> data (commit data (run data "create_tokens"
                                               {:tokens [{:type "color" :name "color.primary" :value "#ff0000"}
                                                         {:type "color" :name "brand.accent" :value "#00ff00"}]})))
                  (as-> data (commit data (run data "apply_tokens"
                                               {:applications [{:shapeId (str box-id) :tokenName "color.primary"}]}))))
        asked (run own "apply_token_pack" {:pack "neutral"})]
    (t/is (= "#ff0000" (box-fill own)))
    (t/testing "asked: nothing changes"
      (t/is (nil? (:changes asked)))
      (t/is (false? (-> asked :result :applied)))
      (t/is (= {:tokens 2 :sets ["Global"] :themes []} (-> asked :result :file)))
      (t/is (re-find #"merge.*replace.*cancel" (-> asked :result :note))))
    (t/testing "cancel: nothing changes"
      (t/is (nil? (:changes (run own "apply_token_pack" {:pack "neutral" :onExisting "cancel"})))))
    (t/testing "merge: theirs stay on, and where both name a token the pack wins"
      (let [out   (run own "apply_token_pack" {:pack "neutral" :onExisting "merge"})
            after (commit own out)]
        (t/is (= "merged" (-> out :result :was)))
        (t/is (= ["Global" "core" "modes/light" "modes/dark"] (mapv ctob/get-name (ctob/get-sets (:tokens-lib after)))))
        (t/is (= "#00ff00" (:value (get (active after) "brand.accent"))))
        (t/is (= "#2563eb" (:value (get (active after) "color.primary"))))
        ;; what was bound to the token follows its new value
        (t/is (= "#2563eb" (box-fill after)))
        (t/is (= 1 (-> out :result :restyled)))
        ;; and still does after a switch of theme
        (let [dark (commit after (run after "activate_theme" {:name "Dark"}))]
          (t/is (= "#00ff00" (:value (get (active dark) "brand.accent"))))
          (t/is (= "#60a5fa" (box-fill dark))))))
    (t/testing "replace: only the pack is left"
      (let [out   (run own "apply_token_pack" {:pack "neutral" :onExisting "replace"})
            after (commit own out)]
        (t/is (= "replaced" (-> out :result :was)))
        (t/is (= ["core" "modes/light" "modes/dark"] (mapv ctob/get-name (ctob/get-sets (:tokens-lib after)))))
        (t/is (nil? (get (active after) "brand.accent")))
        (t/is (= "#2563eb" (box-fill after)))))
    (t/testing "applying the same pack again changes values, not the sets"
      (let [once  (commit own (run own "apply_token_pack" {:pack "neutral" :onExisting "replace"}))
            twice (commit once (run once "apply_token_pack" {:pack "soft" :onExisting "merge"}))]
        (t/is (= ["core" "modes/light" "modes/dark"] (mapv ctob/get-name (ctob/get-sets (:tokens-lib twice)))))
        (t/is (= 2 (count (remove #(= ctob/hidden-theme-name (ctob/get-name %)) (ctob/get-themes (:tokens-lib twice))))))
        (t/is (= "#6d4aff" (box-fill twice)))
        ;; and each theme still turns on its own mode only, whichever was on
        (let [dark  (commit twice (run twice "activate_theme" {:name "Dark"}))
              again (commit dark (run dark "apply_token_pack" {:pack "neutral" :onExisting "merge"}))]
          (t/is (= "#b3a1ff" (box-fill dark)))
          (t/is (= "#2563eb" (box-fill again)))
          (t/is (= "#60a5fa" (box-fill (commit again (run again "activate_theme" {:name "Dark"}))))))))))

(t/deftest a-pack-written-out-is-checked-before-it-is-applied
  (let [made (:result (run empty-file "generate_token_pack" choices))]
    (t/is (nil? (:changes (run empty-file "generate_token_pack" choices))))
    (t/is (= "#4338ca" (get-in made [:summary :themes "Light" "color.primary"])))
    (t/is (re-find #"NOTHING is applied yet" (:note made)))
    (let [after (commit empty-file (run empty-file "apply_token_pack" {:pack (:pack made)}))]
      (t/is (= "#4338ca" (:value (get (active after) "color.primary")))))
    (t/testing "the choices that made it apply it too, with nothing sent back"
      (let [out   (run empty-file "apply_token_pack" {:choices choices})
            after (commit empty-file out)]
        (t/is (= "relay" (-> out :result :pack)))
        (t/is (= "#4338ca" (:value (get (active after) "color.primary")))))
      (t/is (re-find #"apply_token_pack: choices needs `primary`"
                     (:problem (run empty-file "apply_token_pack" {:choices (dissoc choices :primary)})))))
    (t/testing "a whole pack sent as a string of JSON is read as the pack it is"
      (let [text  #?(:clj (json/encode (:pack made)) :cljs (js/JSON.stringify (clj->js (:pack made))))
            after (commit empty-file (run empty-file "apply_token_pack" {:pack text}))]
        (t/is (= "#4338ca" (:value (get (active after) "color.primary")))))
      (t/is (re-find #"no starter pack is called \"\{not json\""
                     (:problem (run empty-file "apply_token_pack" {:pack "{not json"})))))
    (t/is (re-find #"apply_token_pack: the pack theme \"Dark\" leaves 1 role"
                   (:problem (run empty-file "apply_token_pack"
                                  {:pack (update-in (:pack made) [:sets 2 :tokens] dissoc "color.info")}))))
    (t/is (re-find #"the pack has text that cannot be read — color.text on color.background in Light is 1.05 to 1"
                   (:problem (run empty-file "apply_token_pack"
                                  {:pack (assoc-in (:pack made) [:sets 1 :tokens "color.text"] "#ffffff")}))))
    (t/is (re-find #"no starter pack is called \"loud\" — there are: neutral, bold, soft"
                   (:problem (run empty-file "apply_token_pack" {:pack "loud"}))))
    (t/is (re-find #"has no theme called \"Sepia\"" (:problem (run empty-file "apply_token_pack" {:pack "soft" :theme "Sepia"}))))
    (t/is (re-find #"generate_token_pack: needs `primary`" (:problem (run empty-file "generate_token_pack" (dissoc choices :primary)))))))

(t/deftest the-packs-are-listed-with-what-the-file-has
  (let [listed (:result (run empty-file "list_token_packs" {}))]
    (t/is (= ["neutral" "bold" "soft"] (mapv :name (:packs listed))))
    (t/is (= "Space Grotesk" (get-in (second (:packs listed)) [:themes "Light" "font-family.heading"])))
    (t/is (= {:tokens 0 :sets [] :themes [] :linkedLibraries 0 :hasSystem false} (:file listed)))
    (let [after (commit empty-file (run empty-file "apply_token_pack" {:pack "soft"}))]
      (t/is (true? (-> (run after "list_token_packs" {}) :result :file :hasSystem))))))

;; --- What is drawn by hand joins the system

(defn- shape-named
  [data shape-name]
  (some #(when (= shape-name (:name %)) %)
        (vals (get-in data [:pages-index (first (:pages data)) :objects]))))

(t/deftest a-value-written-by-hand-that-is-a-roles-value-is-bound-to-the-role
  (let [themed (commit empty-file (run empty-file "apply_token_pack" {:pack "neutral"}))
        out    (run themed "build_tree"
                    {:tree {:type "board" :name "Cell" :width 200 :height 80 :fill "#ffffff"
                            :layout {:dir "column" :rowGap 12 :padding {:top 16 :right 16 :bottom 16 :left 16}}
                            :children [{:type "text" :name "Status" :text "On track" :fill "#18181b"}
                                       {:type "rect" :name "Swatch" :width 20 :height 20 :fill "#123456"}]}})
        built  (commit themed out)
        cell   (shape-named built "Cell")]
    (t/testing "the board's white is the surface, its gap and padding are spacing steps"
      (t/is (= "color.surface" (-> cell :applied-tokens :fill)))
      (t/is (= "spacing.md" (-> cell :applied-tokens :row-gap)))
      (t/is (= #{"spacing.lg"} (set (map #(get (:applied-tokens cell) %) [:p1 :p2 :p3 :p4])))))
    (t/testing "a text's ink is the text color"
      (t/is (= "color.text" (-> (shape-named built "Status") :applied-tokens :fill))))
    (t/testing "a value no role has stays as written"
      (t/is (nil? (-> (shape-named built "Swatch") :applied-tokens :fill))))
    (t/testing "the answer says so"
      (t/is (= 2 (-> out :result :boundToRoles :shapes))))
    (t/testing "so it follows the theme, where before it was the one thing left behind"
      (let [dark (commit built (run built "activate_theme" {:name "Dark"}))]
        (t/is (= "#18181b" (-> (shape-named dark "Cell") :fills first :fill-color)))
        (t/is (= "#123456" (-> (shape-named dark "Swatch") :fills first :fill-color)))))
    (t/testing "a shape made alone, and its corner"
      (let [made (commit themed (run themed "create_shape" {:type "rect" :name "Chip" :x 0 :y 200 :width 60 :height 24
                                                            :fill "#2563eb"}))]
        (t/is (= "color.primary" (-> (shape-named made "Chip") :applied-tokens :fill)))))
    (t/testing "keepLiterals leaves every value as written"
      (let [kept (commit themed (run themed "build_tree"
                                     {:keepLiterals true
                                      :tree {:type "board" :name "Poster" :width 200 :height 80 :fill "#ffffff"}}))]
        (t/is (nil? (-> (shape-named kept "Poster") :applied-tokens :fill)))
        (t/testing "and what was drawn so stays so, whatever touches it later"
          (let [poster (shape-named kept "Poster")
                out    (run kept "modify_shape" {:shapeId (str (:id poster)) :fill "#18181b"})
                edited (commit kept out)
                inside (commit edited (run edited "build_tree"
                                           {:parentId (str (:id poster))
                                            :tree {:type "rect" :name "Row" :width 100 :height 20 :fill "#ffffff"}}))]
            (t/is (= "#18181b" (-> (shape-named edited "Poster") :fills first :fill-color)))
            (t/is (nil? (-> (shape-named edited "Poster") :applied-tokens :fill)))
            (t/is (nil? (-> out :result :boundToRoles)))
            ;; a child added to it later is content too
            (t/is (nil? (-> (shape-named inside "Row") :applied-tokens :fill)))
            ;; every change answered is one the backend accepts
            (t/is (every? #(sm/validate cfc/schema:change %) (:changes out)))
            (t/is (every? #(sm/validate cfc/schema:change %)
                          (:changes (run themed "build_tree"
                                         {:keepLiterals true
                                          :tree {:type "board" :name "Poster" :width 200 :height 80 :fill "#ffffff"}}))))))))
    (t/testing "a value the user wrote on a shape the agent only touched stays theirs"
      (let [drawn  (commit themed (run themed "create_shape" {:type "rect" :name "Card" :x 0 :y 300 :width 60 :height 24
                                                              :fill "#123456"}))
            card   (shape-named drawn "Card")
            ;; the user paints it white by hand: a role's value, theirs to keep
            white  (assoc-in drawn [:pages-index (first (:pages drawn)) :objects (:id card) :fills]
                             [{:fill-color "#ffffff" :fill-opacity 1}])
            moved  (run white "modify_shape" {:shapeId (str (:id card)) :name "Card (moved)" :x 40})
            after  (commit white moved)]
        (t/is (nil? (-> (shape-named after "Card (moved)") :applied-tokens :fill)))
        (t/is (nil? (-> moved :result :boundToRoles)))
        (t/testing "but a value the call itself writes is bound"
          (let [painted (commit drawn (run drawn "modify_shape" {:shapeId (str (:id card)) :fill "#ffffff"}))]
            (t/is (= "color.surface" (-> (shape-named painted "Card") :applied-tokens :fill)))))))
    (t/testing "a path's content is its geometry: moving it writes no paint"
      (let [by-the-call #'cab/by-the-call
            roles       {:fill "color.surface" :font-size "font-size.md"}]
        (t/is (= {} (by-the-call roles #{:content :selrect} {:type :path})))
        (t/is (= {} (by-the-call roles #{:content} {:type :bool})))
        ;; a text's content is its ink, size and family
        (t/is (= roles (by-the-call roles #{:content} {:type :text})))
        ;; and what a call made is all its own
        (t/is (= roles (by-the-call roles :all {:type :path})))))
    (t/testing "a file with no roles is left alone"
      (let [out (run empty-file "create_shape" {:type "rect" :name "Chip" :x 0 :y 200 :width 60 :height 24 :fill "#ffffff"})]
        (t/is (nil? (-> out :result :boundToRoles)))
        (t/is (nil? (-> (shape-named (commit empty-file out) "Chip") :applied-tokens :fill)))))))
