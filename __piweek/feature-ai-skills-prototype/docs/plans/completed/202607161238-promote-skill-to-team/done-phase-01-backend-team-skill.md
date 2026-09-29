# Phase 01 — Backend team_skill store + RPCs

**Status:** done (2026-07-16) — `team_skill` table (migration 0160, mirrors
profile_skill + team_id/promoted_by/source_profile_skill_id) + `profile_skill.
promoted_to` (0161); `team_skills.clj` with `::get-team-skills` + `::promote-
skill` (copies label/category/reactive/body from the source, dedups the slug in
the team, sets the source's promoted_to); `get-skills` surfaces `promoted_to`;
registered in rpc.clj. Verified live: backend restarted clean, both migrations
applied (`team_skill` 13 cols + `promoted_to` column present).

## Goal

A team-scoped skill store shaped like `profile_skill`, plus the RPCs to promote a
personal skill into it and to fetch a team's skills.

## Before Start

- [ ] Re-read `profile_skills.clj` (table shape, row->skill, create/update) and
      migration `0156-add-profile-skill-table.sql`.
- [ ] Re-read `design_skills.clj` for the team permission pattern
      (`check-read-permissions!` / `check-edition-permissions!` from
      `app.rpc.commands.teams`) and how team-scoped RPCs are shaped.
- [ ] Confirm the latest migration number and the `rpc.clj` command registration
      list.

## Checklist

- [ ] Migration `0160-add-team-skill-table.sql`:
      `team_skill` — `id`, `team_id` (FK `team` ON DELETE CASCADE), `name`,
      `label`, `category`, `reactive`, `trigger_on` NULL, `description`, `body`,
      `promoted_by` (FK `profile`), `source_profile_skill_id` (FK `profile_skill`
      NULL ON DELETE SET NULL), `created_at`, `updated_at`; unique
      `(team_id, name)`; index on `team_id`. `body`/`description` STORAGE external.
- [ ] Migration `0161-add-profile-skill-promoted-to.sql`:
      `ALTER TABLE profile_skill ADD COLUMN promoted_to uuid NULL REFERENCES
      team_skill(id) ON DELETE SET NULL;` — the light link + "disabled" marker.
      Register both in `migrations.clj`.
- [ ] New `app/rpc/commands/team_skills.clj`:
  - `::get-team-skills` `{team-id}` → `check-read-permissions!`, `SELECT * FROM
    team_skill WHERE team_id = ? ORDER BY created_at`, rows shaped
    `{:id :name :label :category :reactive :trigger :description :body
     :promoted-by :source-skill-id}`.
  - `::promote-skill` `{source-id name description team-id}`
    (`::db/transaction true`): load the source `profile_skill`, assert it is the
    caller's (`profile_id = ::rpc/profile-id`) and not already promoted;
    `check-edition-permissions!` on the team; insert `team_skill` copying
    `label/category/reactive/body` from the source and `name/description` from
    params (unique-name within the team, mirroring `profile_skills/unique-name`);
    set `profile_skill.promoted_to = <new id>`; return the team skill row.
- [ ] `get-skills` (personal) must surface `promoted_to` so the client can mark
      the personal copy — add the column to its SELECT + `row->skill`.
- [ ] Register the ns + both commands in `rpc.clj`.
- [ ] `clj-kondo` + `check-fmt:clj` clean (in the devenv). Restart backend so the
      migrations apply; confirm `\d team_skill` + the new `profile_skill` column.

## Notes

- Reuse `profile_skills/unique-name` logic (copy or extract) so a second promote
  of the same name within a team doesn't collide.
- No per-member state table needed: team skills merge into the catalog **by
  name** and inherit the existing name-keyed skill-state (default on).
