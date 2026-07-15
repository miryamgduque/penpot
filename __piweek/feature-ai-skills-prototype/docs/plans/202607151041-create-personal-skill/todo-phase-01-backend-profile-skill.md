# Phase 01 — Backend: `profile_skill` table + RPC

**Status:** todo

## Goal

A per-user store for created skills, plus RPC to **create** one and **list** a user's own. Mirrors
`skill_state.clj` (US #8 RPC/migration style) and the `design_skill` table shape (0152), scoped to a
profile.

## Before Start

- [ ] Read `skill_state.clj` (RPC + schema + SQL + how it's registered in `rpc.clj`) and
      `0155-add-profile-skill-state-table.sql` + its `migrations.clj` entry
- [ ] Read `design_skill` (0152) for column choices (`trigger_on`, `description`, `body`,
      `is_enabled`, external STORAGE on big text)
- [ ] Read `ai_providers.clj` for the ON CONFLICT / `db/exec!` house patterns

## Checklist

- [ ] **Migration `0156-add-profile-skill-table.sql`:**
      `profile_skill(id uuid pk, profile_id uuid NOT NULL REFERENCES profile ON DELETE CASCADE,
      name text NOT NULL, label text NOT NULL, category text NOT NULL, mode text NOT NULL,
      trigger_on text, description text NOT NULL DEFAULT '', body text NOT NULL DEFAULT '',
      is_enabled boolean NOT NULL DEFAULT true, created_at, updated_at)`; unique
      `(profile_id, name)`; index on `profile_id`; `body`/`description` STORAGE external. Register in
      `migrations.clj`.
- [ ] **Command ns `app.rpc.commands.profile-skills`:**
      - `get-skills {}` → the caller's rows (`SELECT … WHERE profile_id = ?`), mapped to
        `{:id :name :label :category :mode :trigger :description :body :enabled}`
      - `create-skill {name label category mode trigger? description? body}` → insert; return the
        created row. Generate `name` server-side or accept a slug — pick one (a slug from the label,
        de-duplicated per profile, is simplest). Reject empty label/body via schema.
      - Register the ns in `rpc.clj`.
- [ ] Schemas via `sm/` (bounded strings; `mode` an enum `suggest|review|autofix`; `category` a
      string). `::rpc/profile-id` from the request (never trust a client-sent profile).
- [ ] **Tests** (`backend_tests.rpc-profile-skills-test`): no skills initially; create returns the
      row (default `is_enabled` true); get lists only the caller's skills; duplicate name per profile
      is rejected/handled; a second profile doesn't see the first's skills.
- [ ] `clojure -M:dev:test --focus backend-tests.rpc-profile-skills-test` green; lint clean
- [ ] Apply the migration to the running devenv DB (backend reload) so Phase 02+ can call it live
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

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
