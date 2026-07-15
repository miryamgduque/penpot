;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.slash-commands
  "The chat composer's `/` menu.

  Two kinds of entries share one shape —

    {:command \"vibes\" :title \"Set project vibes\" :detail \"…\" :insert \"…\"}

  — the special commands first, then every enabled skill (built-in and
  user-created), whose `:insert` is the skill's natural trigger phrase
  (its catalog `:example`). Picking an entry only fills the composer; the
  user still sends the message themselves, so they can edit it, add to it,
  or abort — nothing fires behind their back.

  Pure menu logic lives here (buildable and filterable without a DOM); the
  popover itself is rendered by the composer in `ui.workspace.ai-panel`."
  (:require
   [app.main.data.workspace.agent-skills :as ask]
   [cuerdas.core :as str]))

(def commands
  "Special commands, listed ahead of the skills. `/vibes` inserts the
  project-vibes skill's own trigger phrase — the agent's skills index does
  the actual routing. `:skill` names the catalog skill a command fronts, so
  the menu doesn't list the same thing twice."
  [{:command "vibes"
    :title "Set project vibes"
    :detail "Interview me about this project and pin down its design direction"
    :insert "Set the design vibes for this project."
    :skill "penpot-project-vibes"}])

(defn- skill->entry
  [{:keys [name label blurb example]}]
  {:command name
   :title label
   :detail blurb
   :insert (or example (str "Run the " label " skill on this file."))})

(defn menu-model
  "Every entry the `/` menu can offer for app-db `state`: the special
  commands, then the enabled skills in catalog order — minus the skills a
  command already fronts. Disabled skills never show — consistent with the
  agent's own routing index."
  [state]
  (let [skills  (ask/enabled-skills state)
        enabled (into #{} (map :name) skills)
        fronted (into #{} (keep :skill) commands)
        ;; a command fronting a skill obeys that skill's enable toggle —
        ;; offering /vibes while the vibes skill is off would route nowhere
        visible (filterv #(or (nil? (:skill %)) (contains? enabled (:skill %)))
                         commands)]
    (into visible
          (comp (remove #(contains? fronted (:name %)))
                (map skill->entry))
          skills)))

(defn query
  "The filter text of a composer `input` in slash mode, or nil when the input
  isn't a slash command. Slash mode = the input STARTS with `/` — a slash
  mid-sentence must not open a menu."
  [input]
  (let [input (or input "")]
    (when (str/starts-with? input "/")
      (subs input 1))))

(defn filter-entries
  "The entries matching `q` (case-insensitive, against the command token and
  the human title), keeping the given order. Blank keeps everything; no
  match yields [] — the composer hides the menu, so an ordinary sentence
  that happens to start with `/` degrades gracefully."
  [entries q]
  (let [q (str/lower (str/trim (or q "")))]
    (if (str/blank? q)
      (vec entries)
      (filterv (fn [{:keys [command title]}]
                 (or (str/includes? (str/lower (str command)) q)
                     (str/includes? (str/lower (str title)) q)))
               entries))))
