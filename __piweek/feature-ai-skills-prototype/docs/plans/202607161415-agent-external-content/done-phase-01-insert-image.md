# Phase 01 — insert_image tool

**Status:** done (live verify deferred to the post-merge testing pass)

## Goal

One new agent tool `insert_image` that fetches an image by URL (server-side, SSRF
already guarded) and places it as an image shape. Placeholder recipes live in the
tool description so the model composes URLs itself — no per-service code.

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Re-read `media.cljs` — the image shape is a `:rect` with a `:fill-image` fill built by `types.fills/create` (`image-uploaded`, `media.cljs:73-95`); note `image-uploaded` centers on x/y, the tool uses top-left like `create_shape`
- [x] Confirm the tool-result contract: handler chains `rp/cmd!` → shape creation → result map in one observable
- [x] Smoke-tested the three services: placehold.co (`.png`) and DiceBear answer 200 with content-length; picsum 302→fastly with content-length on the final hop (within the ≤3 redirect budget). picsum answers 405 to HEAD — irrelevant, the backend uses GET

## Checklist

- [x] Tests (15) in `agent_tools_test.cljs`: URL validation (missing/relative/ftp/javascript/data schemes), geometry (intrinsic, both overrides, single-dimension aspect scaling, rounding), error translation (each code names its fix); all 15 confirmed present in `node target/tests/test.js` output — 798 tests, 0 failures
- [x] `insert_image` spec added to `tool-specs` with the three keyless recipes in the description
- [x] Handler: `rp/cmd! :create-file-media-object-from-url` → rect + fill-image via `cb/add-object` + `dch/commit-changes`; returns `{:id :mediaId :width :height :parentId :note}`
- [x] Wired into the `execute-tool` dispatch
- [x] Error mapping via `media-error-message` (`:ssrf-blocked-target`, `:unknown-size`, `:media-type-not-allowed`, `:media-max-file-size-reached`, `:unable-to-download-image`; fallback names the code)
- [x] clj-kondo 0 errors/0 warnings, cljfmt clean, one-shot `main` build compiles (0 warnings)
- [ ] Live verify in devenv — DEFERRED: the running watch serves the main checkout, not this worktree; per Santi's direction (2026-07-16) all phases land first, then merge + one consolidated live-testing pass. Console recipe: `at.execute_tool("insert_image", m)` with each of the three services + an SSRF probe (`http://localhost:6060`)
- [x] Human approval: Santi pre-approved phase-by-phase commits for this plan (2026-07-16)
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec + handler + dispatch; also extracted `reflow-parent!` out of `create-shape` so both tools share the grid/flex reflow
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — tests

## Notes

- SSRF is free here: the download happens in `media/download-image` which keeps
  the check on per hop. The browser never touches the external host.
- Position is TOP-LEFT (`create_shape` convention), deliberately unlike
  `image-uploaded` which centers on the drop point.
- `width`/`height`: both → exact; one → aspect-preserving scale (rounded);
  none → intrinsic. `keep-aspect-ratio true` rides the fill-image.
- Refactor for later phases: `reflow-parent!` is now the shared "child landed in
  a laid-out board" helper — phase 02's `insert_icon` should use it too.
- Live-verify checklist for the post-merge pass is consolidated in the README
  (Completion Summary section will track it).
