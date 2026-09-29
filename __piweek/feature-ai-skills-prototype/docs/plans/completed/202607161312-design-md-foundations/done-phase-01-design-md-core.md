# Phase 01 — design-md core (parse / serialize / validate)

**Status:** done

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions) — tree was clean, only this plan folder untracked
- [x] Check if any gaps have been filled by other work since plan creation — none (created same session)
- [x] Review dependencies are met
- [x] Read relevant source files to confirm assumptions (`design_doc.cljs`, marked usage in `ui/components/markdown.cljs`)

## Checklist

- [x] Add `js-yaml` to `frontend/package.json` dependencies (was only transitive) — landed as devDependency (the convention: `marked` etc. live there), resolved 4.3.0, lockfile updated via container pnpm install
- [x] New ns `app.main.data.workspace.design-md` — pure, no store deps:
  - [x] `parse` — `{:frontmatter <string-keyed map|nil> :body :error}`; legacy docs pass through; broken YAML → readable `:error`, raw doc kept as body
  - [x] `serialize` — canonical key order via an ordered js-obj (hash maps >8 keys forget insertion order)
  - [x] `problems` — name required, colors/typography/rounded/spacing shape checks, dangling `{path.to.token}` refs in components flagged; nil frontmatter always passes; unknown top-level keys pass
  - [x] `canonical-sections` exposed as data
- [x] Tests first: 18 tests incl. round-trip, legacy, broken YAML, scalar frontmatter, dangling ref, non-color value, key ordering
- [x] Registered in BOTH `runner.cljs` `:require` AND `test-namespaces`; verified the tests appear in run output
- [x] Lint + typecheck pass — kondo 0/0, cljfmt clean (it also sorted a pre-existing out-of-order require, skill-gen-test), 783 tests / 2562 assertions, 0 failures
- [x] Human approval received
- [x] Committed: 14031cae08 `:sparkles: Add DESIGN.md parse/serialize/validate core`

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — phase 02 proceeds as planned

## Files

- `frontend/package.json` — add js-yaml dependency
- `frontend/src/app/main/data/workspace/design_md.cljs` — new: pure parse/serialize/validate
- `frontend/test/frontend_tests/data/design_md_test.cljs` — new: tests
- `frontend/test/frontend_tests/runner.cljs` — register test ns (require + vector)

## Notes

- Keep this ns PURE (no `st/state`, no plugin-data) so `agent_tools` and
  `ai_panel` can both require it without cycles — the refs/design-doc cycle
  gotcha from the vibes plan applies here.
- js-yaml `load` can throw — wrap and surface as `:error` string; the agent
  needs a readable message to retry on.
- Don't over-validate: the spec is alpha; unknown top-level keys should pass
  (warn at most). The gate is "can the form editor and the prompt consume it",
  not spec pedantry.
