# Phase 05 — Token tools: create & apply

**Status:** done

## Goal

Port the two token tools: `create_color_token` (add a color token to the file library) and `apply_tokens` (batch-bind a color token to shapes' fill/stroke). These are the **safe coloring path** — the write that `token-only-colors` never rejects — and the target the enforcement error (Phase 06) will point the agent toward.

## Before Start

- [ ] Re-read the internal token APIs:
  - create: `dwtl/create-token set-id token` (`data/workspace/tokens/library_edit.cljs:526`); token = `(ctob/make-token {:type :color :name … :value "#RRGGBB" :id (cthi/new-id! name)})`; resolvability via `ts/resolve-tokens` (see `plugins/tokens.cljs:418-438`)
  - sets: `refs/selected-token-set-id` (`refs.cljs:485`), `ctob/get-tokens-in-active-sets`, set CRUD in `library_edit.cljs:308-345`
  - apply: `dwta/toggle-token {:token :attrs #{:fill}/#{:stroke-color} :shape-ids …}` (`tokens/application.cljs:794`), color→attr map `:color` entry (`:941-947`)
- [ ] Re-read the **async** note: `apply-token` resolves through StyleDictionary as an **observable** (`application.cljs:678-727`, `style_dictionary.cljs:586,627`) — settles on a later tick
- [ ] Re-read the plugin tools this replaces: `apply-tokens` batch + `create-color-token` (`agent.ts:128-166`, `plugin.ts:781-805`)

## Checklist

- [x] `create_color_token` tool: input `{name, value(hex)}`. `ctob/make-token {:type :color …}` → **1-arg `dwtl/create-token`** (targets the current set, or auto-creates one — simpler than managing a named set). Returns the token name. *(Dropped the `set?` param for now; auto-set is fine for the prototype.)* ✓
- [x] `apply_tokens` tool: input `{applications: [{shapeId, tokenName, properties?: [fill|stroke]}]}`. Resolves each token by name via `ctob/get-all-tokens-map`, then `dwta/toggle-token` with the mapped attrs (`#{:fill}` / `#{:stroke-color}`, default fill). Returns a per-application `:ok`/`:error` result so the model sees failures. *(No undo transaction — token application resolves asynchronously through StyleDictionary, which doesn't fit a synchronous undo batch; each application is its own history entry.)* ✓
- [x] Async note in the result ("tokens resolve asynchronously — verify with read_design/audit_file"). ✓
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, `shadow-cljs compile main` → **0 warnings**. ✓
- [x] Preview (live devenv): **console** — created `color.brand.primary` (#6366f1); applied to a rect → after async settle the fill **resolved to #6366f1** and the shape gained `:applied-tokens` (token-bound, not a raw color). **LLM (Haiku)** — "Create a color token color.accent #f59e0b, then create a rectangle and apply that token to its fill" → chained **`create_color_token` → `create_shape` → `apply_tokens`**; amber rectangle on canvas; agent explained live token-linking. No crash. ✓
- [ ] Human approval; commit `:sparkles: Native create/apply color-token tools`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; record the settle behavior (did verification need a re-read tick?)

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `create_color_token`, `apply_tokens`
- `frontend/src/app/main/data/workspace/agent.cljs` — register the two tool declarations

## Notes

- Applying a token stamps `:applied-tokens` on the shape AND sets the resolved raw color; the fill therefore carries a token reference — that reference is exactly what Phase 06's enforcement treats as "allowed."
- Keep the atomic ops separate from `modify_shape`'s raw fill path so Phase 06 can allow `apply_tokens` unconditionally while gating raw fills.
