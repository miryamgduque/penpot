# Phase 01 — Backend: account-level skill state

**Status:** todo

## Goal

Persist per-user skill on/off state server-side, mirroring the AI-providers pattern. A row is
keyed by `(profile_id, skill, file_id)` where a **NULL `file_id` is the account default** and a
set `file_id` is a **per-file override** (both private to the user). Expose RPC commands to read
all states for a profile (optionally for a file) and to set `enabled`.

## Before Start

- [ ] Read `backend/src/app/rpc/commands/ai_providers.clj` (table, upsert, command shape) as the template
- [ ] Find the migrations dir + registration (`app.migrations`) and the sql helpers (`app.db`)
- [ ] Confirm how commands are registered/exposed and how the frontend `rp/cmd!` names map to them
- [ ] Decide the exact columns (booleans vs a status enum) and defaults

## Checklist

- [ ] Migration: `profile_skill_state (profile_id, skill, file_id NULL, enabled, modified_at)`,
      FK profile + FK file (on delete cascade). Unique per `(profile_id, skill, file_id)` — note
      Postgres treats NULL as distinct, so use a unique index with `COALESCE(file_id, sentinel)`
      (or a partial-index pair) so one account row + one row/file coexist
- [ ] `app.rpc.commands.skill-state`: `get-skill-states {?file-id}` (account rows + this file's rows),
      `set-skill-enabled {skill enabled ?file-id}` (NULL file-id = account default) — upsert on
      conflict `(profile_id, skill, file_id)`
- [ ] Register the commands; add malli/spec schemas for params
- [ ] Only persist **non-default** rows where sensible (avoid a row per built-in skill by default)
- [ ] Backend tests for the commands (set → get round-trip, upsert overwrite)
- [ ] `make lint/backend` + backend tests pass
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Note the exact command names + row shape for Phase 02 to consume
- [ ] Confirm Phase 02 assumptions still hold

## Files

- `backend/.../migrations/*.sql` (or the clj migration registry) — new table
- `backend/src/app/rpc/commands/skill_state.clj` — **new** commands
- backend rpc registration + tests

## Notes

- Only `enabled` is stored (the auto-fix trust flags were removed from the story). If future
  stories need per-skill flags, extend this table then.
- Per-**file** overrides ARE here (rows with a set `file_id`) because they're **per-user, not
  shared** — they must not live in the file's shared `:data`. The NULL-`file_id` row is the
  account default; a set-`file_id` row overrides it for that user + file.
