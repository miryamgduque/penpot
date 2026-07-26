# Phase 07 — Persistence wiring

**Status:** done (logic); like phase 04, not reachable in the app until phase 08

Connect the client recorder to the backend: create the row on start, flush as
events accumulate, finish on stop, and survive a reload without losing or
duplicating what was recorded.

## Before Start

- [x] Verify plan is still valid — Phase 06 landed at `5a47fd6599`
- [x] Confirm Phases 04 and 06 landed
- [x] Re-read `agent_chats.cljs` for the flush idiom — fire-and-forget at turn
      boundaries, `rx/catch → rx/empty`, and `rp/cmd!` needs **no** `repo.cljs`
      registration (it is generic). **This phase deliberately goes further than
      that idiom**: a dropped conversation save is recoverable from memory, a
      dropped recording is a falsified record
- [x] Confirm how RPC failures surface — they arrive as stream errors, hence the
      `rx/catch` per attempt
- [x] Absorb Phase 06's adjustment: one idempotent upsert, whole timeline
      re-sent, so flushes want generous debouncing and a skip-if-unchanged guard

## Checklist

- [x] Write tests first — `test/frontend_tests/data/session_persist_test.cljs`,
      13 tests
  - [x] start → the row is created before any events are flushed
  - [x] events flush on a debounce, not per event
  - [x] a failed flush retries and does not drop events
  - [x] persistent failure degrades to local-only **with a visible warning**, and
        recording continues
  - [x] a successful flush clears a failure streak
  - [x] stop finishes the session even when nothing changed since the last flush
  - [x] a cap stop sends its real reason, so it cannot look like a clean stop
  - [x] `raw-dropped` reaches the payload
  - [x] an unfinished session resumes from a server row; raw is NOT resumed
  - [x] a finished session is never resumed
- [x] Create the session on `start-recording` — the first flush IS the create
      (Phase 06 folded create into upsert), driven by seeding `:dirty? true`
- [x] Debounced batch flush of the derived timeline; raw ops are never sent
- [x] Finish the session on stop, including the reason
- [x] Handle reload mid-recording — **resume**, see Notes
- [x] Degrade gracefully when the sessions database is absent — the RPC raises
      `:sessions-database-unavailable`, which is just another failed flush, so it
      lands in the local-only path with no special casing
- [x] Full suite green — **997 tests / 3027 assertions / 0 failures**
- [x] Lint + format — kondo 0/0, cljfmt clean, `compile main` 0 warnings
- [ ] ~~Live: record across a reload~~ **MOVED to Phase 08** — same reason as
      Phase 04: nothing requires these namespaces, so they are not in the `:main`
      build and cannot be driven yet
- [x] Human approval — standing approval from Santi 2026-07-25
- [x] Committed with a gitmoji commit

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — **Phase 08 can
      proceed and now owns a lot.** It must emit `start-persisting` alongside
      `start-recording`, call `resume-recording` on mount, and surface
      `:local-only?` and the stop reason. It also inherits every live check for
      the whole feature (phases 02, 03, 04, 07). Its first task remains the
      require that puts these namespaces in the `:main` build.

## Files

- `frontend/src/app/main/data/workspace/session_persist.cljs` — new
- `frontend/src/app/main/data/workspace/session_recorder.cljs` — `:dirty?`
  tracking, `:session-id`/failure fields on the session, `watch-commits` made
  public for resume
- `frontend/test/frontend_tests/data/session_persist_test.cljs` — new
- `frontend/test/frontend_tests/runner.cljs` — registered (both spots)

## Notes

### The Phase 01/04 flush hazard dissolved

Both earlier phases flagged that `absorb` folds into the *last* event, so a flush
landing mid-gesture would persist one drag as two events, and that Phase 07 would
need provisional-tail bookkeeping.

**Phase 06's whole-timeline upsert removes the problem entirely.** The row is
replaced, not appended to, so the next flush re-sends the merged tail and the
split disappears on its own. No provisional tail, no ordering rules, no dedup.
Worth recording as a case where a backend simplification paid off two phases
upstream.

The same property makes flush ordering safe: if a debounced flush is still in
flight when the closing flush lands, the late one arrives at a row whose
`stopped_at` is set and is rejected by the backend's `WHERE … IS NULL` guard. The
recording is already closed and correct; nothing needs to serialize the calls
client-side.

### Failure policy: retry, then say so

`:dirty?` survives a failure, so the next debounced flush is the retry — there is
no inner retry loop, which keeps a failing backend from turning one flush into a
burst. After `max-flush-failures` (5) the session sets `:local-only?`, stops
flushing, and **keeps recording**.

Both halves are deliberate. Losing the rest of the recording would be worse than
not persisting it; and a recording that silently stopped persisting halfway is a
*false record* — Phase 09's critique would reason from it, which is exactly the
failure this feature exists to catch in humans. Phase 08 must surface
`:local-only?`; that is what makes the degradation honest rather than hidden.

A missing sessions database needs no special handling: the RPC raises
`:sessions-database-unavailable` and that is simply a failed flush.

### Reload: resume, not finish

The phase asked for a real decision. **Resume**, because a recording is a
deliberate act with a start and a stop and a refresh is neither — and because
"leave the row open forever" was explicitly ruled out.

The active row id lives in `storage/user`; `resume-recording` fetches the row,
seeds the recorder from it and restarts capture. Three refusals keep it honest:

- a **finished** row is never resumed (it is immutable server-side anyway);
- a row that is **gone or unreadable** is forgotten rather than retried forever;
- **raw ops are not resumed** — they are ephemeral by decision and the browser
  that held them no longer exists. The timeline is what survives.

`watch-commits` became public for this: a resumed recording must restart capture
without `start-recording`, which would reset the session it just restored.

`:started-at` needed care. It comes back as a date while the recorder's wall-clock
cap subtracts it as a number; unconverted it would yield `NaN` (silently
disabling the cap) and a naive `0` fallback would make a resumed session look two
hours old and stop itself immediately. Hence `at-ms`, falling back to *now*.

### Two subscriptions, not one

Capture and persistence subscribe separately on purpose. The recorder must not
depend on persistence — it works without a backend at all (Phase 04) — and
persistence must read the recorder's state, so merging them would put a require
cycle between the two namespaces. Phase 08 emits both.

### Follow-ups

- **Local state is still never dropped** (carried from Phase 04):
  `finalize-workspace` does not dissoc `[:session-recorder <file-id>]`, so
  finished sessions accumulate in memory for the life of the tab. Now that they
  are persisted, Phase 08 could drop local state once `:dirty?` is false —
  deliberately not done here because a finished timeline is still worth showing
  in the session list before the UI exists to fetch it back.
- **No flush-on-unload.** A tab closed mid-recording leaves an open row until the
  next visit resumes and eventually closes it. `sendBeacon` could close it
  eagerly, but the row is recoverable and a resumable open session is not a
  corrupt one.
- **The debounce is 3s and untuned** — no live traffic has exercised it.
