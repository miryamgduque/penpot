# Phase 08 — brand extraction playbook

**Status:** todo

## Goal

"Import the brand from example.com" as a **skill/playbook composing the previous
phases** — no new fetch machinery. The agent: reads phase-04 metadata (favicon,
og-image, theme-color, title) → screenshots the page (07) → proposes color tokens
(existing `create_token`) → inserts logo imagery (01) → applies fonts if
identifiable (03). Output gated by the existing token-only-colors guard.

## Before Start

- [ ] Verify plan is still valid; phases 01, 04, 07 are merged (03 optional-nice)
- [ ] Decide skill placement with the user: skills-core builtins vs `backend/resources/app/design-skills-seed.json` (app-scope DB row) vs a native catalog entry — check what the catalog looks like post-US#14 (`:reactive on-call|observer` fields)
- [ ] Re-read how a skill body reaches the agent (`get_design_skills` router pattern; the `load` strategy decision may have landed by now — adapt)
- [ ] Check whether `fetch_page` (05) exposes the phase-04 `:meta` map in its tool result, or whether metadata needs a thin `get_page_meta` variant that skips the side-turn (metadata is structured, not free text — it does NOT need the digest containment and shouldn't pay a Haiku call)

## Checklist

- [ ] If needed: `get_page_meta {url}` tool — phase-04 RPC, return only the structured `:meta` + `:title` (no page text, no side turn); tests + dispatch
- [ ] Write the `import-brand` skill (kind: skill, advisory): stepwise playbook — fetch meta → screenshot → extract 3-6 dominant colors FROM THE SCREENSHOT (vision) cross-checked against `theme-color` → propose token set `brand` (primary/accent/surface/text) via `create_token`/`create_token_set` → insert favicon/og-image via `insert_image` on a moodboard frame → STOP and present the palette for approval before applying tokens to any existing shape (the vibes-skill lesson: models ignore weak stop rules — make it a numbered hard rule)
- [ ] Seed/register the skill in the chosen scope
- [ ] Lint/format whatever was touched
- [ ] Live verify on Claude (vision): run the playbook against a real brand site; tokens land in a set, guard still blocks raw-hex fills, agent stops at the approval gate
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Plan completion: write the Completion Summary in README.md, update `ai-skills`/branch docs, move folder to `completed/`
- [ ] Note any follow-up items or discoveries below

## Files

- skill definition (placement decided at Before Start: skills-core builtins / seed json / catalog)
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — only if `get_page_meta` is needed
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — only if `get_page_meta` is needed

## Notes

- Deliberately last: it's the composition proof that the six primitives are the
  right ones. If it needs new machinery, that's a finding about the primitives.
- Color extraction from CSS was considered and dropped — fetching/parsing
  stylesheets is a rabbit hole; the screenshot + vision + theme-color meta gets
  90% for ~0 code.
- Follow-ups seeded here for the backlog: keyed stock providers (Unsplash/Pexels
  via instance config), Anthropic `mcp_servers` passthrough as the BYO-integration
  door, self-hosted Iconify for offline instances.
