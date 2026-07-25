# Phase 05 — Separate database

**Status:** todo

A second Postgres database on the existing server, isolated from Penpot's
product tables: its own logical DB, its own connection pool, its own migration
set. Highest-unknown phase in the plan — do it before building anything on top.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `backend/src/app/main.clj:150-168` — the `::db/pool` component and the `:app.migrations/migrations` component that takes it
- [ ] Read `backend/src/app/db.clj` to confirm `::db/pool`'s init-key params (`::db/uri`, `::db/username`, `::db/password`, sizes) and whether it can be `ig/derive`d under a second key
- [ ] Read `backend/src/app/migrations.clj` to see how the migration registry and its bookkeeping table are scoped — a second DB needs its own
- [ ] Read `backend/src/app/config.clj:36-37,171-172` for how database config keys are declared and validated
- [ ] Confirm the devenv Postgres container name and how to create a second database in it (`penpotdev-infra-postgres-1`, db `penpot`)

## Checklist

- [ ] **Spike first**: prove a second derived pool can init alongside the main one and answer a trivial query, before writing any schema
- [ ] Add config keys (`session-recorder-database-uri`/`-username`/`-password`) with schema entries, all optional
- [ ] Add the derived pool component to `system-config`
- [ ] Add a separate migrations component with its own migration set and its own bookkeeping table
- [ ] Write migration 001 for the recorder DB: session table + events table (transit `jsonb` payload, `STORAGE external`, following the `profile_agent_chat` shape at `backend/src/app/migrations/sql/0158-add-profile-agent-chat-table.sql`)
- [ ] **Make it optional**: when the recorder DB is unconfigured or unreachable, the backend logs once and boots normally with recording disabled — it must never block startup
- [ ] Create the database in devenv and confirm migrations apply
- [ ] Backend tests for the degraded path (absent config → no pool, no crash)
- [ ] Lint (`pnpm run lint:clj`) + `cljfmt` in the devenv container
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `backend/src/app/main.clj` — derived pool + migrations components in `system-config`
- `backend/src/app/config.clj` — new optional config keys + schema
- `backend/src/app/migrations/` — a second, separately-tracked migration set
- `docker/devenv/` — create the second database in the devenv Postgres

## Notes

**No foreign keys to product tables.** The whole point of separation is that
this database can be dropped, moved, or scaled without touching Penpot. That
means `file_id` and `profile_id` are plain uuids here, not references — and
therefore nothing cascades. Deleting a file will leave orphan sessions; decide
whether that is acceptable (probably yes, for a prototype) and write it down
rather than discovering it later.

**Optionality is a hard requirement, not a nicety.** A Penpot instance that
cannot reach the recorder DB must still boot, still open files, still run the
agent. Recording is the only thing that degrades.

Migration numbering here is independent of Penpot's main sequence — this DB has
its own bookkeeping table — which sidesteps the recurring hazard on this branch
of two sessions claiming the same migration number (0157 was taken mid-plan
once already).

If the spike shows a second pool is awkward under Penpot's integrant setup,
**stop and reconsider** before writing schema: falling back to a dedicated
schema (namespace) inside the existing database, or to the `profile_agent_chat`
pattern in the main DB, are both acceptable retreats. Flag it rather than
forcing it.
