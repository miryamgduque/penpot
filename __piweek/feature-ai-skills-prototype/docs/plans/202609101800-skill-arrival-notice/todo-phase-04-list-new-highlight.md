# Phase 04 — Skill-list "NEW" highlight

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Confirm Phase 02 is `done` — list entries expose `:arrived?`
- [ ] Re-read `ai_panel.cljs:2018-2073` (`skills-tab*`, `skill-row*`) to confirm current row-rendering structure before adding the highlight

## Checklist

- [ ] `skill-row*` — when the entry's `:arrived?` is true, apply the accent-tinted background/border (per the Penpot design reference: `color.accent.bg`/`color.accent.border`-equivalent, translated to this project's token/SCSS variables — do **not** hardcode hex, reuse or add proper SCSS variables/tokens matching project convention) + a small "NEW" pill next to the skill name
- [ ] Client-side-only 20s fade: a local timer/effect started when the row (or the list) mounts with `:arrived? true`; after 20s, remove the highlight classes with a CSS transition — no persisted timestamp, no backend call (per the README's approved "client-side ephemeral" decision)
- [ ] Confirm re-mounting the list (panel close/reopen within 20s) simply restarts the local timer — this is the accepted trade-off, not a bug to fix
- [ ] Confirm the highlight does **not** call `mark-team-skill-seen!` on its own — only the notice card's Dismiss/View skill actions do that (see README's "Seen-marking rule"); the highlight is expected to keep reappearing on future opens until the notice is acknowledged
- [ ] Lint + typecheck pass; `shadow-cljs compile main` 0 warnings
- [ ] Preview review with MCP browser/devenv tools — screenshot the highlighted row + the fade
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — `skill-row*` highlight + pill + local fade timer
- corresponding `.scss` — highlight/pill styles

## Notes

This phase is deliberately decoupled from Phase 03's seen-marking — re-read the README's "Seen-marking rule" before changing this behavior; it was a discovery-interview decision, not an oversight.
