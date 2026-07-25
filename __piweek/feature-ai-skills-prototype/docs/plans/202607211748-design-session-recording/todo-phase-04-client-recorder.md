# Phase 04 — Client recorder

**Status:** todo

The recorder itself: start/stop lifecycle, the subscription, in-memory buffers,
and the caps that keep a busy session from becoming unusable. No backend yet —
at the end of this phase the feature works entirely client-side and is drivable
from the browser console.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Confirm Phases 01–03 landed (schema, human attribution, agent attribution)
- [ ] Re-read the subscriber pattern at `frontend/src/app/main/data/persistence.cljs:217` and `workspace.cljs:455` — match the existing idiom for stream subscription and teardown
- [ ] Confirm how workspace-scoped streams are torn down on file close, so a recording cannot outlive its file

## Checklist

- [ ] Write tests first
  - [ ] `start-recording` seeds a session (id, file-id, started-at) and sets active state
  - [ ] commits arriving while inactive are ignored
  - [ ] commits arriving while active are filtered (`recordable?`) then coalesced
  - [ ] `stop-recording` finalizes and leaves the timeline readable
  - [ ] hitting the event cap stops recording and records the reason, rather than silently truncating
  - [ ] raw buffer is bounded and is dropped at stop (raw is ephemeral by decision)
- [ ] Implement `data/workspace/session_recorder.cljs`: `start-recording`, `stop-recording`, stream subscription with `rx/take-until` teardown
- [ ] Keep raw ops in a bounded buffer; keep the derived timeline as the durable structure
- [ ] Enforce caps: max events, max raw ops, and a wall-clock ceiling — each with an explicit, user-visible reason on stop
- [ ] Ensure teardown on file close / navigation (no leaked subscription)
- [ ] Full suite green
- [ ] Lint + format
- [ ] Console-drive a real recording in devenv: start, edit shapes by hand, run an agent turn, stop, read the timeline
- [ ] **INHERITED FROM PHASE 02 — two-session attribution check.** Phase 02
      shipped remote attribution but could not live-verify it (Chrome extension
      unreachable); it is code-traced and unit-tested only. Open the file in two
      sessions, edit from each, and assert each side records the OTHER's
      profile-id on its incoming events. This is the check that proves "record
      every person working on the file" actually works — do not close Phase 04
      without it
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/session_recorder.cljs` — new; lifecycle + subscription + buffers
- `frontend/test/frontend_tests/data/session_recorder_test.cljs` — new
- `frontend/test/frontend_tests/runner.cljs` — register the ns (both spots)

## Notes

**No silent caps.** When a ceiling is hit, the session must say so — an
unexplained truncation reads as "this is everything that happened" when it is
not, and the critique in Phase 09 would then reason from a false record. Same
principle the agent's own round-cap follows.

Console-driving is the verification style that has worked on this branch:
private CLJS fns are callable from the browser console
(`app.main.data.workspace.session_recorder.start_recording()`), and after a
recompile the tab serves stale module JS until you
`fetch('/js/cljs-runtime/<ns>.js', {cache:'reload'})` and reload.

Open question to settle here: is recording **per-browser** or **per-file**? A
per-browser recorder only sees what its own websocket delivers, which is fine
(it receives all collaborators' changes), but two people pressing Record
produces two overlapping sessions of the same events. Simplest coherent answer
is one active recording per file per browser, with the session id generated
client-side — and dedup deferred to Phase 06/07 where the server can see both.
