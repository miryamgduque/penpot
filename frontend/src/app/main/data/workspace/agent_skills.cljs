;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-skills
  "What the agent knows, in two layers with different lifetimes.

  **`catalog`** — the built-in *skills*: playbooks the user chooses, shared with
  the Skills-tab UI. Mirrors `skills-core`'s `builtinCatalog()` (US #7): the
  bundled penpot-ai-kit skills, grouped by category, with a reactive behavior and
  a first-run enabled default. The agent lists the ENABLED ones as a routing index and backs
  `get_design_skills` with them. Cheap to carry (a name and a blurb); the body
  loads only when a task matches.

  **`inner-knowledge`** — always-on knowledge the agent needs to be correct at
  all: governance, naming, and how its own tools behave. Inlined in every system
  prompt, absent from the catalog, and NOT toggleable — see the comment above it.

  The split is the always-loaded ↔ load-on-demand axis applied to our own corpus:
  conventions that shape every response are always-on; procedures are on-demand.

  NOTE: only catalog metadata (name/category/reactive/blurb) is available natively —
  the full skill bodies live in `skills-core` (TS). Bringing those into CLJS (a
  generated `aikit.gen.cljs`, like `import-aikit.mjs` does for the MCP server) is
  a follow-up; until then `get_design_skills` returns the metadata."
  (:require
   [app.main.data.workspace.aikit-bodies :as ab]
   [app.main.data.workspace.skill-state :as skst]
   [cuerdas.core :as str]))

;; The project-vibes playbook is NATIVE-BORN — written for this agent's own
;; tools — so it carries a `:body` right on its catalog entry and `skill-body`
;; serves it verbatim, without the aikit "written for another surface"
;; preamble. The interview questions live HERE, in prose the model adapts,
;; not hardcoded in the form UI: that is what lets the same ask_user widget
;; drop the logistics question for a portfolio site or rephrase chips into
;; the project's own domain.
(def ^:private vibes-body
  (str/join "\n"
            ["Set (or refresh) this project's vibes: a short design.md the whole file is designed against."
             ""
             "## Method"
             ""
             "1. **Look first.** Call `read_design`. If the file already has content, react to it — name what exists and let it inform the options you offer. If `hasDesignDoc` is true, say you'll be replacing the current vibes (they are in your instructions under 'Project vibes') and keep what still holds unless the new answers contradict it."
             "2. **Interview with ONE `ask_user` call** (title it after the project). Adapt the questions to what you saw — drop what's irrelevant, rephrase options into the project's domain, and give every choice question `allow_decide: true`. Cover roughly:"
             "   - What should I design first? Offer the concrete surfaces you'd actually start with, plus an \"Explore a few options\" chip. (single)"
             "   - Primary platform: mobile app / desktop web / both responsive. (single)"
             "   - ONE domain question that shapes the whole UX — invent the right one (a marketplace: how goods reach buyers; a SaaS tool: solo or team workspaces; a game: session length). Skip it if nothing qualifies. (single)"
             "   - Overall vibe: 5–7 adjective-pair chips like \"Warm & homey / handcrafted\", \"Clean & minimal / utility\", \"Premium / artisanal\", \"Playful & colorful\", \"Editorial / magazine-like\" — tuned to the project. (multi)"
             "   - What makes this different from the obvious competitor? (text, optional, `allow_images: true` — reference screens or moodboards welcome)"
             "   - Who is it for — both sides if it's a marketplace? (text, optional)"
             "   - How many design directions: one strong direction / 2–3 to compare. (single)"
             "   - A name, if there is one — say you'll use a placeholder otherwise. (text, optional)"
             "3. **Write the doc** from the answers, as concise markdown:"
             "   - `# <Name>` and the identity in one sentence."
             "   - `## Vibe` — the chosen words made CONCRETE: what they mean for palette temperature, corner radius, density, type direction. This section must be able to settle a color/spacing argument."
             "   - `## Audience`, `## Platform`, `## Design first`, `## Directions`."
             "   - `## Voice & copy` — tone, capitalization, how playful the microcopy gets."
             "   - `## Do / Don't` — 4–6 bullets each, grounded in the vibe words."
             "   Where an answer was `__decide__`, decide well and mark it \"(my call — say the word to change it)\". Where an optional question was skipped, leave its section out. If reference images were attached, READ them and translate what they establish (palette temperature, density, radius, mood) into the Vibe section — that is why they were asked for. Keep the whole doc under 3500 characters."
             "4. **Save it** with `set_design_doc`, then confirm in 2–3 sentences: the vibe in one line, what you decided on their behalf, and the natural next step (usually designing the first screen)."
             ""
             "## Rules"
             "- One ask_user call per interview — never re-interview question by question in prose."
             "- The doc is a contract, not a mood board: every future design task in this file follows it, so write it concrete enough to constrain real choices."
             "- Do not start designing screens in this task; end at the saved doc + summary."]))

