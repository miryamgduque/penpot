# Phase 02 — Frontend data: surface `arrived?` through the catalog

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Confirm Phase 01 is `done` and the backend response shape for `get-team-skills` actually includes `:arrived?`
- [ ] Re-read `frontend/src/app/main/data/workspace/team_skills.cljs` (fetch/promote actions) and `agent_skills.cljs` (`full-catalog`, `team-skill->entry`, `resolve-enabled`) to confirm line numbers haven't drifted since 2026-09-10 grounding

## Checklist

- [ ] `team_skills.cljs` — confirm `team-skills-fetched` passes the new `:arrived?` field through untouched (likely no change needed if it's a pure passthrough store); add a `mark-team-skill-seen!` action wrapping `rp/cmd! :mark-team-skill-seen {:team-skill-id ...}`, updating local state optimistically (flip `:arrived?` to `false` for that id) so the UI reacts without waiting for a refetch
- [ ] `agent_skills.cljs` — thread `:arrived?` through `team-skill->entry` (~line 377-389) onto the merged catalog entry, so `ai_panel.cljs` can read it directly off a skill entry without reaching into team-skills state
- [ ] Confirm the promoter's own fetch never shows `:arrived? true` for their own promotion (should already follow from the backend's `promoted_by <> profile-id` filter — verify, don't just trust)
- [ ] Lint + typecheck pass (`make lint/frontend`, `shadow-cljs compile main` 0 warnings)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/team_skills.cljs` — add `mark-team-skill-seen!`
- `frontend/src/app/main/data/workspace/agent_skills.cljs` — thread `:arrived?` through `team-skill->entry`/`full-catalog`

## Notes

No new fetch trigger — this phase is purely about not dropping the field that Phase 01 already returns on the existing `fetch-team-skills` round-trip (`ai_panel.cljs:2569`).
