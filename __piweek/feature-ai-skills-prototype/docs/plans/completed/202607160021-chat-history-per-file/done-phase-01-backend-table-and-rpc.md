# Phase 01 — Backend table + RPC

**Status:** done

## Goal

A `profile_agent_chat` table and an `agent-chats` RPC namespace: list a file's
conversations (metadata only), fetch one (full data), upsert, delete. Private
per profile; transit-in-jsonb payload.

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions — Miryam works in the same tree)
- [x] Check the latest migration number is still `0156` (`ls backend/src/app/migrations/sql/ | tail`); renumber if she added one
- [x] Re-read `backend/src/app/rpc/commands/skill_state.clj` and `ai_providers.clj` for the current house patterns (ON CONFLICT upsert, schema style, `::doc/added "2.13"`)
- [x] Confirm `db/tjson` + `db/decode-transit-pgobject` signatures in `backend/src/app/db.clj`

## Checklist

- [x] Migration `backend/src/app/migrations/sql/0158-add-profile-agent-chat-table.sql`:
  - `id uuid PRIMARY KEY` (client-generated), `profile_id`/`file_id` uuid FKs `ON DELETE CASCADE DEFERRABLE`, `title text NOT NULL DEFAULT ''`, `data jsonb NOT NULL`, `created_at`/`updated_at timestamptz DEFAULT now()`
  - `ALTER COLUMN data SET STORAGE external` (cf. `profile_skill`)
  - index `(profile_id, file_id, updated_at DESC)` for the list query
- [x] Register in `backend/src/app/migrations.clj`
- [x] `backend/src/app/rpc/commands/agent_chats.clj`:
  - `::get-agent-chats {file-id}` → `[{:id :title :created-at :updated-at}]` for this profile+file, `updated_at DESC`, **no `data`**
  - `::get-agent-chat {id}` → full row with decoded `:data`; not-found for missing or someone else's row
  - `::upsert-agent-chat {id file-id title data}` → parameterized `INSERT … ON CONFLICT (id) DO UPDATE SET title, data, updated_at = now()` guarded so the update only applies to the owner's row (`WHERE profile_agent_chat.profile_id = ?` on the conflict update; a foreign id must not be capturable)
  - `::delete-agent-chat {id}` → delete scoped to profile-id
  - `data` schema is `:any` (transit map); size is bounded client-side by history trim + image strip — note this in the ns docstring
- [x] Register the namespace in `backend/src/app/rpc.clj` `scan-ns`
- [x] Lint: clj-kondo + cljfmt clean (run in devenv against the worktree path, `/opt/utils/bin/cljfmt`)
- [x] Commit in the worktree: `:sparkles: Add per-file agent chat storage` (no approval pause — the merge in Phase 04 is the gate)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `backend/src/app/migrations/sql/0158-add-profile-agent-chat-table.sql` — new table
- `backend/src/app/migrations.clj` — register migration
- `backend/src/app/rpc/commands/agent_chats.clj` — new RPC namespace
- `backend/src/app/rpc.clj` — scan-ns registration

## Notes

- New RPC commands need `(in-ns 'user) (restart)` in the nREPL (port 6064), not
  just `:reload` — `sv/scan-ns` builds `::methods` once at init.
- `NOTE(prototype)`: no quota/row-count cap per file; fine at this stage, flag
  before this leaves prototype.
