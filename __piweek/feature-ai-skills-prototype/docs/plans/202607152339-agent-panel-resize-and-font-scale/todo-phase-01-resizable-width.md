# Phase 01 — Resizable Width

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Review dependencies are met
- [ ] Read relevant source files to confirm assumptions (esp. that `.ai-panel` still carries the fixed `$s-360` width and the grid column is still `auto`)

## Checklist

- [ ] Write tests for the width clamp helper (pure fn: proposed width → clamped width given min/max)
- [ ] Add a `use-panel-resize` (panel-local) hook: pointer-capture drag modeled on `use-resize-hook`'s handlers, but persisting through `hooks/use-persisted-state ::panel-width` (global, not per-file)
- [ ] Render a `.resize-area` handle on the panel's left edge; wire `on-pointer-down` / `on-pointer-move` / `on-lost-pointer-capture`
- [ ] Apply the width as an inline style on the `:aside`; drop the fixed `width` from `.ai-panel` (keep 360 as the default/min constant)
- [ ] Clamp: min 360px, max `0.5 × window width` (re-clamped on window resize, mirroring `use-resize-hook`'s window listener)
- [ ] Lint + typecheck pass (frontend lint; run existing frontend tests)
- [ ] Preview review with MCP tools: drag wider/narrower, reload persistence, second file inherits width, no viewport/grid breakage
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles: Make the agent panel resizable`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — the resize hook + handle element on the root `:aside`; inline width style
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — remove fixed width; add `.resize-area` styles (copy the sidebar's: absolute strip on the inline-start edge, `cursor: ew-resize`, comfortable hit area)
- `frontend/test/frontend_tests/...` — clamp helper test (place alongside existing agent tests; exact ns decided at execution)

## Notes

**Why not `use-resize-hook` directly:** its persistence is `[::state file-id key]`
— per-file by design. The interview decided width is global. Rather than
threading a "global" option through a hook shared by both sidebars (risk without
benefit), copy its small drag mechanics into a panel-local hook and persist with
`use-persisted-state`, which the panel already uses for the remembered model.

**Drag direction:** the panel is right-docked, so dragging the handle *left*
must *grow* the width — equivalent to the right sidebar's `negate? true`.

**Grid interaction:** `workspace.scss` gives the `ai-panel` grid area an `auto`
column; the element's own width is what sizes it, so inline width Just Works.
The viewport column is `1fr` and absorbs the change; `use-resize-observer` on the
workspace content already handles viewport re-measurement.

**Clamp floor = 360:** the current fixed width is the known-good minimum — the
composer's absolutely-anchored attach/send buttons and the model picker were
designed at it. Don't allow narrower.
