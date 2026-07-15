# Phase 02 — Frontend data + state-aware catalog

**Status:** todo

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

- [ ] **Data ns `data/workspace/user-skills.cljs`** (or fold into `skill_state.cljs`): fetch via
      `:get-skills {}` into app-db (`[:user-skills]`, a vector of skill maps with `:source :user`);
      a `create-skill` action (used by Phase 04) that calls `:create-skill` + refetches.
- [ ] **State-aware catalog in `agent_skills.cljs`:** add `(user-skills state)` (from app-db, shaped
      like catalog entries: `:name :label :blurb/:description :mode :category :enabled true`) and a
      `(full-catalog state)` = built-in `catalog` groups **with user skills appended to their
      category** (new categories only if a user skill's category isn't one of the built-ins — but the
      story files under the closest existing category, so expect Audits/Build/Auto-fix).
- [ ] **Thread `state` through the last two:** `find-skill` → `find-skill state name` (search the
      full catalog) and `skill-body` → `skill-body state name` (user skill → its stored `:body`;
      built-in → `aikit-bodies`). Update call sites (`skills-tab*` detail; `get_design_skills` in
      `agent_tools.cljs`).
- [ ] **`resolved-catalog` / manifest / enabled / system-prompt** iterate `full-catalog state`
      instead of the static `catalog` — user skills now flow through resolve-enabled (default from
      their `:enabled`), the router index, and `get_design_skills` automatically.
- [ ] **`skills-tab*`** renders `full-catalog state` (deref user skills) instead of `ask/catalog`.
- [ ] **Fetch on mount:** emit `fetch-user-skills` alongside `fetch-skill-states` in `ai-panel*`.
- [ ] Unit tests: a user skill in state appears in `enabled-skills` / `resolved-enabled-map` /
      `catalog-manifest`; `skill-body` returns its stored body; toggling it off via the US #8 path
      drops it from `enabled-skills`.
- [ ] `make lint` + frontend build, 0 warnings
- [ ] Human approval received
- [ ] Committed (`:sparkles:`)

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
