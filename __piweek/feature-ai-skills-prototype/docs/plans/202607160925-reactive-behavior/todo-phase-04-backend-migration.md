# Phase 04 — Backend

**Status:** todo

## Goal

Persist reactive behavior instead of mode on `profile_skill`, migrating existing
rows, so user-created skills round-trip the new axis.

## Before Start

- [ ] Re-read [profile_skills.clj](../../../../../backend/src/app/rpc/commands/profile_skills.clj):
      row mapping (:28), SELECT (:37), create schema `[:mode [:enum "suggest"
      "review" "autofix"]]` (:56), insert params (:81).
- [ ] Re-read migration `0156-add-profile-skill-table.sql` (`mode text NOT NULL`),
      and how migrations register in `app/migrations.clj`.

## Checklist

- [ ] New migration `0157-profile-skill-reactive.sql`:
      `ALTER TABLE profile_skill RENAME COLUMN mode TO reactive;` then remap
      values — `UPDATE profile_skill SET reactive = CASE WHEN reactive = 'autofix'
      THEN 'observer' ELSE 'on-call' END;`. Register it in `migrations.clj`.
- [ ] `profile_skills.clj`: row map `:reactive (:reactive row)`; SELECT column
      `mode` → `reactive`; create schema `[:reactive [:enum "on-call" "observer"]]`;
      insert `:reactive reactive` and destructure `reactive` from params.
- [ ] Keep `trigger_on`/other columns untouched.
- [ ] `check-fmt:clj` + `clj-kondo` (`lint:clj`) clean in the devenv.
- [ ] Restart backend (tmux `penpot:3`) so the migration applies; confirm the
      column renamed (`\d profile_skill`) and existing rows remapped.

## Notes

- Frontend `user-skill->entry` already reads `:reactive` after Phase 01; this
  phase makes the server actually send it.
- Prototype scope: a hard rename is fine (no external consumers). If any row held
  an unexpected mode value it falls to `on-call`.
