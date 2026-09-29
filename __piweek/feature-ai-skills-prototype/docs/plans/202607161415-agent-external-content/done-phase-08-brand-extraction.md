# Phase 08 — brand extraction playbook

**Status:** done (live playbook run deferred to the post-merge testing pass)

## Goal

"Import the brand from example.com" as a **skill/playbook composing the previous
phases** — no new fetch machinery. The agent: reads phase-04 metadata → screenshots
the page (07) → proposes color tokens (existing `create_token`) → inserts logo
imagery (01), gated by a hard approval stop.

## Before Start

- [x] Verify plan is still valid; phases 01, 04, 07 merged (same branch)
- [x] Placement decided WITHOUT a user round-trip (the natural precedent made it unambiguous): the native builtin catalog in `agent_skills.cljs`, Setup category, exactly like `penpot-project-vibes` — native-born `:body` served verbatim, no aikit preamble, flows through `get_design_skills` automatically
- [x] Post-US#14 field confirmed: `:reactive "on-demand"`
- [x] Metadata exposure decided: `fetch_page` deliberately does NOT expose `:meta` (its contract is digest-only) — added the thin `get_page_meta` tool instead; structured fields don't warrant the Haiku toll, and page TEXT still never reaches the main conversation

## Checklist

- [x] `get_page_meta {url}` tool — phase-04 RPC, returns `{:title :meta :note}` only (no text, no side turn); `fetch-page-error-message` grew a `[tool code]` arity so errors carry the surfacing tool's name; tests (2) for validation + prefixing
- [x] `penpot-import-brand` skill written: metadata → screenshot → palette FROM the screenshot cross-checked with theme-color → propose (table + intended token names + brand-guide question) → **Rule 1: THE STOP IS HARD, turn ends at the proposal** → on approval create `brand` token set + tokens + one "Brand reference" board (logo via insert_image, swatches bound to the NEW tokens — raw hexes named as rejected-by-guard) → confirm; applying to existing shapes explicitly out of the task
- [x] Registered in the Setup category next to vibes; skill tests (3) in `workspace_user_skills_test.cljs`: category/reactive/enabled, body served verbatim (no aikit preamble, stop rule + tool names present), listed by `catalog-manifest`
- [x] 836 tests 0 failures (all 5 new confirmed in output); clj-kondo 0/0; cljfmt clean; `main` build 0 warnings
- [ ] Live verify — DEFERRED to the post-merge testing pass: run the playbook on Claude (vision) against a real brand site; tokens land in a set; guard still blocks raw-hex fills; the agent stops at the approval gate
- [x] Human approval: Santi pre-approved phase-by-phase commits for this plan (2026-07-16)
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Plan completion: Completion Summary written in README.md
- [x] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — `import-brand-body` + Setup catalog entry
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `get_page_meta` spec + handler + dispatch, error-message arity
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — tool tests
- `frontend/test/frontend_tests/data/workspace_user_skills_test.cljs` — skill tests

## Notes

- Composition held: no new machinery beyond the thin metadata tool — the
  primitives from phases 01–07 covered the whole playbook.
- CSS palette extraction stayed dropped (screenshot + vision + theme-color is
  ~0 code for 90%).
- Follow-ups seeded for the backlog: keyed stock providers (Unsplash/Pexels via
  instance config), Anthropic `mcp_servers` passthrough as the BYO-integration
  door, self-hosted Iconify for offline instances.
