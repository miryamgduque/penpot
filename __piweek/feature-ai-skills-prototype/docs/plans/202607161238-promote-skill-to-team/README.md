# Promote a skill to the team (US #12)

**Status:** in-progress
**Created:** 2026-07-16
**Taiga:** [US #12 — Promote a skill to the team](https://tree.taiga.io/project/miryam-all-in-penpot/us/12) (IN PROGRESS)

> **Execution mode (user directive, 2026-07-16):** built **directly on
> `ai-skills-prototype`** (no worktree). Per-phase gates are compile
> (`shadow-cljs compile main`, 0 warnings on our namespaces) + `clj-kondo`, plus
> `check-fmt:clj` for backend. **Tests waived** during development. Commit per
> phase; one live review at the end in devenv.

**Apps:** `frontend`, `backend`
**Dependencies:** builds on US #9 (personal `profile_skill`) and US #14 (reactive
behavior). Shares a pattern with Fork/US #10 (not built) and hands the team-side
management surface to story #13.

## Context

A user promotes a **personal** skill so the whole team gets it. The worked
example is promoting "Tone of voice checker" to the team.

**Decision (discovery, 2026-07-16):** a promoted team skill is backed by a **new
team-scoped store shaped like `profile_skill`** — it carries `label`,
`category`, `reactive`, `body` (US #14's shape), which the older `design_skill`
"team registry" does not (that one is `kind`/`enforcement`, dashboard-only, and
not fed to agents). We leave `design_skill` alone; the team-side *management*
surface (Team Dashboard → Sources → "Agent Skills") is **story #13** and is out
of scope here — US #12 delivers the promote flow plus team-member visibility in
the **agent panel**.

**Grounding (verified in code, 2026-07-16):**
- The ⋯ menu already has a **disabled "Promote to team"** stub (and "Fork") on
  each skill row — [ai_panel.cljs:1510-1515](../../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs).
- Personal skills round-trip via `profile_skills.clj` → `[:user-skills]`
  (`user_skills.cljs`) → merged into the panel catalog by
  `agent-skills/full-catalog` ([agent_skills.cljs:301](../../../../../frontend/src/app/main/data/workspace/agent_skills.cljs))
  via `user-skill->entry`. Team skills mirror this exactly.
- Per-member enable/disable is name-keyed skill-state (account default → per-file
  override), resolved by `resolve-enabled` — a team skill merged **by name**
  reuses it for free (default on).
- The current team is `refs/team` (`dsh/lookup-team`); its `:id` is the team-id
  for the promote + fetch RPCs. Team edit permission: `check-edition-permissions!`
  (see `design_skills.clj` for the pattern).
- Reactive behavior **travels** (copied onto the team skill); foundations
  **don't** (skills read the file's foundations at run time — nothing to copy).
  The confirmation view's "👁 Observer · reads file foundations" line is just a
  note.

## What each side sees

- **Promoter:** their personal copy is marked `promoted_to` the new team skill —
  shown disabled with a "Promoted to team" reference (the *light link*), and
  dropped from the active/router set. They use the team version like anyone.
- **Every member:** the team skill appears as a card in their Skills tab, default
  **on**, merged into the catalog by category with a small "Team" marker.

## Phases

1. [Phase 01 — Backend team_skill store + RPCs](./todo-phase-01-backend-team-skill.md)
   — `team_skill` table (mirrors `profile_skill` + `team_id` + `promoted_by` +
   `source_profile_skill_id`), a `promoted_to` column on `profile_skill`;
   `::promote-skill` and `::get-team-skills` in a new `team_skills.clj`.
2. [Phase 02 — Frontend data + catalog merge](./todo-phase-02-frontend-data-merge.md)
   — `team_skills.cljs` (fetch/promote), fetch on panel open via `refs/team`,
   merge `[:team-skills]` into `full-catalog`, exclude/mark promoted personal
   copies, a "Team" marker on team cards.
3. [Phase 03 — Promote flow UI](./todo-phase-03-promote-flow-ui.md) — enable the
   ⋯ "Promote to team" entry (+ detail view), a `skill-promote*` confirmation
   view (editable name/description + reactive/foundations note + Cancel/Publish),
   panel view wiring, and the post-publish disable + light link.

## Acceptance Criteria

- "Promote to team" is available from a **personal** skill's ⋯ menu and detail
  view; it is absent/disabled for built-ins and already-promoted skills.
- Choosing it opens a confirmation view with editable **name** + **description**,
  a muted reactive/foundations note, and **Cancel / Publish**.
- Publishing creates the team skill (reactive behavior preserved), disables the
  promoter's personal copy, and shows it lightly linked ("Promoted to team").
- The team skill appears as a card, default **on**, in the Skills tab of **every
  team member** (verified with a second account), merged by category.
- Foundations do not travel; the promoted skill reads the current file's
  foundations wherever it runs.

## Out of scope (per US #12 — separate stories)

- Team-side management of promoted skills (Team Dashboard → Sources → Agent
  Skills): **story #13**.
- The "baseline changed" **awareness notice** when someone else updates the team
  skill: deferred until Fork/**story #10** establishes the mechanism (MVP link
  only here).
- Un-publishing / removing a promoted skill: separate story.
- Members forking / locally overriding the promoted skill: general override rule.
