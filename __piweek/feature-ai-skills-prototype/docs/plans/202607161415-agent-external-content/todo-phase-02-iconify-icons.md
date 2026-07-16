# Phase 02 — Iconify icons

**Status:** todo

## Goal

`search_icons` + `insert_icon` against api.iconify.design (CORS-open, keyless,
200k+ open-source icons). Search returns ids; insert fetches the SVG and imports
it as Penpot shapes via the existing SVG pipeline.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Confirm Iconify endpoints from the browser (CORS): `GET https://api.iconify.design/search?query=home&limit=20` → `{icons: ["prefix:name", ...]}`; `GET https://api.iconify.design/<prefix>/<name>.svg?height=24` → raw SVG
- [ ] Re-read the SVG import path: `valid-svg-string?` (`media.cljs:47`), `svg->clj` (`media.cljs:58-69`), `svg-uploaded`/`svg/add-svg-shapes` (`media.cljs:97-106`) — confirm what `add-svg-shapes` needs (position, page) and what it returns (created shape ids?)
- [ ] Check how the token-only-colors guard (`agent_tools.cljs:1098-1238`) interacts with imported SVG fills — imported fills come from the SVG file, not from an agent-set color param; confirm the guard does not fire on `add-svg-shapes`

## Checklist

- [ ] Tests: input validation, icon-id parsing (`prefix:name`), search result mapping; runner registration confirmed in test output
- [ ] `search_icons {query, limit?}` — fetch, return the id list plus total; cap limit (≤32)
- [ ] `insert_icon {icon, x, y, size?}` — fetch SVG at requested height, `valid-svg-string?` gate, `svg->clj` → `add-svg-shapes` at position; return created shape id(s) and the icon id
- [ ] No `color` param in v1 — icons import as authored (mostly `currentColor`→black); recoloring goes through the existing token-guarded fill tools afterwards (state this in the tool description so the model knows the two-step)
- [ ] Network failure / 404 icon → one-line tool error (offline instances: mention self-hostable Iconify API as a follow-up, don't build it)
- [ ] Wire both into `execute-tool` dispatch
- [ ] Lint + cljfmt pass
- [ ] Live verify via console `at.execute_tool` for both tools
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

- This is a **frontend fetch** of an external host (unlike phase 01) — acceptable
  because the host is fixed (`api.iconify.design`), the response is validated SVG,
  and no user data rides the request. Hardcode the base URL; do not accept
  arbitrary hosts through this tool.
- `svgo/optimize` (`media.cljs:45`) is applied by `svg->clj` — keep it; icon SVGs
  shrink nicely.
