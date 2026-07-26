# Phase 02 — Human attribution

**Status:** done (code); live two-session verification outstanding → Phase 04

Make a commit say *who made it*. This is the phase the whole feature rests on:
if attribution is wrong, the eventual critique blames the wrong person, which is
worse than having no recording at all.

## Before Start

- [x] Verify plan is still valid — now on its own branch
      `feature/ai-skills-prototype-session-recording`; Phase 01 landed at
      `2e480b3c49`
- [x] Re-read `notifications.cljs:234-271` — **confirmed verbatim.** The schema
      requires `[:profile-id ::sm/uuid]` (`:238`) and `[:session-id ::sm/uuid]`
      (`:240`), and `handle-file-change` destructures only
      `{:keys [file-id changes revn vern]}` (`:249`), so both are discarded
      before the commit is built (`:265-271`)
- [x] Re-read `changes.cljs:161-197` — `commit` destructuring and commit map
      confirmed unchanged since Phase 01
- [x] Confirm `cf/session-id` (`config.cljs:116`, one uuid per browser tab) and
      that it lands in state at `main.cljs:74`. `profile-id` lives at
      `(:profile-id state)`, set in `profile.cljs:54` — the same accessor
      `notifications.cljs:54,92` already uses
- [x] Backend already sends attribution outward
      (`files_update.clj/send-notifications!` publishes `:profile-id` and
      `:session-id` on the `:file-change` message) — **no backend change needed**

### DRIFT: the plan's "echo" subtlety is false

The phase notes warned that the backend echoes our own writes back to us as
`:source :remote` carrying our own session-id, and that those must not be
double-recorded. **That does not happen.** The `:subscribe-file` websocket
handler builds its channel with
`:xf (remove #(= (:session-id %) session-id))`
(`backend/src/app/http/websocket.clj:153-155`), so a client never receives a
message originating from its own session. Every `:source :remote` commit that
reaches `handle-file-change` genuinely came from a different session.

No dedup is needed, and the "is this our own echo?" test is dropped as testing
a condition the transport makes impossible. What replaces it is the distinction
that actually matters:

**`session-id` is per browser tab, not per person** (`config.cljs:116` runs once
per page load). One person with the file open twice is two sessions, and tab B
*does* receive tab A's changes as remote. That is correct for a recording — it
really was a different session — but it means attribution is the
`(profile-id, session-id)` pair, and neither the recorder nor the critique may
assume one session per participant.

## Checklist

- [x] Write tests first: new `frontend/test/frontend_tests/data/changes_provenance_test.cljs`
  - [x] a local commit carries the current profile-id and session-id
  - [x] a remote commit carries the ORIGINATING profile-id/session-id, not the local one
  - [x] ~~a remote commit from the local session-id (echo of our own write) is
        identifiable as such~~ **DROPPED** — the transport makes this impossible;
        see the DRIFT note above. Testing it would pin a fiction
  - [x] absent attribution keeps the keys present with nil values, so consumers
        distinguish "unattributed" from "key absent" without nil-punning
  - [x] the commit map stays purely additive (regression guard listing all 17
        pre-existing keys — four subscribers read this map)
- [x] Add `:profile-id`/`:session-id` to the `commit` event map in `changes.cljs`
- [x] Stamp local commits from app state in `commit-changes`
- [x] Forward `profile-id`/`session-id` through `handle-file-change` in `notifications.cljs` into the commit it builds
- [x] Verify no existing `dch/commit?` subscriber breaks on the widened map
      (`persistence.cljs:217,255`, `workspace.cljs:455,509`) — all four
      destructure named keys, none enumerate or assert the full map; pinned by
      `commit-map-keeps-its-pre-existing-shape`
- [x] Full suite green — **951 tests / 2931 assertions / 0 failures**
- [x] Lint + format — kondo 0/0, cljfmt clean
- [ ] **Two-browser live verification — STILL OUTSTANDING.** See "Live
      verification debt" below. Not done: the Claude-in-Chrome extension was
      unreachable for the whole phase
- [x] Human approval — standing approval from Santi 2026-07-25 ("I will review it
      later once everything is done")
- [x] Committed with a gitmoji commit

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — **Phase 03 can
      proceed and is unaffected.** It adds the agent's ambient marker on the same
      commit map this phase widened; the two are additive and touch different
      keys (`:who`/`:provider`/`:model` vs `:profile-id`/`:session-id`). One
      consequence worth carrying: an agent-driven commit still goes through
      `commit-changes`, so it now carries the *operating user's* profile-id AND
      will carry `:who :agent` — which is correct and useful (an agent action is
      attributable to the person who asked for it), but Phase 03 must not
      overwrite `:profile-id` with nil when it stamps the agent marker.

## Files

- `frontend/src/app/main/data/changes.cljs` — widen the commit map with provenance
- `frontend/src/app/main/data/workspace/notifications.cljs` — stop discarding remote attribution
- `frontend/test/frontend_tests/data/session_events_test.cljs` — provenance assertions

## Live verification debt (carried into Phase 04)

The two-browser check did not happen: the Claude-in-Chrome extension was
unreachable for the whole phase (a known flake in this environment). **Do not
treat remote attribution as live-proven.**

What was done instead — trace every link of the remote chain in source, and note
that each one is either schema-enforced or now pinned by a test:

| Link | Evidence |
|---|---|
| client sends `:session-id` on `update-file` | required (not optional) in the RPC schema, `files_update.clj:62,82`; sent from `persistence.cljs:132,137` |
| backend stamps the authenticated `:profile-id` into params | `files_update.clj:166` (`::rpc/profile-id`) |
| both published on the `:file-change` message | `files_update.clj:471,473` in `send-notifications!` |
| a client never receives its own session's echo | `websocket.clj:153-155`, `:xf (remove #(= (:session-id %) session-id))` |
| frontend inbound schema requires both | `notifications.cljs:238,240` |
| forwarded into the commit map | **this phase**, pinned by `handle-file-change-forwards-remote-attribution` |
| read back out into an event | Phase 01, pinned by `commit->event-preserves-explicit-attribution` |

This is stronger evidence than one observed session for the *shape* of the data,
but it cannot catch an integration surprise — e.g. a msgbus transform dropping a
key in transit. **Phase 04 needs a live browser anyway** (it verifies a real
subscription against real edits), so the two-session attribution check folds into
it naturally: open the file in two sessions, edit from each, assert each side
records the other's profile-id. Keep it on Phase 04's checklist.

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
