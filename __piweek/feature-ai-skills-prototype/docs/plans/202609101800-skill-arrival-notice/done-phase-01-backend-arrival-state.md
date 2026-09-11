# Phase 01 — Backend: `team_skill_seen` + `arrived?` + mark-seen RPC

**Status:** todo

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions) — confirmed 2026-09-11
- [x] Re-read `backend/src/app/rpc/commands/team_skills.clj` in full — matches grounding exactly; `check-read-permissions!`/`check-edition-permissions!` both already imported
- [x] Re-read `backend/src/app/rpc/commands/skill_state.clj` for the upsert-mutation shape to copy — `ON CONFLICT (...) DO UPDATE` house pattern confirmed (lines 56-60)
- [x] Confirm migration numbering — latest is `0161-add-profile-skill-promoted-to`, registered in `backend/src/app/migrations.clj:526-527`; next is `0162`

## Checklist

- [x] New migration `0162-add-team-skill-seen-table.sql`: `team_skill_seen` table — `profile_id uuid`, `team_skill_id uuid` (FK → `team_skill(id)` on delete cascade), `seen_at timestamptz not null default now()`, PK `(profile_id, team_skill_id)`. Registered in `migrations.clj:529-530`.
- [x] Extended `get-team-skills` SQL (`team_skills.clj`) with `LEFT JOIN team_skill_seen seen ON seen.team_skill_id = ts.id AND seen.profile_id = ?`, computing `:arrived (seen.seen_at IS NULL AND ts.promoted_by IS DISTINCT FROM ?)` per row. Named the field `:arrived` (no `?`) rather than the plan's placeholder `:arrived?`, matching the rest of `row->skill`'s serializable-key convention (`:source-skill-id`, not `:source-skill-id?`, etc.).
- [x] Added `::mark-team-skill-seen` mutation — params `{team-skill-id}`, destructures `::rpc/profile-id`, looks up the `team_skill` row to get its `team-id`, permission-checks via `check-read-permissions!` (team membership, not edition), upserts `team_skill_seen` with `ON CONFLICT (profile_id, team_skill_id) DO NOTHING` (idempotent — verified).
- [x] Lint + format pass — ran inside devenv: `clj-kondo --lint backend/src/app/rpc/commands/team_skills.clj backend/src/app/migrations.clj` → 0 errors/warnings; `cljfmt check` on the same files → all formatted correctly.
- [x] Manual RPC smoke test — ran directly against the live devenv backend via its socket REPL (port 6062, `app.srepl`), using real pre-existing `team_skill` rows from a prior US #12 verification session (team "Team", promoter "Demo User", a second member profile on the same team):
  - `get-team-skills` as the **promoter**: all 3 rows `:arrived false`.
  - `get-team-skills` as the **other member**: all 3 rows `:arrived true`.
  - `mark-team-skill-seen` on one row as the other member → refetch shows only that row flipped to `:arrived false`, the other two unchanged.
  - Idempotency: calling `mark-team-skill-seen` again on the same row does not error.
  - Not-found: bogus `team-skill-id` raises `{:type :not-found :code :object-not-found}` from `db/get`.
  - Non-member: a random `profile-id` calling `get-team-skills` for the team raises `{:type :not-found}` from `check-read-permissions!`.
  - Cleaned up the test `team_skill_seen` row afterward so the shared devenv/demo data is left as found.
- [x] Human approval received
- [x] Committed with a gitmoji commit — `bc61ce46fa`

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — yes, Phase 02 unblocked

## Files

- `backend/src/app/migrations/sql/01XX-add-team-skill-seen.sql` — new table
- `backend/resources/app/migrations.edn` (or wherever migrations are registered — confirm current mechanism) — register the new migration
- `backend/src/app/rpc/commands/team_skills.clj` — extend `get-team-skills`, add `::mark-team-skill-seen`

## Notes

Reuse `skill_state.clj:44-66`'s upsert shape (params destructuring `::rpc/profile-id`, `db/insert!`/`on-conflict` pattern) for `mark-team-skill-seen` rather than inventing a new upsert idiom.

Do **not** touch `profile_skill_state`/`skill_state.clj` — arrival state is a genuinely different axis (team_skill-id-keyed, not skill-name/file-keyed) per the README's grounding notes; keeping it separate avoids conflating "disabled" with "not yet acknowledged."
