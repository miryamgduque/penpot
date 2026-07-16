# Phase 02 — interview emits DESIGN.md

**Status:** done

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions) — tree carried only this plan's own edits
- [x] Check if any gaps have been filled by other work since plan creation — none
- [x] Phase 01 merged (parse/validate available) — 14031cae08
- [x] Read `agent_skills.cljs` `vibes-body` and `agent_tools.cljs` `set_design_doc` (spec :184, impl :2771) to confirm current shape

## Checklist

- [x] Tests: 5 new `dd/doc-problem` tests in design_md_test.cljs — valid full-spec doc accepted, legacy plain-markdown accepted, broken YAML rejected naming YAML, dangling `{colors.missing}` rejected naming the ref, cap still enforced at 6000. (Deviation from plan: validation is tested at `doc-problem` — the tool's actual validation seam — rather than through `execute-tool`, which needs `:current-file-id` in the global store.)
- [x] Rewrite `vibes-body` step 3: frontmatter (version/name/description/colors/typography/rounded/spacing/components with `{token.ref}` warning) + canonical body sections; Voice & copy folded into Overview; interview questions and `__decide__`/reference-image handling untouched (image guidance moved into the colors bullet); doc budget 3500 → 5000
- [x] `set_design_doc` tool description names the format, the sections, the validation, and the 6000 cap
- [x] Validation wired in `dd/doc-problem` (which the tool impl already consults — no impl change needed): parse `:error` first, then joined `problems`
- [x] `max-doc-chars` 4000 → 6000 with a why-comment
- [x] Extras: system-prompt heading now "Project vibes (DESIGN.md)" + a token-source-of-truth sentence when frontmatter is present; catalog blurb/what and skill intro line updated to DESIGN.md
- [x] Lint + typecheck + tests pass — kondo 0/0, cljfmt clean, 788 tests / 2570 assertions, 0 failures
- [x] Human approval received
- [x] Committed: 3b4e23a6d6 `:sparkles: Vibes interview writes a DESIGN.md`

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — phase 03 proceeds as planned; live interview verify still owed (needs user key)

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — vibes-body rewrite
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — tool description + validation in impl
- `frontend/src/app/main/data/workspace/design_doc.cljs` — cap 4000 → 6000
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — validation tests

## Notes

- The system prompt keeps inlining the doc WHOLE (YAML included) — the tokens
  are exactly what the agent should design with; no separate formatting pass.
- Known model behavior (07-15 finding): haiku ignored the vibes skill's
  stop-at-doc rule. The format change doesn't fix that; demo on Opus/Sonnet.
- Live-verify needs a devenv + user's Anthropic key: run the interview, check
  the saved doc parses and the tokens are concrete. Cheap enough on Haiku…
  but see above; use Sonnet.
