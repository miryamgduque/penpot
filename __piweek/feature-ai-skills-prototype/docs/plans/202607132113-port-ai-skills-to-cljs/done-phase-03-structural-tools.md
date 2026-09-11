# Phase 03 — Structural tools: create / modify / nest

**Status:** done

## Goal

The heart of dropping `execute_code`: give the agent native tools to **build and change** basic structure — `create_shape` (rectangle / ellipse / board), `modify_shape` (name, geometry, fills, strokes), and `nest_shape` (reparent/append into a board or group). All via the internal `pcb`→`commit-changes` pipeline, so changes are normal, undoable Penpot edits.

## Before Start

- [ ] Re-read the internal entry points (all proven by `frontend/src/app/plugins/*.cljs`):
  - create: `cts/setup-shape` (`common/.../types/shape.cljc:612`) + `pcb/add-object` + `dch/commit-changes`, or `dwsh/create-and-add-shape` (`shapes.cljs:334`); board = `:type :frame`
  - modify attrs: `dwsh/update-shapes ids update-fn opts` (`shapes.cljs:151`)
  - fills/strokes: `data/workspace/colors` — `change-fill` (`:190`), `change-stroke-color` (`:390`)
  - geometry: `data/workspace/transforms` — `update-position` (`:1075`, per-id), `update-dimensions` (`:387`, `#{:width :height}`)
  - nest: `dwsh/relocate-shapes #{ids} parent-id to-index` (`shapes.cljs:524`)
- [ ] Confirm undo batching: `dwu/start-undo-transaction` / `commit-undo-transaction`, or the `update-shapes-buffer-*` accumulator (`shapes.cljs:51-149`)
- [ ] Note the `:can-edit` gate at `changes.cljs:250` — tools must no-op gracefully in read-only files

## Checklist

- [x] `create_shape` tool: input `{type: rect|ellipse|board, x, y, width, height, name?, fill?, parentId?}`. Build via `cts/setup-shape` + `cb/add-object` (parent via the shape's `:parent-id`/`:frame-id` when `parentId`) → `dch/commit-changes`; returns the new id. Placement from args. **Added an optional `fill`** (set on `:fills`) so "create a red rectangle" works in one call — the model tried this and had no way to fill on create otherwise. ✓
- [x] `modify_shape` tool: input `{shapeId, name?, x?, y?, width?, height?, fill?, stroke?}`. name → `update-shapes`; x/y → `transforms/update-position`; w/h → `transforms/update-dimensions`; fill/stroke → `update-shapes` with raw `:fills`/`:strokes` (proven plugin pattern; Phase 06 reworks this for enforcement). ✓
- [x] `nest_shape` tool: input `{shapeId, parentId, index?}` → `dwsh/relocate-shapes #{id} parent-id index`. ✓
- [x] `modify_shape` writes wrapped in one `start/commit-undo-transaction` → one history step. ✓
- [x] Async note in every tool result ("verify with read_design"). ✓
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, `shadow-cljs compile main` → **0 warnings**. ✓
- [x] Preview (live devenv, Haiku): "Create a board named Card… then add a rectangle inside it…" → **Card board with nested rectangle built on canvas** (2× `create_shape` chips, no crash). Console-verified all paths: create rect/ellipse/board, create-with-parent, create-with-fill, modify fill/geometry, `nest_shape` relocate. ✓
- [x] **Crash found + fixed:** creating/mutating shapes *while a path/pen drawing tool was active* crashed Penpot's `path-preview*` overlay. Fix: each mutating tool emits `:interrupt` first to clear transient drawing/edition state. **Deterministically validated** — reproduced the `:path`+draw-area state, then `create_shape` ran cleanly and unmounted the overlay. ✓
- [ ] Human approval; commit `:sparkles: Native create/modify/nest agent tools`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; record any geometry/async gotchas discovered

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — tool declarations
- `frontend/src/app/main/data/workspace/agent_tools.cljs` *(new, optional)* — the mutation tool implementations (keep `agent.cljs` about the loop)

## Notes

- Prefer the **explicit low-level** create path (`setup-shape`+`add-object`) over `create-and-add-shape` when the agent supplies coordinates — the latter forces viewport-center placement and auto-select.
- x/y is **per-id** through `update-position`; loop for multi-shape moves.
- This phase intentionally leaves color enforcement loose; Phase 06 tightens the fill/stroke inputs.
