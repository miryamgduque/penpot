# Phase 01 — Backend: `profile_skill` table + RPC

**Status:** todo

## Goal

A per-user store for created skills, plus RPC to **create** one and **list** a user's own. Mirrors
`skill_state.clj` (US #8 RPC/migration style) and the `design_skill` table shape (0152), scoped to a
profile.

## Before Start

- [x] Read `skill_state.clj` (RPC + schema + SQL + registration in `rpc.clj`) and
      `0155-add-profile-skill-state-table.sql` + its `migrations.clj` entry
- [x] Read `design_skill` (0152) for column choices (`trigger_on`, `description`, `body`,
      `is_enabled`, external STORAGE on big text)
- [x] Confirmed `db/insert!` returns the inserted row (design_skills.clj) — cleaner than raw RETURNING

## Checklist

- [x] **Migration `0156-add-profile-skill-table.sql`:** `profile_skill(id, profile_id → profile
      CASCADE, name, label, category, mode, trigger_on, description '' , body '' , is_enabled true,
      created_at, updated_at)`; unique `(profile_id, name)`; index on `profile_id`; `body`/
      `description` STORAGE external. Registered in `migrations.clj`.
- [x] **Command ns `app.rpc.commands.profile-skills`:** `get-skills {}` → caller's rows mapped to
      `{:id :name :label :category :mode :trigger :description :body :enabled}`; `create-skill
      {name label category mode trigger? description? body}` → `db/insert!` (returns the row).
      **Name dedup server-side** (`unique-name` → first free `slug-N`) so a create never collides.
      Registered in `rpc.clj`.
- [x] Schemas via `sm/` (bounded strings; `mode` enum `suggest|review|autofix`). `::rpc/profile-id`
      from the request.
- [x] **Tests** (`backend_tests.rpc-profile-skills-test`): no skills initially; create returns the
      row (enabled true); get lists the skill (incl. trigger); duplicate name auto-suffixes to
      `-2`; a second profile doesn't see the first's. **16 assertions, 0 failures.**
- [x] `clojure -M:dev:test --focus backend-tests.rpc-profile-skills-test` green; clj-kondo 0/0
- [x] Migration applied to the running devenv DB (backend reloaded): `profile_skill` table +
      `0156` migration row present; `get-skills` RPC registered live (auth-required, not not-found)
- [x] Human approval received (verified via a live authenticated create + list round-trip)
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links; note the RPC/param shape for Phase 02/04

## Files

- `backend/src/app/migrations/sql/0156-add-profile-skill-table.sql` — **new**
- `backend/src/app/migrations.clj` — register
- `backend/src/app/rpc/commands/profile_skills.clj` — **new**
- `backend/src/app/rpc.clj` — register ns
- `backend/test/backend_tests/rpc_profile_skills_test.clj` — **new**

## Notes

- **Enable/disable reuse:** `is_enabled` here is the skill's **creation default** (true). The US #8
  per-user/per-file toggle (`profile_skill_state`, keyed by skill `name`) then overrides it exactly
  as for built-ins — no separate toggle path. Deleting/editing created skills is out of scope here.
- The running backend must be reloaded to pick up the new migration + ns (documented devenv step).
