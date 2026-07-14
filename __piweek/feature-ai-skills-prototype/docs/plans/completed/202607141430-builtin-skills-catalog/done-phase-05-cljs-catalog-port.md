# Phase 05 — Native CLJS catalog port (workspace Agents panel)

**Status:** done

> Documented after implementation: this phase was built directly at the user's request
> ("option D") to get the catalog into the real devenv workspace, then written up here.

## Goal

Port the built-in skills catalog into the **native CLJS workspace Agents panel**
(`frontend/`), so the Skills tab shows the real catalog inside Penpot itself — not only the
standalone React `ai-skills` prototype (Phases 03/04). Replaces the Skills-tab placeholder
("Skills manager — coming in its own story.").

## Before Start

- [x] Read `frontend/src/app/main/ui/workspace/ai_panel.cljs` (rumext/mf, `stl/css`, DS components)
- [x] Read `frontend/src/app/main/ui/workspace/ai_panel.scss` (design-system tokens)
- [x] Confirm frontend accent tokens for badges (`--color-accent-info/-secondary/-warning`)
- [x] Confirm no existing CLJS catalog source (there is none — data is hardcoded here)

## Checklist

- [x] `ai_panel.cljs`: add `skills-catalog` def (10 entries grouped Audits/Build/Auto-fix) +
      `mode-label` map — mirrors skills-core `builtinCatalog()` + the curated labels/blurbs
- [x] `ai_panel.cljs`: add `skills-tab*` component (grouped cards, name/blurb/mode badge,
      `off by default` + muted for the auto-fix skill); read-only
- [x] `ai_panel.cljs`: wire the `"skills"` tab case to `[:> skills-tab*]` (drop placeholder)
- [x] `ai_panel.scss`: catalog group/label/card/badge styles; mode badges colored via
      `--color-accent-info` (suggest) / `-secondary` (review) / `-warning` (auto-fix)
- [x] Compiles clean (shadow-cljs `:main` 0 warnings/errors; scss compiled)
- [x] Preview review: verified live in the real devenv workspace (see Notes) — 10 cards,
      correct groups, rename-layers off, badges = real accent tokens (blue/purple/gold)
- [ ] Human approval received
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Note the CSS-pipeline gotcha discovered (below)
- [ ] Decide Phase 04 (detail view) target: React vs this CLJS panel
- [ ] Decide whether the React `ai-skills` catalog (Phase 03) is retired

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — catalog data, `skills-tab*`, tab wiring
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — catalog + colored mode-badge styles

## Notes

- **Verified in the real devenv workspace** (Preview tool → login demo user → open file →
  Alt+B → Skills). Computed styles confirmed: suggest `#0e9be9`, review `#bb97d8`,
  auto-fix `#fe9c07`; cards padded/bordered; names white/600; rename-layers muted + off.
  `preview_screenshot` could not capture (render-wasm canvas keeps the renderer busy →
  capture hangs); Claude-in-Chrome blocks all localhost/LAN, so no browser screenshot either.
- **Devenv CSS gotcha:** `frontend/scripts/watch.js` globs SCSS at startup and `concatSass`
  emits only that startup set, so `ai_panel.scss` (added after this long-running watch began)
  was absent from `main.css` — the native panel had been unstyled. Forced a full re-glob by
  touching a file under `resources/styles` (triggers `compileSassAll`). Not a code issue.
- **Data duplication:** the catalog copy now lives in both `skills-core` (TS, for the React
  panel) and here (CLJS, hardcoded). Longer term this should share one source; acceptable for
  the prototype. Router + shared docs excluded by simply not listing them.
- **Scope/decision open:** this duplicates Phase 03/04. If the native panel is the real home,
  Phase 04's detail view should be built here (CLJS), and the React catalog likely retired.
