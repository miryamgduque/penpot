# Phase 06 — Session RPC

**Status:** done — tested through the real harness AND probed live

The commands the client will call, and the authorization that decides who may
read a recording.

## The authz question, answered by Santi (2026-07-25)

The phase deliberately left this open because it is a product decision, and
offered three options (anyone who can edit / only the recorder / team admins).
**Santi's answer was none of them exactly:**

> "The session is intended to be read by a bot. Something admins can export to
> feed agent sessions."

That reframes the surface. A recording is not primarily something people browse —
it is **material an admin hands to an agent**. So it splits three ways, and each
split is pinned by a test:

| Operation | Who | Why |
|---|---|---|
| **write** (`::upsert-design-session`) | the recorder, with EDIT permission on the file | you may record what you may edit; a viewer must not be able to record |
| **read** (`::get-design-session`, `::get-design-sessions`) | team **admins**, plus the recorder's own sessions | admins are the audience; the recorder can still see what they captured |
| **export** (`::export-design-sessions`) | **admins only** | the bot-facing bulk path — a plain editor may read their own sessions but must not harvest everyone's |

Owners count as admins (`teams/get-permissions` folds `is-owner` into
`is-admin`). Failures are `:not-found`, not `:forbidden`, so a session id is not
probeable.

## Before Start

- [x] Verify plan is still valid — Phase 05 landed at `aa3dd787bd`
- [x] Confirm the recorder DB is reachable in devenv — yes, verified in Phase 05
- [x] Re-read `agent_chats.clj` for the house style — `::sm/` schemas,
      `::rpc/profile-id` from session, `db/tjson`, and the parameterized
      `ON CONFLICT` upsert whose update arm re-checks ownership (`:100-108`)
- [x] Decide the authorization rule — **answered by Santi**, see above
- [x] Confirm how a new RPC ns is registered (`sv/scan-ns` in `rpc.clj`) and that
      the backend needs `(user/restart)` for new commands to appear

## Checklist

- [x] Write backend tests — `rpc_design_sessions_test.clj`, through the real
      `th/command!` harness: **6 tests / 24 assertions / 0 failures**
  - [x] create → update → finish round-trips
  - [x] appending to a finished session is rejected (immutability)
  - [x] a caller without file access cannot write
  - [x] an outsider gets `:not-found` on read, so ids are not probeable
  - [x] export is admin-only, and rejects an outsider
  - [x] export can select explicit ids, or take everything
  - [x] idempotent on replay
  - [x] `raw-dropped` round-trips, so a partial raw record announces itself
- [x] `::upsert-design-session` — see Notes on why this replaced
      create/append/finish
- [x] `::get-design-sessions {:file-id}` — metadata only, never the timeline
- [x] `::get-design-session {:id}` — full timeline
- [x] `::export-design-sessions {:file-id :ids?}` — the bot-facing bundle
- [x] Enforce the authorization rule on every command
- [x] Register the ns; restart the backend and confirm the commands resolve
- [x] Lint + `cljfmt` — 0/0 on all new files; `helpers.clj`'s 20 warnings are
      pre-existing (verified by linting it with my change stashed)
- [x] Human approval — standing approval from Santi 2026-07-25
- [x] Committed with a gitmoji commit

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — **Phase 07 needs one
      adjustment.** It was written around "create → append → finish"; the RPC is
      a single idempotent upsert instead, which makes the flush *simpler* (no
      dedup, no per-batch id tracking) but means the client re-sends the whole
      timeline each flush. The flush should therefore be debounced generously and
      skip when the timeline has not changed. Phase 07 must also send
      `raw-dropped` and set `stop-reason` on the final flush — that last call is
      what closes the session, and nothing can write to it afterwards.

## Files

- `backend/src/app/rpc/commands/design_sessions.clj` — new; four commands
- `backend/src/app/rpc.clj` — registered in `scan-ns`
- `backend/src/app/main.clj` — sessions pool injected into `:app.rpc/methods`
- `backend/test/backend_tests/rpc_design_sessions_test.clj` — new
- `backend/test/backend_tests/helpers.clj` — test system points at
  `penpot_sessions_test`
