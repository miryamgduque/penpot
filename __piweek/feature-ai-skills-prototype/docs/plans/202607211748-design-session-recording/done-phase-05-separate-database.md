# Phase 05 — Separate database

**Status:** done — and **live-verified**, unlike phases 01–04

A second Postgres database on the existing server, isolated from Penpot's
product tables: its own logical DB, its own connection pool, its own migration
set. Highest-unknown phase in the plan.

The spike paid for itself twice (see Notes): it caught a design flaw that would
have broken Penpot's boot entirely, and it surfaced that `ig/assert-key`
validation is compiled out in this build.

## Before Start

- [x] Verify plan is still valid — Phase 04 landed at `86cdca8a64`
- [x] Re-read `main.clj:150-168` — the `::db/pool` component and the
      `:app.migrations/migrations` component that takes it
- [x] Read `db.clj` — `::db/pool`'s init-key params confirmed, and **the key
      finding for optionality**: `init-key` is wrapped in `(when uri ...)`
      (`db.clj:87`), so an unconfigured pool is *already* nil with no extra work.
      All of `schema:pool-options` is optional and `::db/name` already exists
      (default `:main`), so named multiple pools are anticipated by design
- [x] Read `migrations.clj` — **the machinery is already module-scoped and needs
      no changes.** `apply-migrations!` takes `[pool name migrations]`, `mg/setup!`
      creates a `migrations` bookkeeping table in whichever database the pool
      points at, and its `unique(module, step)` is all that separates two sets.
      Both fns are public
- [x] Read `config.clj:36-37,171-172` for how database keys are declared/validated
- [x] Confirm the devenv Postgres container (`penpotdev-infra-postgres-1`) and
      that databases are created in `docker/devenv/files/postgresql_init.sql`
      (`penpot_test`, `penpot_telemetry` already there — note `penpot_telemetry`
      is a leftover, Penpot's telemetry is an HTTP endpoint, so it is **not** a
      second-pool precedent)

## Checklist

- [x] **Spike first** — proved a second pool inits alongside the main one and
      answers a query, before any schema. Both halves done live; see Notes for
      what it caught
- [x] Add config keys (`sessions-database-uri`/`-username`/`-password`/
      `-min-pool-size`/`-max-pool-size`) with schema entries, all optional and
      **with no defaults** — absent config is the degraded path
- [x] Add the second pool component to `system-config` — as a DISTINCT key
      delegating to `::db/pool`'s multimethods, **not** an `ig/derive` (see Notes)
- [x] Add a separate migrations component with its own migration set and its own
      bookkeeping table — `::mg/session-migrations`, module `"sessions"`
- [x] Write migration 001 for the recorder DB — `design_session` with a transit
      `jsonb` events column, `STORAGE external`, following the
      `profile_agent_chat` shape
- [x] **Make it optional** — verified live: with the URI unset the backend logs
      `"sessions database not configured, skipping migrations"`, boots to a
      healthy `200`, and the sessions DB stays empty (0 tables)
- [x] Create the database in devenv and confirm migrations apply — added to
      `postgresql_init.sql` (reproducible) rather than left hand-made, plus env
      wiring in `defaults.env` and `docker-compose.main.yml`
- [x] Backend tests for the degraded path — `design_sessions_db_test.clj`,
      8 tests / 15 assertions / 0 failures, DB-less
- [x] Lint + `cljfmt` — kondo 0/0, formatted; changed namespaces load clean
- [x] Human approval — standing approval from Santi 2026-07-25
- [x] Committed with a gitmoji commit

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — **Phase 06 can
      proceed.** The pool is reachable as `::main/sessions-pool` and the table
      exists. One adjustment it must absorb: the RPC handlers will need BOTH
      pools (main for the permission check, sessions for the data) because there
      is no cross-database join — Phase 06 already anticipates this, and it is
      the one place the isolation is deliberately crossed. It must also handle a
      nil sessions pool by failing the RPC cleanly rather than NPE-ing.

## Files

- `backend/src/app/main.clj` — `::sessions-pool` (delegating key) +
  `::mg/session-migrations` wired into `system-config`
- `backend/src/app/migrations.clj` — `session-migrations` set and its
  integrant methods