;; Grouped by category in display order; the dispatch router and the shared/core
;; house-rule docs are intentionally excluded. Every built-in skill ships
;; enabled (US #8 — the story is about removing what you don't want, so the
;; baseline is all-on); the `:enabled` here is that built-in default, which the
;; user's per-account / per-file state then overrides (see `resolve-enabled`).
;; Each skill also carries an `:example` (a natural trigger phrase) and `:what`
;; (a one-paragraph "what it does"), surfaced by the Skills-tab detail view.
(def catalog
  [{:category "Setup"
    :skills [{:name "penpot-project-vibes" :label "Set project vibes"
              :blurb "Interview → a design.md the agent designs against" :reactive "on-call" :enabled true
              :example "Set the design vibes for this project."
              :what "Runs a short kickoff interview as an in-chat form (what to design first, platform, vibe words, audience…) and distills the answers into a design.md stored on this file. The agent then honors it in every design task, and collaborators share it."
              :body vibes-body}]}
   {:category "Audits"
    :skills [{:name "penpot-audit-accessibility" :label "Accessibility audit"
              :blurb "WCAG 2.1/2.2 AA checks" :reactive "on-call" :enabled true
              :example "Check this screen for accessibility problems."
              :what "Runs a WCAG 2.1/2.2 AA audit covering contrast, tap-target sizes, heading structure, and focus order. Returns a severity-ranked report without changing the file."}
             {:name "penpot-audit-tokens" :label "Tokens governance audit"
              :rule "token-only-colors"
              :blurb "Hardcoded values, off-grid spacing" :reactive "observer" :enabled true
              :example "Audit this file for design-system issues."
              :what "Flags hardcoded values where a token exists, off-grid spacing, orphan or unused tokens, and detached instances. Suggests semantic-token swaps; reports only, no changes."}
             {:name "penpot-design-to-code-review" :label "Design-to-code review"
              :blurb "Design vs. built code drift" :reactive "on-call" :enabled true
              :example "Does my code match this design?"
              :what "Diffs a Penpot selection against its implemented component (or Storybook story) and reports drift in tokens, structure and states, with a reconciliation. Read-only."}]}
   {:category "Build"
    ;; An Observer skill additionally declares how the live watcher handles it:
    ;; `:rule` ties it to the audited rule its fixes clear, `:detect` says which
    ;; detection tier applies ("deterministic" = the native scan alone; "model" =
    ;; ALSO judged by the semantic audit tick), and `:model` names the cheap model
    ;; the tick / Fix-it-now runs on, so ambient work never bills like design work.
    ;; Prototype-only: these live in the builtin catalog, not the profile_skill DB.
    :skills [{:name "penpot-foundations" :label "Foundations"
              :blurb "Design tokens setup" :reactive "on-call" :enabled true
              :example "Set up design tokens for this file."
              :what "Builds and governs the token + library foundation: primitive/semantic/component token tiers and light/dark themes. Proposes changes for your review before applying."}
             {:name "penpot-component-factory" :label "Component factory"
              :blurb "Builds full variant matrix" :reactive "on-call" :enabled true
              :example "Turn this into a component with variants."
              :what "Builds a component with a complete variant matrix — sizes, hierarchies and every interactive state — fully tokenized and correctly named. Proposed for review."}
             {:name "penpot-build-screen" :label "Build screen"
              :blurb "Designs screens from a brief" :reactive "on-call" :enabled true
              :example "Design a dashboard screen from this brief."
              :what "Designs a production-grade screen from a brief, section by section, reusing the existing tokens and components. Proposes the result for review."}
             {:name "penpot-build-from-code" :label "Build from code"
              :blurb "Recreates a view on your tokens" :reactive "on-call" :enabled true
              :example "Recreate this React view in Penpot."
              :what "Translates existing page or component code into a Penpot screen bound to your design system — mapping code styles onto semantic tokens and reusing library components. For review."}
             {:name "penpot-document-handoff" :label "Document handoff"
              :blurb "Annotates a design for devs" :reactive "on-call" :enabled true
              :example "Annotate this screen for handoff."
              :what "Builds a clean annotation layer beside the design — a context card, numbered pins and matching note cards — wrapped in a hideable group. Proposed for review."}
             {:name "penpot-migrate" :label "Migrate"
              :blurb "Figma → Penpot migration" :reactive "on-call" :enabled true
              :example "Import this Figma file into Penpot."
              :what "Migrates a Figma design into Penpot with high fidelity: Auto Layout → flex/grid, Variables → tokens, component sets → variants, preserving hierarchy. For review."}
             {:name "penpot-rename-layers" :label "Rename layers"
              :blurb "Auto-fixes messy layer names" :reactive "observer" :enabled true
              :rule "layer-naming" :detect "model"
              :model "claude-haiku-4-5-20251001"
              :example "Clean up the layer names in this file."
              :what "Renames auto-generated layer names (Rectangle 12…) to semantic HTML or role names like nav, header, button and h1–h6. Applies directly."}]}])

