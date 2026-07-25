# Phase 06 — Session RPC

**Status:** todo

The commands the client will call: create a session, append events, finish it,
list and fetch. Modelled on `agent_chats.clj`, but file-scoped rather than
profile-scoped — every collaborator's events belong to one session.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Confirm Phase 05 landed and the recorder DB is reachable in devenv
- [ ] Re-read `backend/src/app/rpc/commands/agent_chats.clj` for the house style: `::sm/` schemas, `::rpc/profile-id` from session, `db/tjson` encoding, the idempotent-upsert pattern at `:100-108`
- [ ] Decide the authorization rule (see Notes) — this is a product decision, settle it before coding
- [ ] Confirm how a new RPC ns gets registered (`scan-ns` in `rpc.clj`) and that the backend needs `(user/restart)` via nREPL 6064 for new commands to appear

## Checklist

- [ ] Write backend tests first (kaocha, DB-less where possible — see `backend-tests.agent-web-test` precedent for the pattern)
  - [ ] create → append → finish round-trips
  - [ ] appending to a finished session is rejected
  - [ ] a caller without access to the file cannot read or write a session
  - [ ] append is idempotent on replay (client-generated event ids)
  - [ ] oversized batches are rejected with a clear error, not truncated
- [ ] `::create-session {:file-id}` → session id
- [ ] `::append-session-events {:session-id :events}` — batch, idempotent
- [ ] `::finish-session {:session-id}` — marks complete, records the stop reason
- [ ] `::get-sessions {:file-id}` — metadata only, newest first
- [ ] `::get-session {:id}` — full timeline
- [ ] Enforce the authorization rule on every command
- [ ] Register the ns; restart the backend via nREPL and confirm the commands resolve
- [ ] Lint + `cljfmt`
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `backend/src/app/rpc/commands/design_sessions.clj` — new; the five commands
- `backend/test/backend_tests/design_sessions_test.clj` — new
- `backend/src/app/rpc.clj` — register the ns in `scan-ns`

## Notes

**Authorization is the open product question and it is not small.** A session
contains a record of identifiable people's actions on a shared file. Candidate
rules, pick one explicitly:

1. Anyone who can edit the file can read its sessions (simplest, matches the
   collaborative premise, and means teammates can review each other).
2. Only the person who started the recording can read it (most private, but
   undercuts "the team reviews the session together").
3. Team admins plus the recorder.

The authz bug found in the earlier branch review (app-scope skills were
writable by any authed user) is the cautionary precedent: decide the rule, then
pin it with a test that a non-member is rejected.

The recorder DB has **no foreign key** to `file` or `profile` (Phase 05), so
these commands cannot lean on a join to check access — they must check against
the main pool explicitly. That means these handlers touch *both* pools: main for
the permission check, recorder for the data. Worth being deliberate about, since
it is the one place the isolation is deliberately crossed.

Size: the client batches events. Set an explicit per-batch ceiling and reject
above it — the payload cap has bitten this branch twice already.
