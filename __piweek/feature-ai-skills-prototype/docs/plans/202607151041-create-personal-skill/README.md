# Create a personal skill from scratch (US #9)

**Status:** todo
**Created:** 2026-07-15
**Apps:** `backend`, `frontend`
**Source:** [Taiga US #9 — Create a personal skill from scratch](https://tree.taiga.io/project/miryam-all-in-penpot/us/9)
**Dependencies:** Builds on the built-in catalog (US #7), the enable/disable machinery (US #8), the
Skills view + chat-default panel (US #35), and the skill-body/disclosure work (US #26). **Out of
scope:** testing a skill (US #11), forking an existing skill (US #10), promoting to team (US #12).

## Context

> As a Penpot user, I want to create a new skill by describing what I need to the Agent, so that I
> don't have to write a structured skill document by hand.

Worked example (from the story): a **"Tone of voice checker"** — reviews a file's copy against a
tone the user describes and flags mismatches. Reports only ⇒ files under **Audits**, mode **suggest**.

**Interaction (verbatim intent):**

- **Two entry points, one flow.** A **"Create skill"** action in the Skills view, or **natural
  language in Chat** ("create a skill that checks my copy's tone"). Either way, **creation happens
  in the Skills view as its own guided flow**, separate from the main Chat; started from Chat, the
  user is **taken to the Skills view** to continue (the initial description carries over).
- **Guided interview.** A small guided flow asks: **what should it do**, **when should it trigger /
  an example phrase**, and **which mode** (🔍 suggest / ✏️ review / ⚡ auto-fix) — the Agent
  **proposes a default mode** from the description (tone checker → suggest), the user confirms or
  overrides.
- **Generation (hidden).** From the captured answers the model **generates the full structured skill
  document** (frontmatter, trigger, steps, mode, body). The user **never sees the raw structure** —
  consistent with US #26's "don't expose the repo's 16-section template to end users."
- **Result.** A **new card in the Skills list**, filed under the **closest existing category** to
  its behavior (tone checker → Audits; closest imperfect match if none fits), **default on** (the
  user just made it — no second step to enable).

### Decisions (from discovery)

1. **Persistence: a new per-user backend table.** `profile_skill(profile_id, name, label, category,
   mode, trigger, description, body, is_enabled)` + create/list RPC — mirrors `design_skill` (0152)
   scoped to a profile, and the `skill_state` command style (US #8). Survives reload/sessions/devices.
2. **Guided capture + LLM generation.** A small guided capture (what / trigger / mode with a proposed
   default) — *not* an open-ended conversational interview — then the **model generates** the full
   structured skill doc from those answers. Real generation, lighter interview.
3. **Both entry points.** The Skills-view "Create skill" action **and** a Chat natural-language path
   that routes into the same Skills-view flow, seeding the "what".

### Current state (grounded in code)

- **Catalog is static.** `agent-skills/catalog` is a `def` consumed directly by `skills-tab*`
  (renders the list) and by `catalog-manifest` / `resolved-enabled-map` / `enabled-skills` /
  `system-prompt-section` (which already take `state`, US #8) and `find-skill` / `skill-body`
  (which don't). **User skills must merge into a state-aware catalog** so they render as cards, feed
  the router + `get_design_skills`, and toggle through the **existing** enable/disable machinery
  (their stored `is_enabled` is the "built-in default" the resolve chain starts from).
- **Bodies:** `skill-body` serves built-in bodies from the generated `aikit-bodies/bodies`; a user
  skill serves its **stored** `body` instead (via the same `get_design_skills` path).
- **Model access:** `agent.cljs` reaches the model through the backend **proxy** (`encode-anthropic`
  / `encode-openai` + `run-turn`, streamed). Generation needs a **single completion** through the
  same proxy + the selected provider/model.
- **Backend precedents:** `skill_state.clj` (RPC + migration style, US #8) and `design_skill`
  (0152 table shape — id, name, trigger_on, description, body, is_enabled, timestamps).
- **Panel:** `ai-panel*` owns the `:chat`/`:skills` view + the selected-skill state (US #35); the
  Chat entry point routes by switching to `:skills` and opening the create flow.

## Phases

1. [Phase 01 — Backend: `profile_skill` table + RPC](./done-phase-01-backend-profile-skill.md) —
   per-user table + `create-skill` / `get-skills`, mirroring `skill_state` + `design_skill`.
   ✅ **done** (16-assertion test; live create/list round-trip verified)
2. [Phase 02 — Frontend data + state-aware catalog](./done-phase-02-frontend-catalog-merge.md) —
   fetch user skills, merge into a state-aware catalog so they render/route/toggle via existing paths.
   ✅ **done** (3 deftests / 13 assertions; test build clean)
3. [Phase 03 — LLM skill-doc generation](./done-phase-03-generation.md) — answers → one model
   completion → structured skill doc (name/label/category/mode/trigger/body), category classified.
   ✅ **done** (5 deftests / 21 assertions)
4. [Phase 04 — Skills-view guided creation flow](./done-phase-04-creation-flow-ui.md) — "Create
   skill" action + guided capture (what/trigger/mode) → generate → create → card (default on).
   ✅ **done** (compile/lint clean; live create is the user's check)
5. [Phase 05 — Chat entry point](./todo-phase-05-chat-entry-point.md) — natural-language "create a
   skill…" routes into the Skills-view flow, seeding the description.

## Acceptance Criteria

- A **"Create skill"** action in the Skills view **and** a Chat natural-language path both lead into
  the **same guided flow in the Skills view** (Chat carries the initial description over).
- The guided flow captures **what / trigger / mode** (mode default proposed, user confirms/overrides).
- The model **generates the structured skill document**; the user never sees the raw structure.
- The created skill appears as a **card in the Skills list**, under the **closest existing category**,
  **default on**, and is immediately usable by the agent (in `enabled-skills` / `get_design_skills`,
  toggleable, with its generated body served on demand).
- Skills **persist per-user** (backend) across reload/sessions.
- `make lint` / typecheck pass; backend + frontend build; backend RPC has tests; verified live.
