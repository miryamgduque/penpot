;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.design-doc
  "The project vibes document — a design.md that lives ON the design file.

  Stored in the file's plugin-data (namespace `:penpot-vibes`, key
  `\"design-md\"`), so it goes through the changes pipeline: shared with every
  collaborator, synced live, undoable, and it travels with the file on
  export/import. That is the point — vibes are a project-level agreement, not
  a per-user preference (contrast `skill-state`, which is deliberately
  per-user).

  The agent consumes it through `system-prompt-section`: unlike a skill body
  (fetched on demand), the vibes doc shapes EVERY response, so it belongs to
  the always-on prompt layer. That is also why `max-doc-chars` is a hard cap
  at the write boundary — every character here is a standing tax on every
  future turn in the file."
  (:require
   [app.main.data.plugins :as dp]
   [cuerdas.core :as str]))

(def ^:private data-ns :penpot-vibes)
(def ^:private data-key "design-md")

(def max-doc-chars 4000)

(defn get-doc
  "This file's vibes doc (a markdown string), or nil when none is set."
  [state]
  (when-let [file-id (:current-file-id state)]
    (let [doc (get-in state [:files file-id :data :plugin-data data-ns data-key])]
      (when (and (string? doc) (not (str/blank? doc)))
        doc))))

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

    :else nil))

(defn set-doc
  "The event persisting `doc` on `file-id` (undoable, synced). The caller
  validates with `doc-problem` first; this trusts its input."
  [file-id doc]
  (dp/set-plugin-data file-id :file data-ns data-key doc))

(defn clear-doc
  "The event removing the file's vibes doc (also undoable)."
  [file-id]
  (dp/set-plugin-data file-id :file data-ns data-key nil))

(defn system-prompt-section
  "The always-on prompt section carrying the vibes doc, or nil when the file
  has none (the section is omitted entirely — an empty heading would read as
  'there are vibes but they are blank')."
  [state]
  (when-let [doc (get-doc state)]
    (str/join "\n"
              ["## Project vibes (design.md)"
               (str "The user's chosen design direction for this project, set by them. "
                    "Honor it in every design decision — palette, type, spacing, layout, tone "
                    "and copy alike, and say so when you lean on it. When a request conflicts "
                    "with it, point at the conflict and ask which should win.")
               ""
               doc])))
