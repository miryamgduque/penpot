;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.design-doc
  "Per-file FOUNDATIONS — standing design context that lives ON the design
  file (US #38): the project vibes DESIGN.md, a tone of voice, naming rules —
  an open-ended, named set, each a DESIGN.md-format doc.

  Stored in the file's plugin-data (namespace `:penpot-vibes`), one key per
  foundation, so every doc goes through the changes pipeline: shared with
  every collaborator, synced live, undoable, and it travels with the file on
  export/import. That is the point — foundations are a project-level
  agreement, not a per-user preference (contrast `skill-state`, which is
  deliberately per-user). The vibes foundation KEEPS the historical
  `\"design-md\"` key — existing files just work; other foundations live
  under `\"foundation/<slug>\"`.

  The agent consumes them through `system-prompt-section`: unlike a skill
  body (fetched on demand), foundations shape EVERY response, so they belong
  to the always-on prompt layer. That is also why `max-doc-chars` is a hard
  per-doc cap at the write boundary — every character here is a standing tax
  on every future turn in the file."
  (:require
   [app.main.data.plugins :as dp]
   [app.main.data.workspace.design-md :as dmd]
   [app.main.store :as st]
   [cuerdas.core :as str]
   [okulary.core :as l]))

(def ^:private data-ns :penpot-vibes)
(def ^:private legacy-vibes-key "design-md")
(def ^:private foundation-key-prefix "foundation/")

(def vibes-slug "vibes")

;; 6000, up from the prose-only 4000: the DESIGN.md format (US #38) spends
;; ~1.5k of it on the YAML token frontmatter before the body says a word.
(def max-doc-chars 6000)

(defn slugify
  "A foundation name → its storage slug (`Tone of Voice!` → `tone-of-voice`).
  Empty when nothing slug-worthy survives — callers must reject that."
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
  (if (= slug vibes-slug)
    legacy-vibes-key
    (str foundation-key-prefix slug)))

(defn- foundations-data
  [state]
  (when-let [file-id (:current-file-id state)]
    (get-in state [:files file-id :data :plugin-data data-ns])))

(defn- valid-doc?
  [doc]
  (and (string? doc) (not (str/blank? doc))))

(defn get-foundation
  "The foundation doc stored under `slug`, or nil."
  [state slug]
  (let [doc (get (foundations-data state) (foundation-key slug))]
    (when (valid-doc? doc)
      doc)))

(defn list-foundations
  "This file's foundations as `[{:slug :doc} …]` — vibes first (it is the
  primary standing context), the rest sorted by slug for a stable prompt."
  [state]
  (let [data   (foundations-data state)
        vibes  (let [doc (get data legacy-vibes-key)]
                 (when (valid-doc? doc)
                   [{:slug vibes-slug :doc doc}]))
        others (->> data
                    (keep (fn [[k v]]
                            (when (and (string? k)
                                       (str/starts-with? k foundation-key-prefix)
                                       (valid-doc? v))
                              {:slug (subs k (count foundation-key-prefix))
                               :doc  v})))
                    (sort-by :slug))]
    (vec (concat vibes others))))

(defn doc-problem
  "Why `doc` cannot be stored, or nil when it can."
  [doc]
  (cond
    (not (string? doc))
    "the design doc must be a markdown string"

    (str/blank? doc)
    "the design doc is empty — clear it instead of saving a blank"

    (> (count doc) max-doc-chars)
    (str "the design doc is " (count doc) " characters; keep it under "
         max-doc-chars " — it is read on every single turn")

    ;; format gate (US #38): a doc carrying DESIGN.md frontmatter must parse
    ;; and pass the schema — persisting broken YAML would poison every later
    ;; read. A plain-markdown doc (no fence) still passes untouched.
    :else
    (let [{:keys [error] :as parsed} (dmd/parse doc)]
      (or error
          (when-let [problems (dmd/problems parsed)]
            (str/join "; " problems))))))

(defn set-foundation
  "The event persisting `doc` as foundation `slug` on `file-id` (undoable,
  synced). The caller validates with `doc-problem` first; this trusts input."
  [file-id slug doc]
  (dp/set-plugin-data file-id :file data-ns (foundation-key slug) doc))

(defn clear-foundation
  "The event removing foundation `slug` from the file (also undoable)."
  [file-id slug]
  (dp/set-plugin-data file-id :file data-ns (foundation-key slug) nil))

;; ---- the vibes foundation, by its old names (pre-foundations callers)

(defn get-doc
  "This file's vibes doc (a markdown string), or nil when none is set."
  [state]
  (get-foundation state vibes-slug))

(defn set-doc
  [file-id doc]
  (set-foundation file-id vibes-slug doc))

(defn clear-doc
  [file-id]
  (clear-foundation file-id vibes-slug))

;; The reactive views live HERE, not in app.main.refs: this ns reaches the
;; changes pipeline for its writes (plugins → changes → data.event), and
;; data.event requires refs — refs requiring us would close that loop into
;; a circular dependency. UI derefs these directly instead.
(def doc-ref
  "Reactive view of the current file's vibes doc (nil when unset)."
  (l/derived get-doc st/state))

(def foundations-ref
  "Reactive view of the current file's foundations (`[{:slug :doc} …]`)."
  (l/derived list-foundations st/state))

(defn system-prompt-section
  "The always-on prompt section carrying every foundation, or nil when the
  file has none (the section is omitted entirely — an empty heading would
  read as 'there are foundations but they are blank')."
  [state]
  (when-let [foundations (seq (list-foundations state))]
    (str/join
     "\n"
     (concat
      ["## Foundations (standing design context for this file)"
       (str "Named, per-file guidance the user set — the project vibes, tone "
            "of voice, and the like. Honor every foundation in every design "
            "decision — palette, type, spacing, layout, tone and copy alike — "
            "and say so when you lean on one. When a request conflicts with a "
            "foundation, point at the conflict and ask which should win. "
            "Where a foundation carries YAML token frontmatter, those tokens "
            "are the source of truth — use those exact values, never "
            "near-misses.")]
      (mapcat (fn [{:keys [slug doc]}]
                ["" (str "### " (display-name slug)) "" doc])
              foundations)))))
