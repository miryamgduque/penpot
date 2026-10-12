;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.agent.document
  "The design agent's document tools that change the file, as pure functions;
  see `app.common.agent.write` for what a tool takes and answers. Comments and
  versions are not file changes: the backend runs those tools itself."
  (:require
   [app.common.agent.structure :as cas]
   [app.common.agent.write :as caw]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(defn create-page
  "Adds an empty page, named as asked or `Page N`. The page in view stays the
  same."
  [{:keys [data]} {:keys [name]} _]
  (let [id     (uuid/next)
        wanted (when (and (string? name) (not (str/blank? name)))
                 (str/trim name))
        named  (or wanted
                   (cfh/generate-unique-name "Page"
                                             (cfh/get-used-names (:pages-index data))
                                             :immediate-suffix? true))]
    {:changes (caw/with-undo [{:type :add-page :id id :name named}]
                [{:type :del-page :id id}])
     :result {:pageId (dm/str id)
              :note (str "page created" (when wanted (str " named \"" wanted "\""))
                         " — you are NOT on it; the other tools still act on the current "
                         "page. Use switch_page to move there.")}}))

;; --- set_foundation
;;
;; A foundation is a standing-context document the file carries: the vibes
;; doc, a tone of voice, a naming guide. It is stored in the file's plugin
;; data, where the workspace reads it from.

(def ^:private foundations-ns :penpot-vibes)

(def max-doc-chars 6000)

(defn slugify
  "A foundation name → its storage slug (`Tone of Voice!` → `tone-of-voice`).
  Empty when nothing slug-worthy survives."
  [name]
  (-> (str name)
      (str/lower)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn display-name
  "The slug back as a human title (`tone-of-voice` → `Tone of voice`)."
  [slug]
  (str/capital (str/replace slug "-" " ")))

(defn- foundation-key
  [slug]
  (if (= slug "vibes")
    "design-md"
    (str "foundation/" slug)))

(defn plain-doc-problem
  "Why `doc` cannot be stored as it is, before its format is looked at."
  [doc]
  (cond
    (not (string? doc))
    "the design doc must be a markdown string"

    (str/blank? doc)
    "the design doc is empty — clear it instead of saving a blank"

    (> (count doc) max-doc-chars)
    (str "the design doc is " (count doc) " characters; keep it under "
         max-doc-chars " — it is read on every single turn")))

(defn- frontmatter?
  [doc]
  (some? (re-find #"^---\r?\n" doc)))

(defn set-foundation
  "Saves `doc` as the foundation `name`, or removes it when `doc` is empty.
  A doc that opens with DESIGN.md frontmatter has to be checked as YAML:
  `check-design-doc` — doc → problem or nil — does that when the host has it."
  [{:keys [data]} {:keys [name doc]} {:keys [check-design-doc]}]
  (let [slug    (slugify name)
        doc     (when (string? doc) (str/trim doc))
        problem (when-not (str/blank? doc) (plain-doc-problem doc))
        store   (fn [value]
                  (-> (cb/empty-changes)
                      (cb/with-file-data data)
                      (cb/set-plugin-data foundations-ns (foundation-key slug) value)
                      (caw/redo)))]
    (cond
      (str/blank? slug)
      {:problem (str "'" name "' does not make a usable foundation "
                     "name — use letters or digits")}

      ;; empty means remove: `doc` is required, so an empty string is the
      ;; explicit "remove it" spelling, not an accident
      (str/blank? doc)
      {:changes (store nil)
       :result {:ok true :note (str "foundation '" (display-name slug) "' removed")}}

      (some? problem)
      {:problem problem}

      (and (frontmatter? doc) (nil? check-design-doc))
      {:fallback "a DESIGN.md document is checked in the workspace"}

      (and (frontmatter? doc) (some? (check-design-doc doc)))
      {:problem (check-design-doc doc)}

      :else
      {:changes (store doc)
       :result {:ok true
                :chars (count doc)
                :note (str "foundation '" (display-name slug) "' saved "
                           "— in your instructions from the next turn on")}})))

;; --- generate_code
;;
;; What Penpot says a design is, as markup and styles, to hold shipped code
;; against. Which shapes a call means is decided here; generating the code is
;; the host's, because the generator renders the frontend's own components.

(def ^:private markup-types #{"html" "svg"})

(defn code-problem
  "Why `generate_code` cannot run, or nil."
  [objects ids type]
  (or (when (empty? ids)
        (str "generate_code: nothing to inspect — pass shapeIds, or select the"
             " shapes you want the markup for"))
      (cas/ids-problem "generate_code" objects ids)
      (when (and (some? type) (not (contains? markup-types type)))
        (dm/str "generate_code: \"" type "\" is not a markup type — use one of: "
                (str/join ", " (sort markup-types))))))

(defn code-plan
  "→ `{:problem}`, or what to generate: the markup `:type`, the `:roots` asked
  for and every shape of `:shapes` to write out. `selected` stands in when no
  shapes are named."
  [objects selected {:keys [shapeIds type includeChildren]}]
  (let [ids  (if (seq shapeIds)
               (into [] (comp (keep #(when (string? %) (parse-uuid %))) (distinct)) shapeIds)
               (vec selected))
        type (or type "html")]
    (if-let [problem (code-problem objects ids type)]
      {:problem problem}
      (let [roots (mapv #(get objects %) ids)
            ;; with their children unless told otherwise: a component's own
            ;; styles without its children is rarely what a review wants
            kids? (not (false? includeChildren))]
        {:type type
         :roots roots
         :shapes (cond->> (cfh/clean-loops objects roots)
                   kids? (mapcat #(cfh/get-children-with-self objects (:id %))))}))))

(defn code-result
  [markup style shapes]
  {:markup markup
   :style style
   :shapes (count shapes)
   :note (str "Penpot's own view of these shapes — compare it against "
              "the shipped code rather than reading the canvas. If the "
              "result is refused as too large, inspect fewer shapes or "
              "pass includeChildren: false.")})
