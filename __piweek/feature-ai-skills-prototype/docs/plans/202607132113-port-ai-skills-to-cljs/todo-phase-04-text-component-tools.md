# Phase 04 — Text & component tools

**Status:** todo

## Goal

Two more `execute_code` replacements: `create_text` (create a text shape and set its string content, laying out glyphs under WASM) and `create_component` (componentize shapes, returning the new component id).

## Before Start

- [ ] Re-read the plugin's `:createText` (`plugins/api.cljs:400-432`): `cts/setup-shape {:type :text … :grow-type :auto-width}` → `(update :content txt/change-text text {:fills …})` → `pcb/add-object` + commit → `dwwt/resize-wasm-text-debounce` under `render-wasm/v1`
- [ ] Re-read `txt/change-text` (`common/.../types/text.cljc:474`) and `data/workspace/wasm-text` (`resize-wasm-text-debounce`/`resize-wasm-text-all`)
- [ ] Re-read `dwl/add-component id-ref ids` (`data/workspace/libraries.cljs:425`) and the plugin's `createComponent` (`plugins/library.cljs:1035-1046`): `(atom nil)` as `id-ref`, set of ids, emit, then read `@id-ref`
- [ ] Confirm `ctn/valid-shape-for-component?` eligibility so the tool can pre-check and give a clean error

## Checklist

- [ ] `create_text` tool: input `{text, x, y, width?, name?, parentId?, fill?}`. Build the text shape with `change-text`, add via the low-level path (avoid auto edit-mode), commit, then trigger `resize-wasm-text-*` so width/height settle. Return the new id + "geometry settles async — re-read to confirm size".
- [ ] `create_component` tool: input `{shapeIds}` (default current selection). Pre-validate eligibility; `id-ref (atom nil)`; emit `dwl/add-component`; read `@id-ref` after emit; return the component id (or a clear error if ineligible).
- [ ] Tool-chip + result rendering already handled by Phase 02; just register the two tools.
- [ ] `make lint/frontend`, `make typecheck/frontend`
- [ ] Preview: "add a heading that says Welcome at 20,20" → text renders with real size after settle; select two shapes, "make this a component" → component created, shows in assets.
- [ ] Human approval; commit `feat(workspace): native create_text and create_component tools`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; note the WASM-text settle timing + whether a small verify delay was needed

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `create_text`, `create_component`
- `frontend/src/app/main/data/workspace/agent.cljs` — register the two tool declarations

## Notes

- `:content` is a structured rich-text tree, not a string — `change-text` builds it from a plain string; rich per-range styling is out of scope for this phase.
- The component id comes back through the side-channel atom; read it synchronously right after `st/emit!` like the plugin does. If it reads `nil`, fall back to observing the next selection/commit.
