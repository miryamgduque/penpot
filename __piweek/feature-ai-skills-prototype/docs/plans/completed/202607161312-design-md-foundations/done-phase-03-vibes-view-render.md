# Phase 03 — frontmatter-aware vibes view

**Status:** done

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Phases 01–02 merged (14031cae08, 3b4e23a6d6)
- [x] Read `vibes-view*` (`ai_panel.cljs:1519`) — confirmed raw doc fed to `markdown*`

## Checklist

- [x] Tests: `display-model` + `typography-summary` in design_md.cljs (pure) — ordered rows, `{token.ref}` swatch aliases resolved (shown value stays the alias), malformed entries skipped, known typography props ordered first; 5 new tests
- [x] `vibes-view*` read mode renders the token summary above the body: name/description heading, swatch grid (name + value + painted chip), typography per-role rows, rounded/spacing chip rows
- [x] Body through `markdown*` WITHOUT frontmatter — the `---` fence never reaches marked
- [x] Legacy doc renders exactly as before (verified live by swapping docs reactively)
- [x] SCSS + build-app-assets; ALSO fixed pre-existing yellow body text (`.vibes-doc` never set a color; `.message-md` inherits — added `color: var(--color-foreground-primary)`)
- [x] Lint + typecheck + tests pass — 793 tests / 2585 assertions 0 failures; kondo 13 warnings on ai_panel.cljs = same 13 as before the change (macro-defined vars); cljfmt clean; prettier drift in ai_panel.scss is pre-existing (keyframes block), my additions are prettier-clean; `compile main` green
- [x] Preview review with Chrome MCP on devenv :3450, New File 2: seeded full-spec doc via `at.execute_tool` console call — swatches/typography/chips/body all correct; live rejection of broken YAML + dangling ref confirmed; legacy swap + restore reactive; only console error is pre-existing libs.js tabindex warning
- [x] Human approval received
- [x] Committed: 6b820ca4e6 `:lipstick: Render DESIGN.md tokens in the vibes view`

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — phase 04 proceeds as planned

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — vibes-view* read mode
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — token summary styles
- `frontend/src/app/main/data/workspace/design_md.cljs` — display-row helpers (keep pure)

## Notes

- Swatch values are arbitrary CSS colors (the spec allows any format) — set
  them via inline `:style`, don't try to normalize to hex.
- Seed a test doc quickly from the console via
  `at.execute_tool("set_design_doc", …)` (cljs.core.assoc/keyword pattern) —
  no LLM turn needed.
