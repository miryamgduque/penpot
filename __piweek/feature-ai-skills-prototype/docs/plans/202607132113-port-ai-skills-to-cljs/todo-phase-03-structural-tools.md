# Phase 03 — Structural tools: create / modify / nest

**Status:** todo

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

- [ ] `create_shape` tool: input `{type: rect|ellipse|board, x, y, width, height, name?, parentId?}`. Build via `cts/setup-shape` + `pcb/add-object` (+ `pcb/change-parent` when `parentId`), commit; return the new shape id. Geometry from args (not viewport-center) so the agent controls placement.
- [ ] `modify_shape` tool: input `{shapeId, name?, x?, y?, width?, height?, fill?, stroke?}`. Route: name → `update-shapes`; x/y → `transforms/update-position`; w/h → `transforms/update-dimensions`; fill/stroke → `colors/*` (subject to Phase 06 enforcement — for now accept token refs and raw, tighten in P6).
- [ ] `nest_shape` tool: input `{shapeId, parentId, index?}` → `relocate-shapes`.
- [ ] Wrap each tool's writes in one undo transaction so a tool call = one history step.
- [ ] Async note in tool results: geometry settles asynchronously under `render-wasm/v1` — return "applied; re-read to confirm geometry" rather than echoing final selrect.
- [ ] `make lint/frontend`, `make typecheck/frontend`
- [ ] Preview: "make a 200×120 board at 0,0 with a red rectangle inside" → board + rect appear nested; "move it to 300,300" works; **Cmd+Z undoes each tool step**.
- [ ] Human approval; commit `feat(workspace): native create/modify/nest agent tools`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; record any geometry/async gotchas discovered

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — tool declarations
- `frontend/src/app/main/data/workspace/agent_tools.cljs` *(new, optional)* — the mutation tool implementations (keep `agent.cljs` about the loop)

## Notes

- Prefer the **explicit low-level** create path (`setup-shape`+`add-object`) over `create-and-add-shape` when the agent supplies coordinates — the latter forces viewport-center placement and auto-select.
- x/y is **per-id** through `update-position`; loop for multi-shape moves.
- This phase intentionally leaves color enforcement loose; Phase 06 tightens the fill/stroke inputs.
