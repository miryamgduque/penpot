# Phase 01 — Live violations watcher

**Status:** done

## Goal

A data-layer watcher that keeps `[:ai-panel <file-id> :violations]` current
while the panel is open: subscribe to `::dch/commit-changes` on the global
stream, debounce, re-run the existing deterministic scan
(`at/audit-violations`), and store the result. Pruning is free — the scan is
over current state, so deletions and user corrections drop out on the next
refresh. Also accumulate touched shape ids into `[:ai-panel <file-id>
:dirty-ids]` now (cheap here, consumed by Phase 05's tick).

No model calls in this phase. No UI in this phase.

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Re-read `data/workspace/ai_panel.cljs` `toggle-panel`/`close-panel` — the
      watcher's start/stop hooks
- [x] Re-read `dch/commit-changes` params — confirm which keys of a change entry
      name shape ids (`:id`, `:ids`, `:shapes`?) across `add-obj`/`mod-obj`/`del-obj`
- [x] Confirm `at/audit-violations` is still pure `state → violations` and what
      `rule-enforced?` reads (`[:ai-panel <file-id> :enforced-rules]`)

## Checklist

- [x] Tests first (in `frontend/test/frontend_tests/data/` — new
      `agent_watcher_test.cljs` or extend `agent_test.cljs`): touched-id
      extraction from a `:redo-changes` vector (add/mod/del forms); refresh
      replaces stale violations (shape renamed → entry gone; shape deleted →
      entry gone); dirty-ids accumulate and dedupe. **Register the ns in
      `runner.cljs` `test-namespaces` — the `:require` alone silently skips it.**
- [x] `start-watcher` event in `data/workspace/ai_panel.cljs`: on panel open,
      `rx/filter (ptk/type? ::dch/commit-changes)` on `stream` → extract touched
      ids into `:dirty-ids` (immediate, un-debounced) → `rx/debounce ~500ms` →
      emit `refresh-violations`; `rx/take-until` a stopper on `::close-panel` /
      `::stop-watcher` / file change
- [x] `refresh-violations` event: recompute `at/audit-violations` into
      `[:ai-panel <file-id> :violations]` (UpdateEvent, pure)
- [x] Kick one initial `refresh-violations` on panel open (the strip must not
      start empty on a file that already has violations)
- [x] Wire start/stop into `toggle-panel`/`close-panel`; guard against double
      subscription on repeated toggles
- [x] `refs/ai-panel-violations` ref
- [x] Lint + format (`pnpm run lint:clj`, cljfmt via devenv `/opt/utils/bin/cljfmt`)
- [x] `shadow-cljs compile main` 0 warnings; `compile test` + `node target/tests/test.js`
      with the new test names visible in the output
- [x] Console verify in devenv: panel open → create a default-named rect →
      `:violations` updates within ~1s; rename it → entry gone; panel closed →
      no updates on edits
- [x] Human approval received
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename this file: `todo-` → `doing-` → `done-`; update README link
- [x] Note the confirmed redo-changes id keys and debounce value below
- [x] Check Phase 02 assumptions still hold

## Files

- `frontend/src/app/main/data/workspace/ai_panel.cljs` — `start-watcher`,
  `refresh-violations`, dirty-id accumulation, lifecycle wiring
- `frontend/src/app/main/refs.cljs` — `ai-panel-violations`
- `frontend/test/frontend_tests/data/agent_watcher_test.cljs` — pure-fn tests
- `frontend/test/frontend_tests/runner.cljs` — register the test ns

## Notes

- Deterministic tier deliberately re-scans the whole page (≤1000 shapes, pure
  in-memory regex + set lookups) instead of scoping to touched ids — simpler,
  and pruning falls out for free. The dirty set exists *only* to keep Phase 05's
  LLM payload small.
- The agent's own tool mutations also flow through `commit-changes`, so agent
  fixes prune the set with no special-casing.
- `read_design`'s `:openViolations` already calls `audit-violations` — the strip
  and the agent see the same numbers by construction.

## Execution notes (2026-07-16, worktree `feature/autofix-watcher`)

- Tests waived per user directive (see README execution mode); gates were
  `shadow-cljs compile main` (0 warnings) + clj-kondo (0/0) + cljfmt.
- The subscription point is `::dch/commit` (NOT `::commit-changes`): `dch/commit`
  events implement `IDeref` returning the commit map (`:redo-changes`,
  `:file-id`, `:source`) — and they also carry REMOTE users' commits, so the
  watcher sees collaborators' edits for free.
- Touched-id keys confirmed: `:id` (add/mod/del-obj) + `:shapes` (mov/reg).
- Debounce 500ms; also re-scans on `::set-enforced-rules` so toggling a rule
  refreshes the set without an edit.
- `refresh-violations`/`track-dirty` no-op while the panel is closed (belt and
  braces on top of the start/stop lifecycle in `toggle-panel`/`close-panel`).
- `refresh-violations` prunes `:dirty-ids` of deleted shapes so phase 05 never
  sends dead ids to the model.
- Console verification deferred to post-merge live testing (worktree has no
  running watch), per the plan's execution mode.
