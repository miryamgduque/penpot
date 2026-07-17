# Phase 03 — ask_user question policy

**Status:** todo

Adopts the codex prompt's question rules for our existing elicitation tool: do
all non-blocked work first; ask exactly ONE targeted question; include your
recommended default; say what changes based on the answer. The tool shipped
(slash-commands/vibes plan, 2026-07-15) with no usage doctrine — the model
currently has no guidance on when a mid-build question is worth the
interruption vs. when to pick a reasonable default and note it.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Read the current `ask_user` tool spec in
      `frontend/src/app/main/data/workspace/agent_tools.cljs` (description text
      + input schema) and any skill bodies that reference it (vibes interview
      does — its adaptive-interview flow must NOT be constrained by the new
      one-question rule)

## Checklist

- [ ] Write/update tests: pin the new description/doctrine text (same
      prompt-pin style as phase 02)
- [ ] Decide placement with a bias to the TOOL DESCRIPTION, not
      inner-knowledge: the policy only matters when the tool is in hand, and
      tool descriptions ride the cached prefix anyway. Only a single
      inner-knowledge line if the description alone proves too weak.
- [ ] Draft policy into the `ask_user` description: "Ask only when blocked on
      something you cannot resolve from the file, the foundations or a
      reasonable default. First finish all work that doesn't depend on the
      answer. Ask ONE question, offer your recommended option first, and say
      what changes based on the choice. Never ask permission-style questions
      ('should I proceed?') — governance already defines when to pause."
- [ ] Carve-out for interviews: skills that run a structured interview (vibes)
      legitimately ask several questions — phrase the policy per-question
      ("one question per call, each self-sufficient") so it composes with the
      interview flow instead of fighting it
- [ ] Lint + typecheck pass (kondo; `compile test` + run; `compile main`)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (e.g. `:memo: ask_user gets a question
      policy: non-blocked work first, one question, recommended default`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `ask_user` tool
  description
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — pin

## Notes

- Interaction with governance: apply-with-review already mandates pausing after
  generative work — this policy governs questions BEFORE/DURING work. Make sure
  the two don't read as contradictory ("never ask should-I-proceed" vs.
  checkpoint pauses: the checkpoint is a REPORT with direction options, not a
  permission question — spell that distinction if needed).
