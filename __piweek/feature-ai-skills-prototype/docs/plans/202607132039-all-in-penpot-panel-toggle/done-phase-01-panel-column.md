# Phase 01 — Native panel column & mount

**Status:** done

## Goal

A native right-docked `[:aside]` panel that appears when an `:ai-panel` layout flag is set and disappears when it is cleared, with the canvas reflowing automatically. No contents yet beyond a titled empty shell — this phase proves the column, the flag, and the reflow.

## Before Start

- [ ] Verify `workspace.scss` grid still matches `grid-template: "left-sidebar viewport right-sidebar plugin-dock" 1fr / auto 1fr auto auto;` (workspace.scss:17)
- [ ] Confirm `#plugin-dock` aside is still the last child of `workspace-content*` (workspace.cljs:131-133)
- [ ] Confirm `layout/valid-flags` and `layout/default-layout` are unchanged (data/workspace/layout.cljs:17-36, 58-70)
- [ ] Re-read `.plugin-dock` CSS (workspace.scss:24-39) for the `position: relative; z-index` trick that keeps the dock above the 100vw bottom palette wrapper

## Checklist

- [x] Add `:ai-panel` to `layout/valid-flags` (data/workspace/layout.cljs) — **not** added to `layout-flags-persistence-mapping` (reset-on-refresh) or `presets` (coexists with layers/assets). ✓
- [x] Create `frontend/src/app/main/ui/workspace/ai_panel.cljs` — `ai-panel*` renders a titled `[:aside]` shell (header "All-In Penpot" + `i/close` button that clears the flag). ✓
- [x] Create `frontend/src/app/main/ui/workspace/ai_panel.scss` — fixed 360px column (`$s-360`), `position: relative; z-index: $z-index-1` per the palette-wrapper gotcha. ✓
- [x] Extend the grid in `workspace.scss:17`: `"left-sidebar viewport right-sidebar ai-panel plugin-dock" 1fr / auto 1fr auto auto auto` (empty `auto` track collapses to 0). ✓
- [x] Mount `ai-panel*` in `workspace-content*` (workspace.cljs), gated by `(contains? layout :ai-panel)` and `(not hide-ui?)`. ✓
- [x] Drove the flag from the browser console (`app.main.store.emit_BANG_(...toggle_layout_flag(...))`) in the live devenv to verify open/close + reflow. ✓
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, `stylelint` clean, `shadow-cljs compile main` → **Build completed, 0 warnings** (ran in devenv). ✓
- [x] Preview review (live devenv, file "New File 1"): flag on → **360px** docked column, viewport reflows 1084→724px, **no horizontal overflow**, bg `rgb(24,24,26)`, white uppercase "ALL-IN PENPOT" title. Close **×** button → panel removed, column collapses to 0, viewport back to 1084. ✓
- [ ] Human approval received
- [ ] Commit: `feat(workspace): add native all-in-penpot panel column`

## After Finish

- [ ] Rename `todo-` → `done-`, update README link
- [ ] Note the exact width var/value chosen and any grid quirks in Notes

## Files

- `frontend/src/app/main/data/workspace/layout.cljs` — register `:ai-panel` in `valid-flags`
- `frontend/src/app/main/ui/workspace.cljs` — mount the native aside gated by the flag
- `frontend/src/app/main/ui/workspace.scss` — grid column + track
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` *(new)* — panel shell component
- `frontend/src/app/main/ui/workspace/ai_panel.scss` *(new)* — panel column styles

## Notes

- Reflow is free: `viewport` is the only `1fr` track, so adding an `auto` column shrinks the canvas without any JS resize logic.
- We deliberately keep `:ai-panel` out of the layout-flag persistence mapping — a hard refresh must reset to closed (spec), and file-bound persistence is handled in Phase 04, not by the global flag mapping.
- **Gotcha (applies to every later phase that adds a NEW `.scss`):** the devenv SCSS watch does not pick up newly-created `.scss` files — the class names generate but resolve to no rules (symptom: transparent bg, content-sized width, default/yellow text). Fix: rebuild assets once with `docker exec -w /home/penpot/penpot/frontend penpot-devenv-ws0-main sudo -u penpot node ./scripts/build-app-assets.js`, then reload the tab. Editing an existing watched `.scss` afterwards hot-reloads fine.
- Live driving of a scaffold layout flag from the console: `app.main.store.emit_BANG_(app.main.data.workspace.layout.toggle_layout_flag(cljs.core.keyword("ai-panel")))`.
