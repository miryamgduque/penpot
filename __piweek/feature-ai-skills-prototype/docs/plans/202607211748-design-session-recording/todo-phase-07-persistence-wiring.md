# Phase 07 — Persistence wiring

**Status:** todo

Connect the client recorder to the backend: create a session on start, flush
events as they accumulate, finish on stop, and survive a reload without losing
or duplicating what was recorded.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Confirm Phases 04 and 06 landed
- [ ] Re-read `frontend/src/app/main/data/workspace/agent_chats.cljs` for the flush idiom — fire-and-forget saves at turn boundaries, optimistic local state, no server quota
- [ ] Confirm how RPC failures surface today so a failed flush degrades the same way

## Checklist

- [ ] Write tests first
  - [ ] start → a session is created before any events are flushed
  - [ ] events flush in batches on a debounce, not per event
  - [ ] a failed flush retries and does not drop events
  - [ ] a failed flush that keeps failing degrades to local-only with a visible warning, and recording continues
  - [ ] stop finishes the session even if a flush is in flight
  - [ ] replayed batches do not duplicate (client-generated event ids)
- [ ] Create the session on `start-recording`
- [ ] Debounced batch flush of the derived timeline (raw ops are never sent — ephemeral by decision)
- [ ] Finish the session on `stop-recording`, including the stop reason (manual / cap / file closed)
- [ ] Handle reload mid-recording: either resume the open session or finish it cleanly — decide and implement, do not leave it dangling
- [ ] Degrade gracefully when the recorder DB is absent (Phase 05 made it optional; the client must match)
- [ ] Full suite green
- [ ] Lint + format
- [ ] Live: record a session across a reload, confirm the server timeline matches what the client showed
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/session_recorder.cljs` — flush lifecycle
- `frontend/src/app/main/repo.cljs` — only if new command wiring is needed
- `frontend/test/frontend_tests/data/session_recorder_test.cljs` — flush + failure assertions

## Notes

**Do not lose events on a failed flush, and do not silently keep them either.**
The honest behaviour is: retry, and if it keeps failing, tell the user the
recording is local-only from this point. A recording that quietly stopped
persisting halfway is a false record, and Phase 09 would critique from it.

The reload case deserves a real decision rather than a default. A recording is a
deliberate act with a start and a stop; a browser refresh is not a stop. Either
resume (session id in local storage, keep appending) or finish-on-unload — both
defensible, but "leave it open forever" is not, because it produces sessions
that never end and never get reviewed.

Two people recording the same file simultaneously produces two sessions
containing largely the same events (each browser sees all collaborators'
changes). The server can now see that overlap. Decide whether to dedup, merge,
or simply allow it and let the reviewer pick a session. Allowing it is fine for
the prototype — but say so in the UI in Phase 08 rather than letting it look
like a bug.
