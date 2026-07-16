# Phase 03 — Google Fonts

**Status:** todo

## Goal

`search_fonts` over the in-memory catalog and `set_font` to apply a family/variant
to text shapes, loading the font on demand. All frontend; Penpot already ships the
Google Fonts integration.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `frontend/src/app/main/fonts.cljs`: catalog (`google-fonts` `:28-29`, `fontsdb` `:49`), lookup (`find-font-family` `:83-90`, `get-font-data` `:73`, `resolve-variants` `:92`), `ensure-loaded!` (`:214+`, promise-based, idempotent)
- [ ] Confirm the `:google-fonts-provider` flag gate (`fonts.cljs:70-71`) — if the instance disables Google fonts, `search_fonts` should say so rather than return an empty list
- [ ] Find how text font attrs are applied today: existing `set_text` handler (`agent_tools.cljs:1457`) and `app.main.data.workspace.texts` — which event updates `font-id`/`font-family`/`font-variant-id`/`font-weight`/`font-style` on text content nodes (root vs paragraph vs span scope)

## Checklist

- [ ] Tests: search matching (case-insensitive, substring), variant resolution, set_font input validation (unknown font id, non-text shape); runner registration confirmed
- [ ] `search_fonts {query, limit?}` — search `fontsdb` by family; return `{id, family, variants: [...]}` rows; include builtin (local) fonts, mark backend `:google` vs `:builtin`
- [ ] `set_font {shape-ids, font-id, variant-id?}` — validate shapes are text, resolve variant (default via `resolve-variants` pick regular/400), `ensure-loaded!` first, then emit the text-attr update event; return applied family+variant and shape count
- [ ] Non-text shapes in the list → validation error naming the offending ids (silent-no-op pattern is the enemy — see variants lesson)
- [ ] Wire both into `execute-tool` dispatch
- [ ] Lint + cljfmt pass
- [ ] Live verify via console: search "inter", apply to a text shape, glyphs render after load
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — two specs + handlers + dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — tests

## Notes

- Keep search results small (limit default ~15) — the catalog has ~1500 families
  and tool results are 20k-char capped anyway.
- Follow-up candidate (not this phase): a typography-token bridge (`create_token`
  with `:font-family` type already works via the generic token tools).
