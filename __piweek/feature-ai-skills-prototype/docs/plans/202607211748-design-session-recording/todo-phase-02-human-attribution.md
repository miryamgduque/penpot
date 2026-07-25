# Phase 02 — Human attribution

**Status:** todo

Make a commit say *who made it*. This is the phase the whole feature rests on:
if attribution is wrong, the eventual critique blames the wrong person, which is
worse than having no recording at all.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `frontend/src/app/main/data/workspace/notifications.cljs:234-271` — confirm the inbound schema still requires `profile-id`/`session-id` and that `handle-file-change` still drops them
- [ ] Re-read `frontend/src/app/main/data/changes.cljs:161-197` — confirm the `commit` destructuring and the commit map keys
- [ ] Confirm `cf/session-id` (`frontend/src/app/config.cljs:116`) and how it lands in state (`frontend/src/app/main.cljs:74`)
- [ ] Check the backend already echoes attribution outward (`backend/src/app/rpc/commands/files_update.clj:258-259,471-484`) so no backend change is needed here

## Checklist

- [ ] Write tests first: extend `frontend/test/frontend_tests/data/session_events_test.cljs` (or a new `changes_provenance_test.cljs`)
  - [ ] a local commit carries the current profile-id and session-id
  - [ ] a remote commit carries the ORIGINATING profile-id/session-id, not the local one
  - [ ] a remote commit from the local session-id (echo of our own write) is identifiable as such
  - [ ] absent attribution degrades to a stable `:unknown` marker rather than nil-punning into "user"
- [ ] Add `:profile-id`/`:session-id` to the `commit` event map in `changes.cljs`
- [ ] Stamp local commits from app state in `commit-changes`
- [ ] Forward `profile-id`/`session-id` through `handle-file-change` in `notifications.cljs` into the commit it builds
- [ ] Verify no existing `dch/commit?` subscriber breaks on the widened map (`persistence.cljs:217,255`, `workspace.cljs:455,509`)
- [ ] Full suite green (regression risk is real — this touches the pipeline every mutation uses)
- [ ] Lint + format
- [ ] **Two-browser live verification** (see Notes) — unit tests cannot prove this
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/changes.cljs` — widen the commit map with provenance
- `frontend/src/app/main/data/workspace/notifications.cljs` — stop discarding remote attribution
- `frontend/test/frontend_tests/data/session_events_test.cljs` — provenance assertions

## Notes

**This phase changes a hot path.** Every mutation in Penpot goes through
`dch/commit`. Keep the change purely additive (new keys, no renames, no
reordering) and confirm the four existing subscribers are indifferent to extra
keys before touching anything else.

**Live verification is mandatory and cannot be faked with unit tests.** Two
browser sessions on the same file (a second profile, or the same profile in a
private window), edit from each, and confirm each side records the *other's*
profile-id on the incoming events. Historic gotcha from past sessions: drive
Penpot through real Chrome, not the Browser pane, and force module refresh with
`fetch('/js/cljs-runtime/<ns>.js', {cache:'reload'})` + `location.reload()`
after a recompile, or the tab serves stale JS and you will "verify" the old
code.

A subtlety worth deciding here, not later: the backend echoes our own writes
back to us. Those arrive as `:source :remote` carrying *our* session-id. They
must not be double-recorded as if a second person did them.
