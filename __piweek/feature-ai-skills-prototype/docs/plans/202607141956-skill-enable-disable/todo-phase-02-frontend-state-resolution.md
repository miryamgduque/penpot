# Phase 02 — Frontend state + effective-enabled resolution

**Status:** todo

## Goal

Load and persist skill state on the frontend (**account default + per-file override, both
per-user, from the Phase 01 backend** — the file override is NOT shared file data), and make
`agent_skills/enabled-skills` (and the system-prompt / `get_design_skills` index) reflect the
**resolved** effective state — so a toggle changes what the router sees immediately.

## Before Start

- [ ] Read `data/ai_providers.cljs` (fetch + optimistic `rp/cmd!` write pattern) and `refs.cljs`
- [ ] Read `agent_skills.cljs` (`catalog`, `enabled-skills`, `catalog-manifest`, `system-prompt-section`)
- [ ] Note: per-file override is **per-user via Phase 01's backend** (keyed by `file_id`), NOT the
      shared file `:data` — so `set-enforced-rules`/file-data is the wrong path here; use `rp/cmd!`
- [ ] Confirm the current `file-id` is available in the panel's data layer to pass to the commands
- [ ] **Default:** US #8 overrules US #7 — flip every built-in's static default (incl.
      `penpot-rename-layers`) to **on**. No first-use guard (no instant-apply skills in the story).

## Checklist

- [ ] `data/workspace/skill-state.cljs` (**new**): fetch states via `:get-skill-states {file-id}`
      (account rows + this file's rows) into app state; action `set-skill-enabled {skill enabled ?file-id}`
      (optimistic update + `rp/cmd!`)
- [ ] The card toggle action passes the **current file-id** (per-file override); NULL file-id sets
      the account default (no dedicated UI for that in this story — see README)
- [ ] `resolve-enabled` helper: built-in default → account row (NULL file) → per-file row (this file wins)
- [ ] `agent_skills/enabled-skills` + `catalog-manifest` + `system-prompt-section` use resolved state
- [ ] `refs` for account states + file overrides; a `skill-enabled?` ref/selector for the UI
- [ ] Unit tests for `resolve-enabled` (all precedence combinations) and the enabled-skills filter
- [ ] `make lint` + typecheck; frontend build
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Note the selector/action API for Phase 03's toggle (card toggle = per-file override for the current file)

## Files

- `frontend/src/app/main/data/workspace/skill_state.cljs` — **new**
- `frontend/src/app/main/data/workspace/agent_skills.cljs` — resolution in enabled-skills/manifest
- `frontend/src/app/main/refs.cljs` — refs/selectors

## Notes

- Router immediacy: because `enabled-skills` derives from resolved state, disabling a skill drops
  it from the next turn's system-prompt index and `get_design_skills` with no extra plumbing.
- Keep resolution pure/tested — it's the crux of correctness and feeds both UI and agent.
