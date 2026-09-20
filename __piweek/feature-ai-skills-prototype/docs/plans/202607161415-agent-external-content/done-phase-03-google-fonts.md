# Phase 03 — Google Fonts

**Status:** done (live verify deferred to the post-merge testing pass)

## Goal

`search_fonts` over the in-memory catalog and `set_font` to apply a family/variant
to text shapes, loading the font on demand. All frontend; Penpot already ships the
Google Fonts integration.

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] fonts.cljs re-read: `fontsdb` lens atom (`:49`), `find-font-family` case-insensitive (`:83-90`), `get-variant` falls back to the default variant (`:267-270`), `get-default-variant` prefers "regular" (`:261-265`), `ensure-loaded!` returns an idempotent promise (`:214+`)
- [x] `:google-fonts-provider` flag gate confirmed (`:70-71`) — when off, only `:builtin` fonts register; `search_fonts` detects the absence of any `:google`-backend font and says so in the empty-result note instead of returning a bare `[]`
- [x] Apply path confirmed from the typography menu (`typography.cljs:250-261`): a font change is the FIVE-key attr map `{font-id font-family font-variant-id font-weight font-style}` (partial maps leave stale attrs) applied via `dwt-text/update-all-attrs` (`texts.cljs:880-890`, wraps one undo transaction; render-wasm waits for the `:font-loaded` event before re-measuring — `texts.cljs:859-866`)

## Checklist

- [x] Tests (14): query validation, case-insensitive/substring search, limit cap, rows carry variants+backend, `font-attrs` five-key completeness, variant validation (accepts real/nil, lists available on miss), non-text validation (accepts text, names offenders, flags unknown ids with the page mention), set_font param validation (shapeIds required, unknown family points at search_fonts, some font param required); 820 tests 0 failures, names confirmed in output
- [x] `search_fonts {query, limit?}` — searches `@fonts/fontsdb`, includes builtins, marks backend google/builtin
- [x] `set_font {shapeIds, family|fontId, variantId?}` — resolves font (family case-insensitive), validates text-ness and variant up front (silent-no-op is the enemy), `ensure-loaded!` → `update-all-attrs`, load failure maps to a friendly error
- [x] Non-text shapes rejected naming the offending shapes
- [x] Wired both into `execute-tool` dispatch
- [x] clj-kondo 0/0, cljfmt clean, `main` build 0 warnings
- [ ] Live verify — DEFERRED to the post-merge testing pass: search "inter", apply to a text shape, glyphs render after load; apply variant "700"; try a rect (expect the named rejection)
- [x] Human approval: Santi pre-approved phase-by-phase commits for this plan (2026-07-16)
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — two specs + handlers + dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — tests

## Notes

- `set_font` accepts `family` OR `fontId` — models know family names, not
  Penpot's internal ids, so family is the primary path.
- `variantId` is validated against the font's real variants even though
  `get-variant` would silently fall back — a silent fallback to regular when
  the agent asked for bold is exactly the no-op trap the plan warns about.
- Follow-up candidate (backlog): typography tokens bridge — `create_token` with
  a `:font-family` type already works via the generic token tools.
