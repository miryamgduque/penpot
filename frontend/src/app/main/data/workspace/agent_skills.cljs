;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-skills
  "What the agent knows, in two layers with different lifetimes.

  **`catalog`** — the built-in *skills*: playbooks the user chooses, shared with
  the Skills-tab UI (US #7): the bundled penpot-ai-kit skills, grouped by
  category, with a reactive behavior and a first-run enabled default. The agent lists the ENABLED ones as a routing index and backs
  `get_design_skills` with them. Cheap to carry (a name and a blurb); the body
  loads only when a task matches.

  **`inner-knowledge`** — always-on knowledge the agent needs to be correct at
  all: governance, naming, and how its own tools behave. Inlined in every system
  prompt, absent from the catalog, and NOT toggleable — see the comment above it.

  The split is the always-loaded ↔ load-on-demand axis applied to our own corpus:
  conventions that shape every response are always-on; procedures are on-demand.

  The full skill bodies are native too: `get_design_skills` serves them from
  the generated `aikit-bodies` namespace (plus the stored body for user-created
  skills)."
  (:require
   [app.common.agent.recipe-build :as carb]
   [app.common.agent.recipes :as cars]
   [app.main.data.workspace.aikit-bodies :as ab]
   [app.main.data.workspace.aikit-refs :as ar]
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
            ["Set (or refresh) this project's vibes: a short DESIGN.md — token frontmatter + written guidance — the whole file is designed against."
             ""
             "## Method"
             ""
             "1. **Look first.** Call `read_design`. If the file already has content, react to it — name what exists and let it inform the options you offer. If `hasDesignDoc` is true, say you'll be replacing the current vibes (they are in your instructions under 'Foundations') and keep what still holds unless the new answers contradict it."
             "2. **Interview with ONE `ask_user` call** (title it after the project). Adapt the questions to what you saw — drop what's irrelevant, rephrase options into the project's domain, and give every choice question `allow_decide: true`. Cover roughly:"
             "   - What should I design first? Offer the concrete surfaces you'd actually start with, plus an \"Explore a few options\" chip. (single)"
             "   - Primary platform: mobile app / desktop web / both responsive. (single)"
             "   - ONE domain question that shapes the whole UX — invent the right one (a marketplace: how goods reach buyers; a SaaS tool: solo or team workspaces; a game: session length). Skip it if nothing qualifies. (single)"
             "   - Overall vibe: 5–7 adjective-pair chips like \"Warm & homey / handcrafted\", \"Clean & minimal / utility\", \"Premium / artisanal\", \"Playful & colorful\", \"Editorial / magazine-like\" — tuned to the project. (multi)"
             "   - What makes this different from the obvious competitor? (text, optional, `allow_images: true` — reference screens or moodboards welcome)"
             "   - Who is it for — both sides if it's a marketplace? (text, optional)"
             "   - How many design directions: one strong direction / 2–3 to compare. (single)"
             "   - A name, if there is one — say you'll use a placeholder otherwise. (text, optional)"
             "3. **Write the doc** from the answers, in the DESIGN.md format: YAML frontmatter between `---` fences carrying the machine-readable design tokens, then a markdown body. The frontmatter is where the vibe words become NUMBERS AND VALUES — it must be able to settle a color/spacing argument on its own:"
             "   - `version: alpha`, `name`, and `description` (the identity in one line)."
             "   - `colors:` — 4–7 named tokens (primary, accent, surface, text…) with concrete values (hex or any CSS color) chosen from the vibe words. If reference images were attached, READ them and pull the palette temperature from there — that is why they were asked for."
             "   - `typography:` — at least `heading` and `body`, each a map of `fontFamily`, `fontSize`, `fontWeight` (plus `lineHeight` where it matters)."
             "   - `rounded:` and `spacing:` — small named scales (sm/md/lg) consistent with the vibe: sharp & utilitarian earns small radii and tight spacing; soft & friendly earns generous ones."
             "   - `components:` (optional) — key components referencing tokens as `{colors.primary}`; never write a reference to a token that doesn't exist above."
             "   Then the body, `##` sections in this order (omit any with nothing to say):"
             "   - `## Overview` — identity, audience, platform, what to design first, how many directions; include the voice & copy guidance (tone, capitalization, how playful the microcopy gets)."
             "   - `## Colors` — why this palette and where each token is used."
             "   - `## Typography` — the type direction made concrete."
             "   - `## Layout` — density, spacing rhythm, what generous vs. tight means here."
             "   - `## Shapes` — how the radius scale is applied, borders, softness."
             "   - `## Do's and Don'ts` — 4–6 bullets each, grounded in the vibe words."
             "   Where an answer was `__decide__`, decide well and mark it \"(my call — say the word to change it)\". Where an optional question was skipped, leave its section out. Keep the whole doc under 5000 characters."
             "4. **Save it** with `set_foundation` (name \"Vibes\"), then confirm in 2–3 sentences: the vibe in one line, what you decided on their behalf, and the natural next step (usually designing the first screen)."
             ""
             "## Rules"
             "- One ask_user call per interview — never re-interview question by question in prose."
             "- The doc is a contract, not a mood board: every future design task in this file follows it, so write it concrete enough to constrain real choices."
             "- Do not start designing screens in this task; end at the saved doc + summary."]))

