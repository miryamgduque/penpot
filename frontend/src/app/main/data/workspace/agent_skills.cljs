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
   [app.main.data.workspace.skill-state :as skst]
   [cuerdas.core :as str]))

;; Grouped by category in display order; the dispatch router and the shared/core
;; house-rule docs are intentionally excluded. Every built-in skill ships
;; enabled (US #8 — the story is about removing what you don't want, so the
;; baseline is all-on); the `:enabled` here is that built-in default, which the
;; user's per-account / per-file state then overrides (see `resolve-enabled`).
;; Each skill also carries an `:example` (a natural trigger phrase) and `:what`
;; (a one-paragraph "what it does"), surfaced by the Skills-tab detail view.
(def catalog
  [{:category "Audits"
    :skills [{:name "penpot-audit-accessibility" :label "Accessibility audit"
              :blurb "WCAG 2.1/2.2 AA checks" :mode "suggest" :enabled true
              :example "Check this screen for accessibility problems."
              :what "Runs a WCAG 2.1/2.2 AA audit covering contrast, tap-target sizes, heading structure, and focus order. Returns a severity-ranked report without changing the file."}
             {:name "penpot-audit-tokens" :label "Tokens governance audit"
              :blurb "Hardcoded values, off-grid spacing" :mode "suggest" :enabled true
              :example "Audit this file for design-system issues."
              :what "Flags hardcoded values where a token exists, off-grid spacing, orphan or unused tokens, and detached instances. Suggests semantic-token swaps; reports only, no changes."}
             {:name "penpot-design-to-code-review" :label "Design-to-code review"
              :blurb "Design vs. built code drift" :mode "suggest" :enabled true
              :example "Does my code match this design?"
              :what "Diffs a Penpot selection against its implemented component (or Storybook story) and reports drift in tokens, structure and states, with a reconciliation. Read-only."}]}
   {:category "Build"
    :skills [{:name "penpot-foundations" :label "Foundations"
              :blurb "Design tokens setup" :mode "review" :enabled true
              :example "Set up design tokens for this file."
              :what "Builds and governs the token + library foundation: primitive/semantic/component token tiers and light/dark themes. Proposes changes for your review before applying."}
             {:name "penpot-component-factory" :label "Component factory"
              :blurb "Builds full variant matrix" :mode "review" :enabled true
              :example "Turn this into a component with variants."
              :what "Builds a component with a complete variant matrix — sizes, hierarchies and every interactive state — fully tokenized and correctly named. Proposed for review."}
             {:name "penpot-build-screen" :label "Build screen"
              :blurb "Designs screens from a brief" :mode "review" :enabled true
              :example "Design a dashboard screen from this brief."
              :what "Designs a production-grade screen from a brief, section by section, reusing the existing tokens and components. Proposes the result for review."}
             {:name "penpot-build-from-code" :label "Build from code"
              :blurb "Recreates a view on your tokens" :mode "review" :enabled true
              :example "Recreate this React view in Penpot."
              :what "Translates existing page or component code into a Penpot screen bound to your design system — mapping code styles onto semantic tokens and reusing library components. For review."}
             {:name "penpot-document-handoff" :label "Document handoff"
              :blurb "Annotates a design for devs" :mode "review" :enabled true
              :example "Annotate this screen for handoff."
              :what "Builds a clean annotation layer beside the design — a context card, numbered pins and matching note cards — wrapped in a hideable group. Proposed for review."}
             {:name "penpot-migrate" :label "Migrate"
              :blurb "Figma → Penpot migration" :mode "review" :enabled true
              :example "Import this Figma file into Penpot."
              :what "Migrates a Figma design into Penpot with high fidelity: Auto Layout → flex/grid, Variables → tokens, component sets → variants, preserving hierarchy. For review."}]}
   {:category "Auto-fix"
    :skills [{:name "penpot-rename-layers" :label "Rename layers"
              :blurb "Auto-fixes messy layer names" :mode "autofix" :enabled true
              :example "Clean up the layer names in this file."
              :what "Renames auto-generated layer names (Rectangle 12…) to semantic HTML or role names like nav, header, button and h1–h6. Applies directly."}]}])

(def mode-label
  {"suggest" "suggest" "review" "review" "autofix" "auto-fix"})

(defn find-skill
  "The full catalog entry for `name`, tagged with its `:category`, or nil.
  Backs the Skills-tab detail view. `:enabled` here is the built-in default;
  the resolved on/off comes from `resolve-enabled`."
  [name]
  (some (fn [{:keys [category skills]}]
          (some #(when (= name (:name %)) (assoc % :category category)) skills))
        catalog))

(defn resolve-enabled
  "Effective on/off for one skill: built-in `default` → account default →
  this file's override (per-file wins). `account`/`file` are skill-name→bool
  maps (see `skill-state`); a nil/absent entry falls through to the next layer."
  [default account file skill-name]
  (cond
    (contains? file skill-name)    (get file skill-name)
    (contains? account skill-name) (get account skill-name)
    :else                          default))

(defn- resolved-catalog
  "The catalog with each skill's `:enabled` replaced by its effective state for
  the current file, given the app-db `state`."
  [state]
  (let [file-id (:current-file-id state)
        account (skst/account-states state)
        file    (skst/file-states state file-id)]
    (for [group catalog]
      (update group :skills
              (fn [skills]
                (mapv (fn [s]
                        (assoc s :enabled
                               (resolve-enabled (:enabled s) account file (:name s))))
                      skills))))))

(defn resolved-enabled-map
  "`{skill-name enabled}` over the whole catalog, resolved for the current file.
  Backs the Skills-tab toggles (each card reads its own resolved state)."
  [state]
  (into {} (for [group (resolved-catalog state)
                 skill (:skills group)]
             [(:name skill) (:enabled skill)])))

(defn enabled-skills
  "Flattened, category-tagged list of the skills enabled on this file, with the
  user's per-account / per-file overrides applied over the built-in defaults."
  [state]
  (vec (for [{:keys [category skills]} (resolved-catalog state)
             skill skills
             :when (:enabled skill)]
         (assoc skill :category category))))

(defn catalog-manifest
  "Metadata for the `get_design_skills` tool: all enabled skills, or one by
  name. Full bodies are not yet available natively (see ns docstring)."
  ([state]
   (mapv #(select-keys % [:name :label :category :mode :blurb]) (enabled-skills state)))
  ([state name]
   (some #(when (= name (:name %))
            (select-keys % [:name :label :category :mode :blurb]))
         (enabled-skills state))))

(defn system-prompt-section
  "The skills routing index for the agent's system prompt — enabled skills as a
  short list the agent consults (and fetches details for via get_design_skills)."
  [state]
  (let [skills (enabled-skills state)]
    (when (seq skills)
      (str/join "\n"
                (concat
                 ["## Skills available for this file"
                  "These are your playbooks. When a task matches one, call get_design_skills to read its details and follow it — do not guess its content."]
                 (map (fn [s]
                        (str "- **" (:label s) "** (" (:category s) " · "
                             (get mode-label (:mode s) (:mode s)) "): " (:blurb s)))
                      skills))))))
