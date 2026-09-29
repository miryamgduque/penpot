# Phase 01 — Resizable Width

**Status:** done

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions) — worktree based on `d813923357`, two commits newer than at plan time; neither touched the panel
- [x] Check if any gaps have been filled by other work since plan creation — none
- [x] Review dependencies are met
- [x] Read relevant source files to confirm assumptions (`.ai-panel` still carried the fixed `$s-360`; grid column still `auto`)

## Checklist

- [x] ~~Write tests~~ (waived — see README "Execution Mode")
- [x] Add a `use-panel-resize` (panel-local) hook: pointer-capture drag modeled on `use-resize-hook`'s handlers, but persisting through `hooks/use-persisted-state ::panel-width` (global, not per-file)
- [x] Render a `.resize-area` handle on the panel's left edge; wire `on-pointer-down` / `on-pointer-move` / `on-lost-pointer-capture`
- [x] Apply the width as an inline style on the `:aside`; drop the fixed `width` from `.ai-panel` (kept 360 as `panel-min-width` in cljs)
- [x] Clamp: min 360px, max `0.5 × window width` (re-clamped on a window `resize` listener)
- [x] Lint pass — stylelint clean for the additions (file's 20 pre-existing errors untouched); clj-kondo unavailable in this environment (no binary, no JVM/bb), so the cljs got a manual review + paren-balance check instead
- [x] ~~Preview review~~ deferred to post-merge verification with the user (worktree has no running dev env)
- [x] ~~Human approval before commit~~ moved to the merge gate (see README "Execution Mode")
- [x] Committed with a gitmoji commit (`:sparkles: Make the agent panel resizable`)

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

**Execution discoveries (2026-07-15):**
- `dom/get-window-size` (not `get-window-width`) is the width helper; `dom/get-client-position` returns a `gpt/point` record, so `(:x pos)` keyword access works.
- No Clojure lint tooling exists on this machine outside devenv (no clj-kondo, JVM, or babashka); stylelint ran from the main checkout's `node_modules` against the worktree file with `--config-basedir`.
- The handle overlays the panel's `border-inline-start`; kept the sidebar's `$s-8` hit width and `$z-index-4`.
