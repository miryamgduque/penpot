# Phase 02 — Frontend data: surface `arrived?` through the catalog

**Status:** todo

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions) — confirmed 2026-09-11
- [x] Confirm Phase 01 is `done` and `get-team-skills` returns `:arrived` — confirmed via the Phase 01 REPL smoke test
- [x] Re-read `team_skills.cljs` and `agent_skills.cljs` — `team-skill->entry` is at lines 377-389 as grounded (unchanged); `team-skills-fetched` (20-25) is a pure passthrough, no changes needed for `:arrived` to survive the round-trip

## Checklist

- [x] `team_skills.cljs` — confirmed `team-skills-fetched` needs no change (pure passthrough already carries `:arrived`); added `mark-team-skill-seen!`: optimistically flips `:arrived` to `false` for the given id in `[:team-skills]` (`UpdateEvent`), then fires `rp/cmd! :mark-team-skill-seen` fire-and-forget (`rx/ignore`) — mirrors the existing `comments.cljs`/`clipboard.cljs` fire-and-forget idiom
- [x] `agent_skills.cljs` — threaded `:arrived` through `team-skill->entry` onto the merged catalog entry. Also added `:promoted-by (:promoted-by-name ts)` at the same spot (not in the original plan text, but the same one-line passthrough shape, and Phase 03's notice card needs the promoter's name — added now rather than repeating this edit later)
- [x] Confirmed the promoter's own fetch never shows `:arrived true` — verified in Phase 01's REPL smoke test (backend-level), and this phase just passes that value through unchanged
- [x] Lint pass: `clj-kondo --lint frontend/src/app/main/data/workspace/team_skills.cljs frontend/src/app/main/data/workspace/agent_skills.cljs` → 0 errors/warnings
- [x] Format pass: `cljfmt check` on the same files → all formatted correctly
- [x] shadow-cljs live watch (window 0) picked up the change and rebuilt `:main`/`:worker`/`:storybook` — 0 warnings, 0 errors
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