- `docker/devenv/files/postgresql_init.sql` — creates `penpot_sessions_test`

## ⚠ Existing devenvs need one manual step

`postgresql_init.sql` only runs when the Postgres volume is created, so an
**existing** devenv will not have the new databases and the backend suite will
fail to init its system. One-off fix:

```
docker exec penpotdev-infra-postgres-1 psql -U penpot -d postgres \
  -c "CREATE DATABASE penpot_sessions OWNER penpot;" \
  -c "CREATE DATABASE penpot_sessions_test OWNER penpot;"
```

Both are already created in Santi's ws0. This follows the same pattern
`penpot_test` has always had, but it is a real trap for anyone else on the branch.

## Notes

### One idempotent upsert, not create/append/finish

The phase specified three write commands. It ships as one, matching
`::upsert-agent-chat`: the client owns a bounded timeline (capped at 5000 events,
raw ops never sent) and re-sends it, so a retried or replayed flush cannot
duplicate anything. Idempotency comes free from replacing rather than appending,
which is a much smaller correctness surface than append-plus-dedup.

Immutability is preserved where it matters: the update arm's
`WHERE … stopped_at IS NULL` means a finished session cannot be reopened or
rewritten, and `stop-reason` arriving non-nil is what finishes it. A blocked
update affects zero rows, surfaced as `:not-found` — the same trick
`agent_chats` uses to make "not yours" and "does not exist" indistinguishable.

### Both pools, deliberately

There are no cross-database foreign keys (Phase 05), so no join can answer "may
this profile read this session?". Every handler therefore touches the **main**
pool for permissions and the **sessions** pool for data. This is the one place
the isolation is intentionally crossed, and it is why authorization is explicit
in each command rather than expressed in SQL.

The sessions pool reaches the handlers through `:app.rpc/methods` under
`app.main`'s own component key, referenced as a **literal keyword** rather than a
require — `app.main` pulls in the whole rpc tree, so requiring it from a command
namespace would cycle.

### Export is structured, not prose

`::export-design-sessions` deliberately does not render a transcript. The events
are already semantic (`:kind`, `:label`, `:who`, `:model`, `:at`), so a consumer
can feed them straight to an agent, and rendering server-side would mean a second
copy of `session-events/timeline->prompt-text` in Clojure that could drift from
the frontend's. One definition, in CLJS, where the panel also needs it.

### Live probe findings

Before the harness tests existed, the commands were probed against the **running
dev system** over nREPL. Three things worth recording:

- **The rejection paths were confirmed live**: outsider write, read and export all
  returned `:object-not-found` against real profiles and a real file.
- **`:app.rpc/methods` is keyed by SIMPLE keyword** (`:upsert-design-session`),
  not the qualified `::` name, and each value is `[mdata method-fn]` — see
  `th/command!`. A first probe using qualified keywords reported
  "method-not-registered" when the methods were in fact registered.
- Invoking a handler directly in the **dev** system hits audit middleware that
  wants a real `yetti` request (`get-header` on nil). The test harness redefines
  `audit/submit` to a no-op, which is why `th/command!` works and a raw dev-system
  call does not. Use the harness for behaviour; use the dev probe only for
  authorization smoke checks that fail before the middleware.

### Pre-existing suite failures, verified not mine

The full backend suite reports **17 failures**, all pre-existing:

- 3 in `util-ssrf-test` — **environmental**: the container has no DNS, so
  `example.com` does not resolve and `safe-url?` correctly returns false
  (`getent hosts example.com` → nothing).
- 14 in `rpc-profile-skills-test` — fail identically with my `helpers.clj` change
  stashed, so unrelated to this phase.

Attribution was established by reverting only `helpers.clj` and re-running, not
by assumption. Worth a separate look, but out of scope here.

### Follow-ups

- **No per-file quota** on sessions (same gap `profile_agent_chat` has).
- **No reaper** for sessions whose file or profile is deleted — cross-database
  cascades do not exist (Phase 05).
- **`review` column is unused so far** — Phase 09 writes it.
- `rpc-profile-skills-test`'s 14 failures deserve their own investigation.
