# Phase 04 — Client recorder

**Status:** done (logic); NOT yet reachable in the running app → Phase 08

The recorder itself: start/stop lifecycle, the subscription, in-memory buffers,
and the caps that keep a busy session from becoming unusable.

**Important scope correction, found while closing the phase:** the phase claimed
that at its end "the feature works entirely client-side and is drivable from the
browser console". It is not. Nothing in the app requires
`session-recorder`, so shadow-cljs does not include it in the `:main` build
(verified: `resources/public/js/cljs-runtime/app.main.data.workspace.session_recorder.js`
is absent, while `session_events.js` is present because `ai_panel` requires it).
Console driving is therefore blocked on a caller existing, not on the browser —
and the first caller is Phase 08's record control. Every live item has moved
there. Phase 04 delivers the tested logic.

## Before Start

- [x] Verify plan is still valid — Phase 03 landed at `453f007e60`
- [x] Confirm Phases 01–03 landed (schema, human attribution, agent attribution)
- [x] Re-read the subscriber pattern at `persistence.cljs:217` and
      `workspace.cljs:455` — matched: `(rx/filter dch/commit?)` → `rx/map deref`
      → `rx/take-until stopper`
- [x] Confirm how workspace-scoped streams are torn down so a recording cannot
      outlive its file — `::dw/finalize-workspace` is the workspace-wide stopper
      (`workspace.cljs:181,306,352`, public `defn` at `:529`). Requiring
      `app.main.data.workspace` from a `workspace/*` ns is established practice
      (`shortcuts`, `versions`, `libraries`, `notifications` all do it) and
      `data.workspace` requires neither `ai_panel` nor the session namespaces, so
      there is no cycle — confirmed by a clean test-build compile

## Checklist

- [x] Write tests first — `test/frontend_tests/data/session_recorder_test.cljs`
  - [x] `start-recording` seeds a session (id, file-id, started-at) and sets active state
  - [x] starting with no file open records nothing
  - [x] commits arriving while inactive are ignored
  - [x] commits arriving while active are filtered (`recordable?`) then coalesced
  - [x] a commit for a DIFFERENT file is ignored (the stream is global)
  - [x] `stop-recording` finalizes and leaves the timeline readable
  - [x] hitting the event cap stops recording and records the reason, rather than
        silently truncating
  - [x] raw buffer is bounded, discloses what it dropped, and is dropped at stop
  - [x] a rolling raw buffer does NOT end the recording
  - [x] a session past the wall-clock ceiling stops with `:time-cap`
  - [x] stopping twice keeps the FIRST reason (a cap stop must not be relabelled
        by the teardown stop that follows it)
- [x] Implement `data/workspace/session_recorder.cljs`: `start-recording`,
      `stop-recording`, `toggle-recording`, stream subscription with
      `rx/take-until` teardown
- [x] Keep raw ops in a bounded buffer; keep the derived timeline as the durable
      structure
- [x] Enforce caps: max events, max raw ops, and a wall-clock ceiling — each with
      an explicit, user-visible reason on stop
- [x] Ensure teardown on file close — `::dw/finalize-workspace` both unsubscribes
      and emits `(stop-recording :file-closed)`
- [x] Full suite green — **984 tests / 2997 assertions / 0 failures**
- [x] Lint + format — kondo 0/0, cljfmt clean, `compile main` 0 warnings
- [ ] ~~Console-drive a real recording in devenv~~ **MOVED to Phase 08** — the ns
      is not in the `:main` build until something requires it
- [ ] ~~INHERITED FROM PHASE 02 — two-session attribution check~~ **MOVED to
      Phase 08**
- [ ] ~~INHERITED FROM PHASE 03 — agent-vs-human attribution live~~ **MOVED to
      Phase 08**
- [x] Human approval — standing approval from Santi 2026-07-25
- [x] Committed with a gitmoji commit

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — **Phase 05 (separate
      database) can proceed and is independent of everything so far.** It is the
      highest-unknown phase in the plan and starts with a spike; nothing in
      phases 01–04 constrains it. Worth noting Phase 07 will need
      `absorb`-before-flush (see below).

## Files

- `frontend/src/app/main/data/workspace/session_recorder.cljs` — new; lifecycle,
  subscription, buffers, caps
- `frontend/src/app/main/data/workspace/session_events.cljs` — `coalesce`
  refactored onto a new public `absorb`
- `frontend/test/frontend_tests/data/session_recorder_test.cljs` — new, 20 tests
- `frontend/test/frontend_tests/runner.cljs` — registered (both spots)

## Notes

### `coalesce` was refactored onto `absorb`, not duplicated

The recorder needs coalescing *incrementally* (one commit at a time, so the live
timeline and Phase 07's flush are always current), while Phase 01 had it in bulk
via `partition-by`. Rather than keep two implementations of the same rules, both
now go through one public `se/absorb`, and `coalesce` is
`(reduce absorb [] commits)`.

This is behaviour-preserving — all of Phase 01's coalescing tests pass unchanged
— and strictly simpler: comparing against the *last event* gives adjacency for
free, which removed the `[::ungrouped idx]` discriminator that existed only to
stop nil undo-groups colliding under `partition-by`.

One real bug surfaced in the process: `merge-events` set `:commits (count
events)`. That is correct when folding a fresh run of 1-commit events, but an
incremental absorb merges an *already-merged* event with a new one, so a
three-commit gesture would have reported 2. It now sums. Pinned by
`one-gesture-coalesces-into-one-event`.

### Cap philosophy

The two ceilings behave differently on purpose:

- **Event cap ends the recording** (`:stop-reason :event-cap`). The timeline is
  the durable record, so it must never silently lose its tail.
- **Raw cap only rolls the buffer** and counts the drop in `:raw-dropped`. Raw is
  ephemeral by decision and the timeline has already absorbed whatever rolls off.

Both are disclosed. Nothing truncates in silence.

The time ceiling is checked when the next commit arrives rather than on a timer:
an idle recording is capturing nothing, so there is nothing to protect against in
the interim. The consequence — a session left open past the ceiling reads as
active until something happens — is documented at `max-duration-ms`.

`trim-raw` and `absorb-into` are pure and public so the cap rules are testable
without driving thousands of events through the store. The first draft of these
tests fed `max-raw + 5` commits through `ptk/update`; testing the pure functions
directly is both faster and more precise about what is being asserted.

### Follow-ups

- **Phase 07 must coalesce before flushing, not after** (carried from Phase 01
  and now concrete): `absorb` folds into the *last* event, so if a flush cuts a
  gesture in half, that gesture persists as two events. Flush whole events, and
  treat the tail event as provisional until its undo-group can no longer grow.
- **Session state is never dropped from app-db.** `finalize-workspace` does not
  dissoc `[:session-recorder <file-id>]`, so recordings accumulate in memory
  across files for the life of the tab. Harmless for a prototype (and it keeps a
  finished timeline readable after closing the file), but Phase 07 should drop
  local state once a session is safely persisted.
- **`:fix-obj` / `:reg-objects` / `:assign` classification** (from Phase 01)
  remains unresolved and now needs real traffic to settle — which needs Phase 08.
