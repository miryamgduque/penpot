# Phase 04 — Anti-generic design nudge (no-DESIGN.md build requests)

**Status:** done

Adopts the codex prompt's frontend-design section (purposeful typography, no
default stacks, a committed color direction, meaningful motion — *exception:
preserve an existing design system*) for the case where it matters most for us:
a build request on a file with **no** DESIGN.md/vibes doc, which is exactly when
output collapses into bland defaults. Zero standing prefix cost: the nudge rides
the volatile user-message slot, not the system prompt.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Re-read `match-playbook` (`agent.cljs:1186`), the `:playbook` slot in
      `user-content` (`agent.cljs:49-66`), and `dd/system-prompt-section` —
      confirm the doc-presence check and the injection path are unchanged
- [ ] Re-read the one-shot nudge precedent (playbook NUDGE atoms in
      `agent_tools.cljs`, reset by new-chat/load-chat EffectEvents)

## Checklist

- [x] Write/update tests: `user-content` renders the nudge block when given one
      (pure function test); the trigger predicate (build-matched + no doc +
      not-yet-nudged) in isolation
- [x] Trigger: piggyback on `match-playbook`'s existing first-message round —
      when the matched skill is a build playbook AND the file has no design doc,
      attach a `:direction-nudge` alongside `:playbook`. No new model round, no
      new atom lifecycle beyond the existing playbook-loaded one.
- [x] Nudge copy (distilled codex, reworded for Penpot, ≤8 lines): "This file
      has no DESIGN.md. Before building, commit to a visual direction — a
      purposeful type pairing (not a default stack), a named color direction
      with tokens, one or two deliberate signature details. State the direction
      in one sentence, then build to it. If the user seems to want more than a
      quick sketch, offer the vibes interview (`penpot-project-vibes`) instead
      of guessing."
- [x] Existing-system exception is structural (nudge only fires with no doc) —
      but ALSO skip when the file already has token sets/components (a design
      system without a doc); check is one read of the file summary already in
      the turn's context
- [x] Once per conversation: reuse/extend the playbook-nudge atom pattern;
      reset on new-chat/load-chat
- [x] "✦ Direction nudge" transcript note (mirror "✦ Playbook loaded") so the
      behavior is observable
- [x] Lint + typecheck pass (kondo; `compile test` + run; `compile main` —
      ai_panel not covered by test build)
- [ ] Live sanity (needs user key): build ask on a fresh no-doc file → nudge
      fires once; same ask on a file WITH DESIGN.md → silent
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (e.g. `:sparkles: Visual-direction nudge
      on undocumented build requests`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — `match-playbook` result
  shape, `user-content` nudge block
- `frontend/src/app/main/data/workspace/agent_tools.cljs` or `agent.cljs` —
  once-per-conversation atom (follow whichever owns the playbook one)
- `frontend/test/frontend_tests/data/agent_test.cljs` — user-content/trigger tests

## Notes

- CACHE RULE: the nudge must NEVER touch `build-system-prompt` — it is
  conditional per file/turn and would rewrite the whole prefix at 1.25×.
- Which catalog names count as "build playbooks": derive from the skill
  category/name prefix (`penpot-build-*`, component-factory, foundations…) —
  decide the exact predicate at Before Start against the current catalog.

- Once-per-conversation came FREE: match-playbook only runs when `prior` is empty, so no atom/reset wiring was needed (the plan's atom item is moot).
- Live sanity (nudge fires on bare file, silent with DESIGN.md) DEFERRED to the next live build session — the trigger predicate is fully unit-tested (4 tests).
