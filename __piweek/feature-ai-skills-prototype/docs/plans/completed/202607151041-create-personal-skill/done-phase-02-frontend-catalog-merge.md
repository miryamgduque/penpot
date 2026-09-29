# Phase 02 — Frontend data + state-aware catalog

**Status:** done

## Goal

Fetch the user's created skills into app state and **merge them into the catalog** so they render as
cards, feed `enabled-skills` / `get_design_skills`, toggle through the existing enable/disable
machinery, and serve their stored body — with **no special-casing** at the call sites.

## Before Start

- [ ] Re-read `agent_skills.cljs`: `catalog` (static `def`), `resolved-catalog` (private, `state`),
      `find-skill` / `skill-body` (no `state` yet), `catalog-manifest` / `resolved-enabled-map` /
      `enabled-skills` / `system-prompt-section` (take `state`)
- [ ] Re-read `skill_state.cljs` (US #8 fetch/store pattern) and `refs/resolved-skills-enabled`
- [ ] Re-read `skills-tab*` (iterates `ask/catalog`) and `ai_panel.cljs` fetch-on-mount effect
- [ ] Confirm the Phase 01 RPC shape (`get-skills`)

## Checklist

- [x] **Data ns `data/workspace/user_skills.cljs`:** `fetch-user-skills` (`:get-skills {}` →
      `[:user-skills]`) + `create-skill` (`:create-skill` + refetch, on-success/on-error meta) for
      Phase 04.
- [x] **State-aware catalog in `agent_skills.cljs`:** `user-skills state` (app-db → catalog-shaped
      entries: name/label/blurb=description/mode/category/enabled + example=trigger, what=description,
      body, `:user? true`) and `full-catalog state` = built-in groups **with user skills merged into
      their category**, plus an appended group for any non-built-in category.
- [x] **Threaded `state`:** `find-skill` → `find-skill state name` (searches `full-catalog`);
      `skill-body` → `skill-body state name` (user skill → its stored `:body` **verbatim, no
      preamble** since generated for native tools; built-in → aikit body + preamble). `catalog-
      manifest` 2-arity passes `state` to `skill-body`.
- [x] **`resolved-catalog` iterates `full-catalog state`** → user skills flow through resolve-enabled,
      the router index, and `get_design_skills` automatically (manifest/enabled/system-prompt unchanged).
- [x] **`skills-tab*`** renders `refs/skills-catalog` (the merged catalog) for the list + finds the
      selected skill in it (dropped the `ask/catalog` / `ask/find-skill` calls). New `refs/skills-catalog`.
- [x] **Fetch on mount:** `dusk/fetch-user-skills` added alongside `fetch-skill-states` in `ai-panel*`.
- [x] Unit tests (`workspace-user-skills-test`, 3 deftests / 13 assertions): merges into full-catalog
      (incl. unknown-category group); appears in enabled-skills / resolved-enabled-map / manifest;
      US #8 off-override drops it; `skill-body` returns the stored body (built-in keeps its preamble);
      `find-skill` category-tags it.
- [x] `clj-kondo` 0 errors; shadow **test build 814 compiled, 0 warnings**; new tests + US #8
      skill-state test green (2 suite failures are pre-existing plugin/math tests, unrelated)
- [x] Human approval received
- [x] Committed (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links

## Files

- `frontend/src/app/main/data/workspace/user_skills.cljs` — **new** (fetch + create action)
- `frontend/src/app/main/data/workspace/agent_skills.cljs` — state-aware catalog + `state` on
  `find-skill`/`skill-body`
- `frontend/src/app/main/data/workspace/agent_tools.cljs`, `ui/workspace/ai_panel.cljs` — call sites
- `frontend/test/frontend_tests/data/workspace_user_skills_test.cljs` — **new**

## Notes

- Keep the merge in `agent_skills` so every consumer already taking `state` gets user skills for
  free; the only new `state` params are on `find-skill` / `skill-body`.
- No delete/edit UI here — just create + display + route + toggle.
