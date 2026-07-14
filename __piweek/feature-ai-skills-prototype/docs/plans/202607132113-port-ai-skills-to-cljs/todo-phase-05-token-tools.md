# Phase 05 — Token tools: create & apply

**Status:** todo

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

- [ ] `create_color_token` tool: input `{name, value(hex), set?}` (set defaults to the active/"core" set; create the set if missing via `create-token-set`). Build `ctob/make-token`, `dwtl/create-token`; return the token name. Validate hex (`normalizeHex` equivalent).
- [ ] `apply_tokens` tool: input `{applications: [{shapeId, tokenName, properties?: [fill|stroke]}]}`. For each, resolve the token, `dwta/toggle-token` with the mapped attrs (default `#{:fill}`). Batch under one undo transaction.
- [ ] Because application is async, the tool result says "tokens applied — verify with `read_design`/`audit_file` (do not assume in the same call)" — matching the source tool's contract.
- [ ] `make lint/frontend`, `make typecheck/frontend`
- [ ] Preview: "create a token color.brand.primary = #6366f1" → token appears; "apply it to the rectangle's fill" → rect turns indigo, bound to the token (fill shows the token ref, not a raw color).
- [ ] Human approval; commit `feat(workspace): native create/apply color-token tools`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; record the settle behavior (did verification need a re-read tick?)

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `create_color_token`, `apply_tokens`
- `frontend/src/app/main/data/workspace/agent.cljs` — register the two tool declarations

## Notes

- Applying a token stamps `:applied-tokens` on the shape AND sets the resolved raw color; the fill therefore carries a token reference — that reference is exactly what Phase 06's enforcement treats as "allowed."
- Keep the atomic ops separate from `modify_shape`'s raw fill path so Phase 06 can allow `apply_tokens` unconditionally while gating raw fills.
