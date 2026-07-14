# Phase 04 — Text & component tools

**Status:** done

## Goal

Two more `execute_code` replacements: `create_text` (create a text shape and set its string content, laying out glyphs under WASM) and `create_component` (componentize shapes, returning the new component id).

## Before Start

- [ ] Re-read the plugin's `:createText` (`plugins/api.cljs:400-432`): `cts/setup-shape {:type :text … :grow-type :auto-width}` → `(update :content txt/change-text text {:fills …})` → `pcb/add-object` + commit → `dwwt/resize-wasm-text-debounce` under `render-wasm/v1`
- [ ] Re-read `txt/change-text` (`common/.../types/text.cljc:474`) and `data/workspace/wasm-text` (`resize-wasm-text-debounce`/`resize-wasm-text-all`)
- [ ] Re-read `dwl/add-component id-ref ids` (`data/workspace/libraries.cljs:425`) and the plugin's `createComponent` (`plugins/library.cljs:1035-1046`): `(atom nil)` as `id-ref`, set of ids, emit, then read `@id-ref`
- [ ] Confirm `ctn/valid-shape-for-component?` eligibility so the tool can pre-check and give a clean error

## Checklist

- [x] `create_text` tool: input `{text, x, y, name?, fill?, parentId?}`. Builds an auto-width text shape (`setup-shape {:type :text … :grow-type :auto-width}` → `txt/change-text` for content → `dissoc :position-data` → `cb/add-object` → commit), then `dwwt/resize-wasm-text-debounce` under `render-wasm/v1`. Returns the id + async note. *(Dropped the `width?` param — auto-width text, matching the plugin.)* ✓
- [x] `create_component` tool: input `{shapeIds?}` (default current selection). `id-ref (atom nil)` → emit `dwl/add-component` → read `@id-ref`; returns the component id, or a clear error if `@id-ref` is nil (shapes ineligible). Wrapped in try/catch. ✓
- [x] Both tools registered; chips render via Phase 02 machinery. Both emit `:interrupt` first (Phase 03 crash fix). ✓
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, `shadow-cljs compile main` → **0 warnings**. ✓
- [x] Preview (live devenv): **console** — `create_text` "Welcome back" created; `create_component` on a rect returned `componentId` and the file gained a component (no crash). **LLM (Haiku)** — "Add a text heading that says Dashboard at 300,600…" → ✓ `create_text` chip, "Dashboard Heading" on canvas, model echoed the async-settle note. Screenshot confirms the text + the Main component in the layers/DESIGN panel. ✓
- [ ] Human approval; commit `:sparkles: Native create_text and create_component tools`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; note the WASM-text settle timing + whether a small verify delay was needed

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `create_text`, `create_component`
- `frontend/src/app/main/data/workspace/agent.cljs` — register the two tool declarations

## Notes

- `:content` is a structured rich-text tree, not a string — `change-text` builds it from a plain string; rich per-range styling is out of scope for this phase.
- The component id comes back through the side-channel atom; read it synchronously right after `st/emit!` like the plugin does. If it reads `nil`, fall back to observing the next selection/commit.