;; import-brand is the composition proof for the external-content tools
;; (get_page_meta + screenshot_page + insert_image + the token tools): if the
;; playbook needs machinery these primitives don't cover, that is a finding
;; about the primitives. Native-born like vibes — served verbatim. The stop
;; rule is numbered and hard because the vibes rollout showed weak stop rules
;; get ignored by cheaper models (a haiku run built a full landing page
;; unprompted).
(def ^:private import-brand-body
  (str/join "\n"
            ["Import a brand from a website into this file: proposed color tokens, logo imagery, and a visual reference board — nothing touches existing shapes."
             ""
             "## Method"
             ""
             "1. **Read the brand surface.** Call `get_page_meta` on the URL (title, description, theme-color, favicon, og:image). Then `screenshot_page` for the visual; add `fullPage: true` only if the first viewport looked empty."
             "2. **Extract the palette FROM THE SCREENSHOT** — 3–6 colors that carry the brand: primary action, accent, background, text. Cross-check against the theme-color meta. Name only what you can see; if you cannot see images on this model, say so and work from theme-color plus fetch_page questions instead of guessing."
             "3. **Propose, then STOP.** Present a short table — role, hex, where you saw it — plus which imagery you would import (favicon / og:image) and the token names you intend (`color.brand.primary`, `color.brand.accent`, `color.bg.default`, `color.text.default`, in a set named `brand`). Ask whether a brand guide exists with the real values. END YOUR TURN HERE and wait."
             "4. **On approval only:** create the token set and tokens (create_token_set / create_token), then build one \"Brand reference\" board: the logo via insert_image (favicon or og:image URL), labeled color swatches (create_shape + apply_tokens binding the NEW tokens — never raw hexes; token-only-colors rejects the shortcut anyway), and the site title as text."
             "5. **Offer the brand as a design system.** The brand tokens name what the brand is; a design system gives every role a value so recipes and screens can be built on it. Call generate_token_pack with the brand's primary color, its typefaces when the site shows them (search_fonts for the nearest that exists) and corners and shadows as the site has them, show the user its summary — primary, grounds and text of both themes, fonts — and apply it with apply_token_pack only once they agree. In a file that has tokens (the ones of step 4 count), ask merge, replace or cancel."
             "6. **Confirm** in 2–3 sentences: what was created, where the reference board sits, and the natural next step (applying brand tokens to existing screens is a separate task the user asks for)."
             ""
             "## Rules"
             "- Rule 1 — THE STOP IS HARD: no token, shape or image is created before the step-3 approval. The proposal is the end of that turn."
             "- Extracted colors are proposals, not facts — offer to correct them against a real brand guide."
             "- Do not restyle or retoken any existing shape in this task, even if asked to \"import the brand\" — applying it is its own task with its own review."]))

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
              :blurb "Interview → a DESIGN.md the agent designs against" :reactive "on-demand" :enabled true
              :example "Set the design vibes for this project."
              :what "Runs a short kickoff interview as an in-chat form (what to design first, platform, vibe words, audience…) and distills the answers into a DESIGN.md stored on this file: design tokens (colors, typography, radius, spacing) in YAML frontmatter plus written guidance. The agent then honors it in every design task, and collaborators share it."
              :body vibes-body}
             {:name "penpot-import-brand" :label "Import brand from URL"
              :blurb "Website → proposed color tokens + logo + reference board" :reactive "on-demand" :enabled true
              :example "Import the brand from acme.com."
              :what "Reads a website's metadata and a screenshot, proposes a brand palette and token names for approval, and only then creates the tokens plus a reference board with the logo and labeled swatches. Existing shapes are never touched."
              :body import-brand-body}]}
   {:category "Audits"
    :skills [{:name "penpot-audit-accessibility" :label "Accessibility audit"
              :blurb "WCAG 2.1/2.2 AA checks" :reactive "on-demand" :enabled true
              :example "Check this screen for accessibility problems."
              :what "Runs a WCAG 2.1/2.2 AA audit covering contrast, tap-target sizes, heading structure, and focus order. Returns a severity-ranked report without changing the file."}
             {:name "penpot-audit-tokens" :label "Tokens governance audit"
              :rule "token-only-colors"
              :blurb "Hardcoded values, off-grid spacing" :reactive "observer" :enabled true
              :example "Audit this file for design-system issues."
              :what "Flags hardcoded values where a token exists, off-grid spacing, orphan or unused tokens, and detached instances. Suggests semantic-token swaps; reports only, no changes."}
             {:name "penpot-design-to-code-review" :label "Design-to-code review"
              :blurb "Design vs. built code drift" :reactive "on-demand" :enabled true
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
              :blurb "Design tokens setup" :reactive "on-demand" :enabled true
              :example "Set up design tokens for this file."
              :what "Builds and governs the token + library foundation: primitive/semantic/component token tiers and light/dark themes. Proposes changes for your review before applying."}
             {:name "penpot-component-factory" :label "Component factory"
              :blurb "Builds full variant matrix" :reactive "on-demand" :enabled true
              :example "Turn this into a component with variants."
              :what "Builds a component with a complete variant matrix — sizes, hierarchies and every interactive state — fully tokenized and correctly named. Proposed for review."}
             {:name "penpot-build-screen" :label "Build screen"
              :blurb "Designs screens from a brief" :reactive "on-demand" :enabled true
              :example "Design a dashboard screen from this brief."
              :what "Designs a production-grade screen from a brief, section by section, reusing the existing tokens and components. Proposes the result for review."}
             {:name "penpot-build-from-code" :label "Build from code"
              :blurb "Recreates a view on your tokens" :reactive "on-demand" :enabled true
              :example "Recreate this React view in Penpot."
              :what "Translates existing page or component code into a Penpot screen bound to your design system — mapping code styles onto semantic tokens and reusing library components. For review."}
             {:name "penpot-document-handoff" :label "Document handoff"
              :blurb "Annotates a design for devs" :reactive "on-demand" :enabled true
              :example "Annotate this screen for handoff."
              :what "Builds a clean annotation layer beside the design — a context card, numbered pins and matching note cards — wrapped in a hideable group. Proposed for review."}
             {:name "penpot-migrate" :label "Migrate"
              :blurb "Figma → Penpot migration" :reactive "on-demand" :enabled true
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

(def rule->skill
  "`rule → {:name :label}` for the Observer skill that owns each watched rule —
  drives the notification headline (issue #37): a rule's flags are surfaced under
  the skill that watches for them. Built from the catalog's `:rule` declarations."
  (into {}
        (for [group catalog
              skill (:skills group)
              :when (:rule skill)]
          [(:rule skill) {:name (:name skill) :label (:label skill)}])))

(def reactive-label
  "The human label for a skill's reactive behavior (US #14): On-demand acts only
  when invoked; Observer keeps ambient awareness and notifies in the panel."
  {"on-demand" "On-demand" "observer" "Observer"})

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
   "- Creating a new token — propose it and let a human approve the name, value and tier. Not needed for the tokens of a design system the user agreed to (apply_token_pack), nor when the user told you to make the choices yourself: then create the tokens the design calls for and say which."
   "- Deleting or renaming shared library assets."
   "- Anything where the matching token is a judgement call rather than an exact equality."
   ""
   "### Scope and other people's work"
   "- Make the smallest change that satisfies the ask. Never build what was not requested — an unrequested screen is not initiative, it is scope the user now has to review and pay for."
   "- Existing content you did not create is someone's work in progress. Never delete, restyle or \"clean up\" shapes outside the task's scope."
   "- Foundations describe standing intent. When a change you apply contradicts one (a new accent colour, a different type scale), update that foundation in the same turn (set_foundation) or name the conflict — a stale foundation is worse than none."
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

;; Layout doctrine graduated into inner-knowledge after the Kahoot session
;; (2026-07-16 postmortem): the flex-first rules existed only inside skill
;; bodies that never got fetched, and the model burned ~150 calls building
;; absolute-positioned screens it then had to restructure. Layout choices shape
;; every build response — exactly the bar for this layer.
(def ^:private layout-doctrine
  ["## Layout doctrine"
   "- **Every container is a flex or grid board — recursively.** A screen, a card, a list row, a button cluster: create a board, set_layout it, THEN create its children inside it in reading order. Boards nest as deep as the UI does."
   "- **Never position UI with raw x/y.** Order + gap + padding + align/justify place children; set_layout_child (fill/fix/auto) expresses sizing intent. Absolute coordinates don't reflow and rot on the first edit — reserve x/y for placing top-level boards on the canvas and for genuine overlays (set_layout_child absolute:true)."
   "- **Plain groups don't lay out** — they only bound shapes. When siblings need arranging or spacing, that is a board with a layout."
   "- **\"Quick\", \"sloppy\" or \"rough\" means rough VALUES** — eyeballed gaps, placeholder content — never absolute positioning. A rough flex board refines in place; hand-placed coordinates must be rebuilt."
   "- Build order for a screen: root column board → chrome and sections as child boards in reading order → leaves inside each section → spacing and tokens last."
   "- **Canvas placement:** put each new top-level board BESIDE the previous one — tops aligned, one consistent gutter (~100px) — reading the last board's x/width first (read_design or find_shapes). Boards scattered across the canvas make every later render, comparison and handoff harder."])

(def ^:private visual-self-review
  ["## Look at your work before presenting it"
   "- After building or changing anything visible, render it (render_board) and actually LOOK: overlaps, clipping, missing or misordered elements, text overflow, broken hierarchy."
   "- When the user attached reference images, compare your render against them before claiming a match — wrong order, spacing or hierarchy is a failure even when every shape exists. If you cannot see images on this model, say so instead of guessing."
   "- Fix what you see with the smallest change and re-render. After two failed fixes, STOP and present the render with the remaining defects named — never declare success while a defect you noticed is still visible."])

(def ^:private design-system-notes
  ["## A design system to build on"
   "Everything you build should take its colors, type, spacing, corners and shadows from one set of tokens, so it is consistent and can be restyled in one move. That set reaches a file from one of three places: a starter token pack, one generated for the project, or the user's own library."
   "- **Look before you build.** In a file you have not looked at yet, call list_token_packs once before the first shape: `file.hasSystem` says whether there are tokens or a linked library to build on."
   "- **With no system, propose one first and wait.** Do not start building. Offer, in a few lines the user can choose from: the starters that could fit (neutral, bold, soft — one line each on what it is for), and one made for this project when a brief, a foundation or a brand says what it is (generate_token_pack, shown as its summary: primary, grounds, fonts, corners). Ask which they want (ask_user when you have it; otherwise end your turn with the question). When they choose, apply_token_pack, then build."
   "- **A generated pack starts from what the project says.** A foundation, a brief or an imported brand gives the primary color (the brand's), the typefaces, and the temper — sharp or round corners, compact or roomy, flat, soft or hard shadows. Read them (the foundations are in your context; brand tokens are in the file) and pass them to generate_token_pack; do not invent a look the project already described. Always show its summary before applying: the tool applies nothing by itself."
   "- **If they decline, build anyway** — recipes fall back to plain defaults — **and do not ask again in this conversation.**"
   "- **If the user told you not to ask, or to make the choices yourself, choose:** pick the starter that fits or generate one from what you know of the project, apply it, and say in a sentence which and why. Never skip the system silently."
   "- **A pack is the direction, in tokens.** Choosing or generating it IS deciding the direction (see below): do not also restyle shape by shape. To customize, change tokens — send one again with a new value — or switch theme (activate_theme). A dark screen is the Dark theme, not dark colors written on shapes."
   "- **A file that has tokens is the user's.** apply_token_pack will not touch it until the user chose merge, replace or cancel: ask them, in those words, and pass their answer. With tokens already there and no wish for a pack, build on what the file has: recipes use the file's tokens of the same role names."
   "- **The team's own library comes first.** When list_token_packs says the file has no system, also call list_shared_libraries: a shared library of the team is the system they already chose. If one fits, PROPOSE linking it — its name, what it offers — next to the packs, and link it (link_library, userAgreed: true) only after the user says yes to that library. If they say no, nothing is linked and you go on with a pack or without."
   "- **With a library linked, its components before any recipe.** For each part you are about to build — a button, a field, a card, a nav item — look at the library's components first (read_design lists them, with their file's id; list_shared_libraries gives their names). When one IS that part, place it with create_instance {componentId, fileId} and give it its words; pick its variant with switch_variant. Only when none matches, build_recipe; only when no recipe does either, build_tree."
   "- **A match is the same kind of thing, not a similar name.** A library's `Button` is a button; its `Card / Product` is not a pricing plan, and its `Tag` is not a button. A wrong match is worse than building: when in doubt, use the recipe and say which library component you passed over and why. Never detach an instance to bend it into something else."
   "- **Do not mix two systems silently.** Recipes take their look from the file's role tokens; a library's components carry the library's look. Side by side they can disagree — different blue, different corners. When you fall back to a recipe in a file built on a library, give the file's role tokens the library's values first (its tokens of the same role, or what its components show), and tell the user where the two meet."
   "- **A linked library's tokens become the file's.** Right after linking a library that has tokens, in a file with none of its own, call apply_token_pack {library: <its fileId>}: the file takes the library's sets and themes, so recipes, hand-built parts and the library's components all share one set of values and switch theme together. Do not make a look-alike set by hand."
   "- **What is inside a mock-up keeps its own values.** A design shown inside a design — the board on a design tool's canvas, a phone screen in a hero, a chart's series colors, a brand's fixed color — is content, not chrome: it must look the same in every theme. Build it in its own build_tree call with keepLiterals: true and do not bind it to the role tokens. Everything else you draw by hand is bound to the roles its values equal, automatically."
   "- **Bind by hand to the same roles.** What you build with build_tree binds to the role tokens the pack filled — color.background, color.surface, color.text, color.primary, spacing.*, radius.*, font-size.*, font-family.body / font-family.heading, shadow.* — not to literals, and not to new tokens when a role says it."])

(def ^:private visual-judgement
  ["## Judge it, do not only check it"
   "Looking for defects is not the same as judging quality. A screen with nothing wrong on it can still be bland. Work in this order:"
   "- **A direction before the first shape.** For any new screen or visible redesign, decide in a few lines what it should feel like and write it down before you build: the mood in two or three words; the palette (what the primary color is and why, the surfaces, the text); the type (a family and the few sizes that make its levels); the density (roomy or compact); the shape (sharp, soft, round); and ONE signature move that makes it this product's and nobody else's. Put it in the tokens first — send the tokens with the values this design calls for; a token sent again changes, and everything bound to it follows — so everything you build inherits it. When the file has a design direction already (a foundation, a DESIGN.md, existing tokens), follow that instead of inventing one. Say the direction to the user in a sentence."
   "- **The critic at the first whole render, not at the end.** As soon as the screen is whole — every part there with its real words, before any polish — call critique_design with its name and your direction as the brief. Do not spend rounds checking and tidying first: what the critic finds usually moves more than what you would have tidied, and tidying first leaves no room to act on it. Fix only what is broken (overlaps, clipped text, an empty box) before you ask."
   "- **Its points are work, not notes.** Point 1 is yours to do now: make the change, render, and look. Then points 2 and 3, each unless it would add something the user did not ask for or undo something they did ask for — \"out of scope\" is not a reason when the point is about a thing they asked for. A point that needs a part the screen lacks (a timestamp, a status, a picture) is usually in scope: a screen is not only its listed parts. Do not ask the critic again after your changes."
   "- **Then look yourself, with its eyes.** On the render after your changes, in this order: hierarchy (one focal point; what matters most reads first); character (would it be mistaken for a template or a wireframe?); rhythm (spacing in a few consistent steps; related things close); contrast and legibility; alignment and density; fit to the direction you wrote."
   "- **Answer each point when you present.** One line per point of the critic: done and what changed, or not done and why. A screen where point 1 is not done is a first pass: say so."])

(def ^:private native-tool-notes
  ["## How your tools behave"
   "- Your tools are your only write path, and every change goes through Penpot's normal edit history — so anything you apply is undoable by the user."
   "- Applying tokens and creating text settle asynchronously. A tool returning successfully means \"applied\", not \"verified\" — confirm the result with read_design or audit_file instead of trusting the return value."
   "- A new board is born with an opaque white fill. Keep it only on a real surface (the screen root, a card, a control) and bind it to a `color.bg.*` token; clear it on layout-only containers, where it defeats a child's border radius and breaks dark mode."
   "- Colour rules are enforced at the tool boundary: while `token-only-colors` is active a raw hex is rejected outright. Create or apply a token — do not try to route around the rule."
   "- Token order of operations: SETS exist before tokens, THEMES before resolution. A fresh file has no sets — create_token_set (e.g. primitives / semantic / modes/light) first, then create_token into them, then create_token_theme + activate_theme so bound values actually resolve."
   "- For broad reading — file maps, inventories, cross-shape audits — prefer ONE explore_design call over many read_design/find_shapes rounds: it sweeps in a side context and returns a digest at a fraction of the cost."
   "- **Work in as few rounds as possible.** Reach for the composition tools first: build_recipe for anything the recipe catalog has, build_tree for a new section or screen skeleton, clone_shape for copies with different content, create_tokens / update_shapes / apply_tokens for anything repeated — one call, not one per item. When separate calls ARE independent, issue them together in one response instead of one at a time."
   "- When you mention a shape you created or changed, link it: `[Layer name](shape:ID)` with the id your tools returned — the user can click it to select that shape on canvas. Link the shapes that MATTER (the board you just built, the layer a defect names), not every mention."])

(def ^:private recipe-notes
  ["## Recipes"
   "A recipe is a common piece of UI built the way Penpot means it: auto-layout, values bound to tokens, real components with variants. The catalog:"
   (carb/index cars/builtin)
   "- **Before you build anything structural, look here first.** If a piece you are about to build is in the catalog — or might be — call find_recipes with a few words for it, then build_recipe. One call builds what would take you dozens, and builds it the same way every time."
   "- **For a whole page or screen, look for a screen recipe first** (category `screen`: landing-page, dashboard-screen, sign-in-screen, list-screen, settings-screen, mobile-list-screen…). One build_recipe call lays out the whole screen from the sections it is made of, with `texts` for its content and `profile` for its platform; then reshape it: take out a section that does not belong, add the ones it lacks into its slots. When no screen recipe fits, build the screen's board yourself and place section recipes into it with `parentId`."
   "- Recipes say nothing of a platform. When the work is for one — an iPhone app, a desktop web page — pass `profile` (ios, android, web) to build_recipe: it sets the platform's sizes and spacing and picks the variants it uses."
   "- **A built recipe is the start, not the result.** It arrives with stand-in words, grey boxes where icons and pictures go, and default token values. Finishing it is your job, in as few calls as it takes: (1) pass the real content in the build itself with `texts`, and use set_texts for whatever is left — never one set_text per text; (2) replace every layer the result lists in `placeholders`: the icons with ONE set_icons call, a picture with insert_image — or tell the user which you left; (3) fill the slots; (4) make it this product's: give the tokens the values the design calls for (its brand color, its radius, its type sizes) by editing the tokens, never by overriding colors on the shapes. A screen left at the defaults looks like a wireframe — do not present that as finished."
   "- **What a recipe builds is yours to change.** It sets up the basics; push it from there. Restyle through the tokens. Change structure where the design asks for it: a plain tree you edit directly; a component you change on its MAIN, so every instance follows (later build_recipe calls reuse that main, so the change stays). When the user is happy with a piece you reshaped, offer to keep it with save_recipe — it becomes the team's own recipe, used in place of the built-in one of the same name."
   "- **Recipes are for what is convention, not for what makes a product itself.** A form, a table, a navigation bar are conventions: build them from recipes. The parts that carry the product's character — an editor's canvas and tool rail, a player's controls, a map, a brand's hero — have no recipe and should not be forced into one: design them by hand, with intent. Decide the visual direction before you build (palette, type, density, shape) and put it in the tokens first, so what the recipes build inherits it. When the user asks for something bold or distinctive, that is a brief for the direction and for the hand-made parts — not a reason to skip recipes for the ordinary ones."
   "- **A bespoke screen still has ordinary parts — look them up.** Before you build ANY screen, however unusual, call find_recipes once for the conventional pieces inside it: its buttons, text fields, selects, tabs, badges, avatars, list rows. Place those with build_recipe and `parentId` into the boards you made by hand; the tokens you set first give them the screen's look. Drawing a button or a field from rectangles when a recipe exists is a defect, in a bold design as much as in a plain one."
   "- **What repeats is a component; what has states is a variant.** This holds for what you build by hand. When the same piece appears three or more times — the rows of a layer tree, the tools of a rail, the fields of an inspector, the items of a menu — build it ONCE in build_tree as a board with `component: true`, and place the others as `{type: \"instance\", of: …, texts: …}` in the same call. Where one of them is in another state (current, selected, active, disabled), make that a variant (create_variant on the two mains, then switch_variant on the instance) instead of restyling a copy. Before you present a screen, look at it for copies of plain layers that should be instances: a screen full of them is not finished."
   "- When no recipe fits, build by hand with build_tree — and still use recipes for the parts that have one (button, text-field, badge…) instead of drawing them."])

(defn team-recipes-section
  "What the prompt says of the team's own recipes that are in use, or nil."
  [state]
  (let [recipes (remove :disabled (:team-recipes state))]
    (when (seq recipes)
      (str/join "\n"
                (into ["## Your team's recipes"
                       "The team keeps these of its own. Prefer them over the built-in ones; build them with build_recipe by name, and ask find_recipes for their params:"]
                      (map (fn [{:keys [recipe overrides-built-in]}]
                             (str "- " (:name recipe) " (" (:title recipe) "): " (:purpose recipe)
                                  (when overrides-built-in " — used in place of the built-in one"))))
                      recipes)))))

(def inner-knowledge
  "The always-on knowledge layer, inlined into every system prompt."
  (str/join "\n" (concat governance [""] naming-conventions [""]
                         layout-doctrine [""] visual-self-review [""]
                         design-system-notes [""]
                         visual-judgement [""]
                         native-tool-notes [""] recipe-notes)))

;; --- Skill bodies (load-on-demand)
;;
;; The other half of the disclosure axis: the routing index above is always in
;; context and costs a line per skill; the BODY is thousands of tokens and loads
;; only when a task actually matches. `aikit-bodies/bodies` is generated from
;; the committed kit import, with the tooling sections that do not apply to the
;; native agent and the sections duplicated by `inner-knowledge` already
;; stripped.
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
             "> **The `references/NN-name.md` files this playbook cites ARE available**: fetch one"
             "> with get_design_skills {name: this skill, reference: \"NN-name\"} (this result lists"
             "> them under `references`). They carry the method detail — layout composition, style"
             "> profiles, component recipes, critique framework — read the ones the step you are on"
             "> points at."
             ">"
             "> Its governance and naming sections were removed because you already carry them."
             "> What is left is the part worth having: the method — what to build, in what order,"
             "> where to stop for review, and what good looks like."
             ""]))

(def ^:private ref-preamble
  (str/join "\n"
            ["> Reference for a playbook written against the plugin-API surface — translate any"
             "> API names onto your own tools; the method and constraints are the point."
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
  {:id          (:id us)
   :name        (:name us)
   :label       (:label us)
   :blurb       (:description us)
   :reactive    (:reactive us)
   :category    (:category us)
   :enabled     (:enabled us)
   :example     (:trigger us)
   :what        (:description us)
   :body        (:body us)
   :user?       true})

;; A team-promoted skill (US #12, from `[:team-skills]`) is shaped exactly like a
;; user skill so it merges into the catalog by name with no special-casing —
;; every member sees it, default on. `:team? true` is only a display marker.
;; `:arrived` (US #52) is true for every member except the promoter, until they
;; Dismiss/View-skill the arrival notice — see app.rpc.commands.team-skills.
(defn- team-skill->entry
  [ts]
  {:id           (:id ts)
   :name         (:name ts)
   :label        (:label ts)
   :blurb        (:description ts)
   :reactive     (:reactive ts)
   :category     (:category ts)
   :enabled      true
   :example      (:trigger ts)
   :what         (:description ts)
   :body         (:body ts)
   :team?        true
   :promoted-by  (:promoted-by-name ts)
   :arrived      (boolean (:arrived ts))})

(defn user-skills
  "The user's created skills (from app-db) shaped as catalog entries."
  [state]
  (mapv user-skill->entry (get state :user-skills)))

(defn team-skills
  "The current team's promoted skills (from app-db) shaped as catalog entries."
  [state]
  (mapv team-skill->entry (get state :team-skills)))

(defn- extra-skills
  "User-created + team-promoted skills merged into the built-in catalog."
  [state]
  (into (user-skills state) (team-skills state)))

(defn full-catalog
  "The built-in `catalog` with the user's created + team-promoted skills merged
  into their category — a new group is appended for any category the built-ins
  don't have."
  [state]
  (let [by-cat    (group-by :category (extra-skills state))
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
  (if-let [s (some #(when (= name (:name %)) %) (extra-skills state))]
    (:body s)
    (or (some (fn [{:keys [skills]}]
                (some #(when (= name (:name %)) (:body %)) skills))
              catalog)
        (when-let [body (get ab/bodies name)]
          (str body-preamble "\n" body)))))

;; --- References (the second disclosure level)
;;
;; The kit's per-skill `references/*.md` carry the design knowledge the
;; playbooks lean on — until the 2026-07-16 re-import the bodies cited them as
;; dangling pointers. Imported whole into `aikit-refs`; NATIVE references (like
;; the taxonomy below, written for these tools) merge in per skill and are
;; served without the translation preamble.

;; The Kahoot postmortem's decomposition ask: a shared vocabulary for reading a
;; reference image into named regions, so structure and layer names come out
;; consistent across sessions. Attached to the build-family skills.
(def ^:private ui-element-taxonomy
  (str/join "\n"
            ["# UI element taxonomy — decomposing a reference image"
             ""
             "Before creating anything from a screenshot or mockup, name every region top-to-bottom, outside-in, in this vocabulary — and keep those names as your board/layer names, so the layer tree reads like the screen."
             ""
             "## Mobile chrome (iOS / Android)"
             "- `status-bar` — clock left; cellular/wifi/battery cluster right. OS chrome, not app UI: build once, componentize, instance on every screen."
             "- `nav-bar` / `app-bar` — leading back/close control, screen title, optional trailing action. Sits under the status bar."
             "- `tab-bar` — 3–5 icon+label destinations pinned to the bottom."
             "- `home-indicator` — the centered dark pill at the very bottom (iOS)."
             "- `modal-sheet` — a rounded-top surface over a dimmed or peeking parent (the parent shows as a sliver at the very top). The sheet's header stays fixed; content scrolls under it. Model scroll states as ONE content component clipped by the frame at different offsets."
             "- `keypad` — 3×4 numeric grid, letter sub-labels under digits, action key bottom-right."
             ""
             "## Content patterns"
             "- `section` — heading + body or list; separated by spacing, or a `divider` (thin rect bound to a subtle border token)."
             "- `info-card` / callout — leading icon + title + supporting text on a subtle surface with radius. Icon fixed, text column fills."
             "- `list-item` — leading icon/avatar + primary/secondary text + trailing meta or chevron; a list is a column board of them."
             "- numbered/bulleted list — simplest faithful form: one text block per list with markers inline; upgrade to marker+text rows only when the design styles markers separately."
             "- `illustration` / media block — use a placeholder image sized to the reference until real art exists."
             "- `segmented-control` — 2–3 exclusive options in a pill container; active segment filled."
             "- buttons — one PRIMARY (filled) per screen; secondary = tonal/outline; tertiary = text-only."
             "- `field` — label + input + helper/error text, a small column board."
             "- footer caption / attribution bar — e.g. a curator bar in reference-site screenshots. Part of the mockup, not the app: build it if visible, keep it a clearly-named component so it can be dropped."
             ""
             "## Web equivalents"
             "- `top-nav` (logo, links, trailing actions), `sidebar`, `breadcrumbs`, `footer` (link columns + legal), `banner`/consent bar."
             ""
             "## Method"
             "1. List the regions you see BEFORE the first create call; if scope is ambiguous, show the list and ask."
             "2. Each region is a board with a layout (see your layout doctrine), named from this vocabulary."
             "3. Chrome repeats across screens — component + instances, never rebuilt per screen."
             "4. Compare your render against the reference region by region before calling it done."]))

(def ^:private native-references
  {"penpot-build-screen"    {"ui-element-taxonomy" ui-element-taxonomy}
   "penpot-build-from-code" {"ui-element-taxonomy" ui-element-taxonomy}
   "penpot-migrate"         {"ui-element-taxonomy" ui-element-taxonomy}})

(defn skill-references
  "reference-key → text for `name`: the imported kit references merged with the
  native extras. Empty when the skill has none."
  [name]
  (merge (get ar/references name) (get native-references name)))

(defn skill-reference
  "One reference document, or nil. Kit references get the translation preamble;
  native ones (written for these tools) are served verbatim."
  [name ref]
  (when-let [text (get (skill-references name) ref)]
    (if (get-in native-references [name ref])
      text
      (str ref-preamble "\n" text))))

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
  rules. Reactive behavior drives the watch: an On-demand skill never observes, so
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
            (let [refs (skill-references name)]
              (cond-> (-> (select-keys % [:name :label :category :reactive :blurb])
                          (assoc :body (or (skill-body state name)
                                           "No playbook text is bundled for this skill; use the description above.")))
                (seq refs)
                (assoc :references (vec (sort (keys refs)))
                       :referencesNote (str "each is one get_design_skills "
                                            "{name, reference} fetch — read the "
                                            "ones the step you are on cites")))))
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
                  "These are your playbooks. When a task matches one, call get_design_skills with the skill's `name` (the value in backticks) BEFORE your first mutating call, read the playbook, and follow it — do not guess its content. This holds when the user asks for quick, sloppy or rough work (that changes the polish, not the method), and when continuing work a previous conversation started."]
                 (map (fn [s]
                        (str "- `" (:name s) "` — **" (:label s) "** (" (:category s) " · "
                             (get reactive-label (:reactive s) (:reactive s)) "): " (:blurb s)))
                      skills))))))

;; --- Skills through the agent service
;;
;; With the agent service, skills are the harness's: it lists them to the
;; model and loads one through its own `skill` tool. So the prompt carries no
;; index of its own and `get_design_skills` is not offered — only the rule for
;; when to load one stays here.

(defn harness-skills-section
  "What the prompt says about skills when the harness serves them."
  [state]
  (when (seq (enabled-skills state))
    (str/join "\n"
              ["## Skills available for this file"
               "The skills listed to you are your playbooks. When a task matches one, load it with the `skill` tool BEFORE your first mutating call, read the playbook, and follow it — do not guess its content. This holds when the user asks for quick, sloppy or rough work (that changes the polish, not the method), and when continuing work a previous conversation started. Wherever your instructions say get_design_skills, use the `skill` tool."])))

(defn- reference-skill-name
  "The harness takes kebab-case names only."
  [skill reference]
  (-> (str skill "-ref-" reference)
      (str/lower)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn harness-skills
  "The enabled skills as the agent service registers them: `{:name
  :description :body}`. A skill's reference documents are skills of their own,
  named after it, so a playbook can cite one and the model can load just that."
  [state]
  (vec
   (mapcat
    (fn [{:keys [name label category reactive blurb]}]
      (let [refs (skill-references name)]
        (cons
         {:name name
          :description (str label " (" category " · " (get reactive-label reactive reactive) "): " blurb)
          :body (str (or (skill-body state name)
                         "No playbook text is bundled for this skill; use the description above.")
                     (when (seq refs)
                       (str "\n\nReferences — each is a skill of its own, load the ones the step "
                            "you are on cites: "
                            (str/join ", " (map #(reference-skill-name name %) (sort (keys refs)))))))}
         (for [reference (sort (keys refs))]
           {:name (reference-skill-name name reference)
            :description (str "Reference \"" reference "\" of the skill " name
                              " — load it when that playbook cites it")
            :body (skill-reference name reference)}))))
    (enabled-skills state))))