- `backend/resources/app/migrations/sessions/0001-add-design-session-tables.sql` — new
- `backend/src/app/config.clj` — five optional config keys
- `backend/test/backend_tests/design_sessions_db_test.clj` — new
- `docker/devenv/files/postgresql_init.sql` — creates `penpot_sessions`
- `docker/devenv/defaults.env`, `docker/devenv/docker-compose.main.yml` — env wiring

## Notes

### What the spike caught #1: `ig/derive` breaks Penpot's boot

The obvious way to add a second pool is to inherit the existing behaviour:

```clojure
(derive ::sessions-pool ::db/pool)   ;; DO NOT DO THIS
```

It fails, and not subtly. Integrant resolves `ig/ref` by `isa?`, so deriving
makes **every pre-existing `(ig/ref ::db/pool)` in the system map ambiguous** —
and there are dozens. The system refuses to init:

```
:key :app.db/pool, :matching-keys (:app.db/pool :app.main/sessions-pool)
```

The fix is a distinct key that delegates to `::db/pool`'s own multimethods
(`init-key`, `halt-key!`, `assert-key`). Identical construction, logging and
shutdown; no ref ambiguity; and it still inherits the `(when uri ...)` behaviour
that makes optionality free.

A REPL gotcha worth knowing if this is ever revisited: **`derive` mutates the
global hierarchy and survives a `tools.namespace` refresh.** Deleting the code
was not enough — the running JVM kept failing until an explicit
`(underive :app.main/sessions-pool :app.db/pool)`.

### What the spike caught #2: `ig/assert-key` validates nothing here

`*assert*` is **false** in this build, so every `ig/assert-key` body in the
backend — including the pre-existing `::db/pool` one — is compiled out. A typo'd
sessions URI is *not* caught by the schema; what catches it is pool creation
failing to connect, which surfaces as a boot error rather than a silently
disabled recorder.

Two tests were written on the assumption that malformed options would be
rejected. They failed, and the honest fix was to correct the *tests*, not weaken
them into passing: `assert-key-validation-is-decorative-in-this-build` now pins
the finding, including a guard that fails if assertions are ever switched on so
the claim gets revisited. The session-recorder keys still follow the same
`assert-key` convention as the rest of the backend on purpose — being the one
namespace that validated differently would be worse than being consistently
decorative.

### Live verification (the first in this plan)

Unlike phases 01–04, this phase is proven against a running Penpot:

| Check | Result |
|---|---|
| unconfigured → boots normally | `"sessions database not configured, skipping migrations"`, backend `200`, sessions DB has 0 tables |
| configured → both pools init | `name="main" uri=…/penpot` and `name="sessions" uri=…/penpot_sessions min-size=1 max-size=8` |
| migration applies | `action="apply migration", module="sessions", name="0001-add-design-session-tables"` |
| isolation holds | `design_session` exists ONLY in `penpot_sessions`; its `migrations` table contains only the `sessions` module; `penpot` has no `design_session` |
| pool is usable | `{:pool? true, :ok {:ok 1}, :rows {:n 0}}` via the running system |

Recipe, for later phases: the container needs recreating
(`instance-compose ws0 up -d main`) for new env to reach the process, then
`start-tmux.sh` again; `(user/restart)` over nREPL 6064 is enough for code-only
changes. There is no `nc` in the container, so nREPL is driven by a small
raw-bencode client over node.

### No foreign keys, by design

`file_id` and `profile_id` in `design_session` are plain uuids. Cross-database
foreign keys do not exist, and that is the price of the isolation that was the
point of the phase. **Consequence accepted deliberately: deleting a file or
profile leaves its recordings orphaned.** For a prototype that beats coupling the
databases; a real deployment needs a reaper task. Written into the migration
itself so it cannot be discovered by surprise.

### Follow-ups

- **Orphan reaper** for recordings whose file or profile is gone (above).
- **`raw_dropped` is on the session row** so a partial raw record announces
  itself. Phase 07 must actually send it.
- **No per-file or per-profile quota** on recordings yet — same gap
  `profile_agent_chat` has (`NOTE(prototype)` in `agent_chats.clj`).
- The `review` column exists now so Phase 09's critique can be stored without a
  migration.