(defn rule-fix-model
  "The model the auto-fix skill covering `rule` declares (nil when none —
  callers fall back to the panel's selected model)."
  [rule]
  (->> catalog
       (mapcat :skills)
       (filter #(and (:enabled %) (= rule (:rule %))))
       (keep :model)
       (first)))

(def reactive-label
  "The human label for a skill's reactive behavior (US #14): On-call acts only
  when invoked; Observer keeps ambient awareness and notifies in the panel."
  {"on-call" "On-call" "observer" "Observer"})

;; --- Inner knowledge
;;
;; Knowledge the agent must ALWAYS have to be correct at all — as opposed to a
;; skill, which is a playbook the user chooses. It is therefore always inlined in
;; the system prompt, never listed in `catalog`, never returned by
;; `catalog-manifest`, and deliberately NOT toggleable: a user must not be able
;; to switch off "ask before destructive changes".
;;
;; Reworked from the penpot-ai-kit shared docs for the NATIVE agent: the source
;; texts are written against the plugin API and the MCP door, neither of which
;; this agent uses (our tools go through the internal changes pipeline). Both are
;; therefore stripped — a reference to `applyToken()` or an MCP tool is not just
;; noise here, it is wrong.
;;
;; The bar for adding to this layer is high: it is the only content the user
;; cannot turn off, so it is the easiest place to bloat every request. Anything
;; procedural belongs in a skill body, not here. What survives is what shapes
;; *every* response — governance, naming, and how our own tools behave.
;;
;; Sources: shared/modes-and-policies.md (governance half only — its fill-policy
;; and token-modes sections are procedural and stay for skill bodies) and
;; shared/naming-conventions.md (minus its run-identifier section, which is
;; plugin-data specific). shared/penpot-mcp-tool-reference.md and
;; shared/plugin-api-gotchas.md are intentionally NOT carried; the native-tool
;; notes below replace the latter.

(def ^:private governance
  ["## Operating modes (governance)"
   "Design decisions are often ambiguous, opinionated or product-level. Never quietly make an irreversible or opinionated change."
   ""
   "- **Suggest** — propose changes as a report; touch nothing. The default for audits, reviews and anything exploratory."
   "- **Apply-with-review** — make the change, then summarize what changed and pause for direction. The default for all generative work."
   "- **Auto-fix** — apply without asking, but ONLY for the safe set below. Opt-in per change type, never per skill wholesale."
   ""
   "### The safe set — the only changes auto-fix may make"
   "A change qualifies only if it is non-destructive, reversible AND unambiguous:"
   "- Renaming an auto-named layer (`Rectangle 12` → a semantic name)."
   "- Replacing a raw value that is EXACTLY equal to an existing token's resolved value with that token (a loss-less swap)."
   "- Reordering documentation or layer trees without changing geometry."
   "- Adding documentation or metadata."
   ""
   "### Never without explicit approval"
   "- Anything that changes geometry (position, size, layout)."
   "- Creating, deleting or restructuring components or variants; detaching an instance."
   "- Creating a new token — propose it and let a human approve the name, value and tier."
   "- Deleting or renaming shared library assets."
   "- Anything where the matching token is a judgement call rather than an exact equality."
   ""
   "### Checkpoints"
   "\"Looks good\" approves only the phase you just showed — never a future one. Name the next phase explicitly before proceeding."
   "On every applied change, say why: which token, component or rule drove it, and what you rejected."])

(def ^:private naming-conventions
  ["## Naming conventions"
   "- **Tokens** — lowercase dot-notation: `color.action.primary.bg`, `spacing.inset.md`, `radius.control`. Tiers read by intent: primitive (`color.blue.500`) → semantic (`color.text.default`) → component (`button.primary.bg`)."
   "- **Components** — PascalCase: `Button`, `InputField`, `CardProduct`. Sub-parts inside a component use semantic layer names, not PascalCase."
   "- **Variants** — `Property=Value`, both sides PascalCase: `Size=Medium`, `State=Hover`, `Hierarchy=Primary`. Interactive components carry `State = Default | Hover | Pressed | Focus | Disabled` unless the design system says otherwise."
   "- **Layers** — name a layer for the semantic HTML element it represents (`nav`, `header`, `main`, `button`, `label`, `h1`–`h6`, `p`, `ul`, `li`, `img`); for non-semantic containers use a kebab-case role name (`card-container`, `button-group`, `field-row`). Never ship an auto-generated name like `Rectangle 12` or `Group 4` — renaming is a precondition for accessibility work and code review."
   "- **Token sets** — `primitives` (raw ramps), `semantic` (mode-invariant: `spacing.*`, `radius.*`, `font.*`), and `modes/light` + `modes/dark` holding the SAME colour names with per-mode values. A Light/Dark theme just toggles which `modes/*` set is active, so a shape bound by token name flips correctly."])

(def ^:private native-tool-notes
  ["## How your tools behave"
   "- Your tools are your only write path, and every change goes through Penpot's normal edit history — so anything you apply is undoable by the user."
   "- Applying tokens and creating text settle asynchronously. A tool returning successfully means \"applied\", not \"verified\" — confirm the result with read_design or audit_file instead of trusting the return value."
   "- A new board is born with an opaque white fill. Keep it only on a real surface (the screen root, a card, a control) and bind it to a `color.bg.*` token; clear it on layout-only containers, where it defeats a child's border radius and breaks dark mode."
   "- Colour rules are enforced at the tool boundary: while `token-only-colors` is active a raw hex is rejected outright. Create or apply a token — do not try to route around the rule."])

(def inner-knowledge
  "The always-on knowledge layer, inlined into every system prompt."
  (str/join "\n" (concat governance [""] naming-conventions [""] native-tool-notes)))

;; --- Skill bodies (load-on-demand)
;;
;; The other half of the disclosure axis: the routing index above is always in
;; context and costs a line per skill; the BODY is thousands of tokens and loads
;; only when a task actually matches. `aikit-bodies/bodies` is generated (see
;; ai-skills/scripts/import-aikit-cljs.mjs), with the MCP/plugin-API sections and
;; the sections duplicated by `inner-knowledge` already stripped.
;;
;; Stripping whole sections is deterministic; what it cannot fix is prose that
;; assumes a capability we do not have. A few bodies still say things like
;; "`execute_code` is the only mutation path" (false — we have native tools),
;; "call the `export_shape` MCP tool" (we have no such tool until a render tool
;; lands), or "read the design via the Figma MCP" (we have no Figma MCP; the
;; body's own pasted-export fallback is our only path). Rewriting that prose
;; mechanically would be guesswork, so it is reframed at fetch time instead —
;; the model is perfectly able to translate intent onto the tools it can see,
;; provided it is told the playbook predates them.

(def ^:private body-preamble
  (str/join "\n"
            ["> **How to read this playbook.** It was written for a different tool surface — an"
             "> external MCP server driving Penpot's plugin API — which you do not have. Names like"
             "> `execute_code`, `export_shape`, `high_level_overview`, `penpot_api_info`,"
             "> `set.toggleActive()` or `scripts/*.js` are NOT tools you can call: reach for your"
             "> own tools instead, and if a step needs a capability you genuinely lack, say so"
             "> rather than pretending you used it."
             ">"
             "> **Separate the call from the constraint.** Where a step reads like an API call it is"
             "> usually also stating a fact about Penpot — an ordering rule, a precondition, a"
             "> gotcha. The call is stale; the fact is not. \"Create the set and activate it (sets"
             "> are created inactive)\" means activation genuinely has to happen before anything"
             "> references that set — keep that, drop the method name. Discarding the constraint"
             "> along with the syntax is the main way to misread this document."
             ">"
             "> Its governance and naming sections were removed because you already carry them."
             "> What is left is the part worth having: the method — what to build, in what order,"
             "> where to stop for review, and what good looks like."
             ""]))

;; --- User-created skills (US #9)
;;
;; The user's own skills (from the `profile_skill` backend, fetched into
;; `[:user-skills]`) are shaped like catalog entries and MERGED into the built-in
;; catalog, so every consumer below — cards, resolve-enabled, the router index,
;; get_design_skills — treats them the same as built-ins with no special-casing.
;; Their stored `:enabled` is the creation default the resolve chain starts from,
;; and their generated `:body` (written for the native tools already) is served
;; verbatim, without the built-in bodies' "written for another surface" preamble.

(defn- user-skill->entry
  [us]
  {:id       (:id us)
   :name     (:name us)
   :label    (:label us)
   :blurb    (:description us)
   :reactive (:reactive us)
   :category (:category us)
   :enabled  (:enabled us)
   :example  (:trigger us)
   :what     (:description us)
   :body     (:body us)
   :user?    true})

(defn user-skills
  "The user's created skills (from app-db) shaped as catalog entries."
  [state]
  (mapv user-skill->entry (get state :user-skills)))

(defn full-catalog
  "The built-in `catalog` with the user's created skills merged into their
  category — a new group is appended for any category the built-ins don't have."
  [state]
  (let [by-cat    (group-by :category (user-skills state))
        base-cats (into #{} (map :category) catalog)]
    (concat
     (for [{:keys [category skills]} catalog]
       {:category category :skills (into (vec skills) (get by-cat category))})
     (for [[category skills] by-cat
           :when (not (contains? base-cats category))]
       {:category category :skills (vec skills)}))))

(defn skill-body
  "The playbook text for `name` served by `get_design_skills` on demand — never
  inlined into the system prompt. A user skill returns its stored body as-is; a
  built-in with a native `:body` on its catalog entry (written for these tools,
  e.g. project-vibes) is served verbatim; the remaining built-ins return their
  aikit body reframed for the native tool surface."
  [state name]
  (if-let [us (some #(when (= name (:name %)) %) (user-skills state))]
    (:body us)
    (or (some (fn [{:keys [skills]}]
                (some #(when (= name (:name %)) (:body %)) skills))
              catalog)
        (when-let [body (get ab/bodies name)]
          (str body-preamble "\n" body)))))

(defn find-skill
  "The full catalog entry for `name` (built-in or user-created), tagged with its
  `:category`, or nil. Backs the Skills-tab detail view. `:enabled` here is the
  creation default; the resolved on/off comes from `resolve-enabled`."
  [state name]
  (some (fn [{:keys [category skills]}]
          (some #(when (= name (:name %)) (assoc % :category category)) skills))
        (full-catalog state)))

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
    (for [group (full-catalog state)]
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

(defn watched-rules
  "The rule names declared (via `:rule`) by the currently-enabled **Observer**
  skills — what the live watcher enforces when nothing else has set the file's
  rules. Reactive behavior drives the watch: an On-call skill never observes, so
  only Observer skills contribute rules, and disabling one takes its rule out."
  [state]
  (->> (enabled-skills state)
       (filter #(= "observer" (:reactive %)))
       (keep :rule)
       (set)))

(defn catalog-manifest
  "Backs the `get_design_skills` tool. Listing (1-arity) stays metadata-only and
  cheap — it is the menu. A named fetch (2-arity) is where disclosure happens and
  carries the skill's full `:body`; only that call pays for the playbook."
  ([state]
   (mapv #(select-keys % [:name :label :category :reactive :blurb]) (enabled-skills state)))
  ([state name]
   (some #(when (= name (:name %))
            (-> (select-keys % [:name :label :category :reactive :blurb])
                (assoc :body (or (skill-body state name)
                                 "No playbook text is bundled for this skill; use the description above."))))
         (enabled-skills state))))

(defn system-prompt-section
  "The skills routing index for the agent's system prompt — enabled skills as a
  short list the agent consults, then fetches the body for via get_design_skills.

  Each line carries the skill's `:name`, not just its human `:label`, because the
  name IS the key `get_design_skills` takes. Listing only the label made the
  agent guess (`{name: \"Accessibility audit\"}` → error → retry with the real
  name): it recovered, but it burned a whole round doing so. An index that hints
  at a fetch has to say what to fetch by."
  [state]
  (let [skills (enabled-skills state)]
    (when (seq skills)
      (str/join "\n"
                (concat
                 ["## Skills available for this file"
                  "These are your playbooks. When a task matches one, call get_design_skills with the skill's `name` (the value in backticks) to read its playbook and follow it — do not guess its content."]
                 (map (fn [s]
                        (str "- `" (:name s) "` — **" (:label s) "** (" (:category s) " · "
                             (get reactive-label (:reactive s) (:reactive s)) "): " (:blurb s)))
                      skills))))))
