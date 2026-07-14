;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-skills
  "The built-in skills catalog, shared by the Skills-tab UI and the agent.

  It mirrors `skills-core`'s `builtinCatalog()` (see US #7) — the bundled
  penpot-ai-kit skills, grouped by category, with a mode and a first-run
  enabled default. The agent uses the ENABLED skills to build its system-prompt
  routing index and to back the `get_design_skills` tool.

  NOTE: only catalog metadata (name/category/mode/blurb) is available natively —
  the full skill bodies live in `skills-core` (TS). Bringing those into CLJS (a
  generated `aikit.gen.cljs`, like `import-aikit.mjs` does for the MCP server) is
  a follow-up; until then `get_design_skills` returns the metadata."
  (:require
   [cuerdas.core :as str]))

;; Grouped by category in display order; the dispatch router and the shared/core
;; house-rule docs are intentionally excluded. Audit + build skills ship enabled;
;; the single auto-fix skill ships off (it writes directly).
(def catalog
  [{:category "Audits"
    :skills [{:name "penpot-audit-accessibility" :label "Accessibility audit"
              :blurb "WCAG 2.1/2.2 AA checks" :mode "suggest" :enabled true}
             {:name "penpot-audit-tokens" :label "Tokens governance audit"
              :blurb "Hardcoded values, off-grid spacing" :mode "suggest" :enabled true}
             {:name "penpot-design-to-code-review" :label "Design-to-code review"
              :blurb "Design vs. built code drift" :mode "suggest" :enabled true}]}
   {:category "Build"
    :skills [{:name "penpot-foundations" :label "Foundations"
              :blurb "Design tokens setup" :mode "review" :enabled true}
             {:name "penpot-component-factory" :label "Component factory"
              :blurb "Builds full variant matrix" :mode "review" :enabled true}
             {:name "penpot-build-screen" :label "Build screen"
              :blurb "Designs screens from a brief" :mode "review" :enabled true}
             {:name "penpot-build-from-code" :label "Build from code"
              :blurb "Recreates a view on your tokens" :mode "review" :enabled true}
             {:name "penpot-document-handoff" :label "Document handoff"
              :blurb "Annotates a design for devs" :mode "review" :enabled true}
             {:name "penpot-migrate" :label "Migrate"
              :blurb "Figma → Penpot migration" :mode "review" :enabled true}]}
   {:category "Auto-fix"
    :skills [{:name "penpot-rename-layers" :label "Rename layers"
              :blurb "Auto-fixes messy layer names" :mode "autofix" :enabled false}]}])

(def mode-label
  {"suggest" "suggest" "review" "review" "autofix" "auto-fix"})

(defn enabled-skills
  "Flattened, category-tagged list of the skills enabled on this file."
  []
  (vec (for [{:keys [category skills]} catalog
             skill skills
             :when (:enabled skill)]
         (assoc skill :category category))))

(defn catalog-manifest
  "Metadata for the `get_design_skills` tool: all enabled skills, or one by
  name. Full bodies are not yet available natively (see ns docstring)."
  ([]
   (mapv #(select-keys % [:name :label :category :mode :blurb]) (enabled-skills)))
  ([name]
   (some #(when (= name (:name %))
            (select-keys % [:name :label :category :mode :blurb]))
         (enabled-skills))))

(defn system-prompt-section
  "The skills routing index for the agent's system prompt — enabled skills as a
  short list the agent consults (and fetches details for via get_design_skills)."
  []
  (let [skills (enabled-skills)]
    (when (seq skills)
      (str/join "\n"
                (concat
                 ["## Skills available for this file"
                  "These are your playbooks. When a task matches one, call get_design_skills to read its details and follow it — do not guess its content."]
                 (map (fn [s]
                        (str "- **" (:label s) "** (" (:category s) " · "
                             (get mode-label (:mode s) (:mode s)) "): " (:blurb s)))
                      skills))))))
