# Phase 02 — Iconify icons

**Status:** done (live verify deferred to the post-merge testing pass)

## Goal

`search_icons` + `insert_icon` against api.iconify.design (CORS-open, keyless,
200k+ open-source icons). Search returns ids; insert fetches the SVG and imports
it as Penpot shapes via the existing SVG pipeline.

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Confirmed both endpoints live: search returns `{"icons":["prefix:name",…],"total":n}` with `access-control-allow-origin: *`; the SVG endpoint returns `image/svg+xml` with `currentColor` strokes. NOTE: the search API has a floor of `limit=32` — smaller asks are ignored, so the tool trims client-side
- [x] SVG import path re-read: `add-svg-shapes` has a 4-arity form that ACCEPTS the root shape id (`svg_upload.cljs:66-71`) — the tool generates `(uuid/next)` and returns it deterministically; `{:ignore-selection? true :change-selection? false}` keeps the insert independent of the user's selection (it still nests into the board under the drop point via `top-nested-frame`)
- [x] Guard interaction: imported SVG fills come from the parsed SVG data, not from any agent color param — the token-only-colors collectors never see them; recoloring is documented as apply_tokens in the tool description

## Checklist

- [x] Tests (8): icon-id validation (missing/component-name/`mdi:`/`a:b:c` rejected with search_icons named as the fix; hyphenated ids accepted), `icon-svg-url` building, `icons-payload` limit-capping/empty-result note/insert_icon pointer; all confirmed in test output — 806 tests, 0 failures
- [x] `search_icons {query, limit?}` — blank-query rejection, limit clamped 1–64, requests `max(limit,32)` from the API and trims
- [x] `insert_icon {icon, x?, y?, size?, name?}` — status≠200 → "not found, check with search_icons"; non-SVG body rejected via `dwm/valid-svg-string?`; `dwm/svg->clj` → `svg-up/add-svg-shapes` with explicit id
- [x] No `color` param — description routes recoloring through apply_tokens (two-step)
- [x] Wired both into `execute-tool` dispatch
- [x] clj-kondo 0/0, cljfmt clean, `main` build compiles 0 warnings (new requires `dwm`/`svg-up`/`http` introduced no cycle)
- [ ] Live verify via console — DEFERRED to the post-merge testing pass: `at.execute_tool("search_icons", {query:"home"})`, then insert one; also verify a 404 id message
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

- Frontend fetch of a FIXED external host (`api.iconify.design`, hardcoded) —
  the response is validated as SVG before touching the import pipeline, and no
  user data rides the request. Arbitrary hosts do not pass through this tool.
- `svgo/optimize` applies inside `svg->clj` when the `:frontend-svgo` flag is on
  — kept as-is.
- Follow-up (backlog): self-hosted Iconify API for offline instances.
