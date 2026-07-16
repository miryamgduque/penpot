# Phase 06 — Scout tool (`explore_design`)

**Status:** todo

A new agent tool that delegates exploratory reading to the phase-05 side context:
the main model calls `explore_design` with a question ("map every screen and its
component usage", "which shapes violate token-only-colors and why"), a Haiku side
turn sweeps the file with the read-only tools, and ONE digest tool result enters
the main history. Today that same sweep is 3–6 main-model rounds each dragging up
to 20k chars into the expensive, forever-re-sent history.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Phase 05 landed (this is its consumer)
- [ ] Re-read `tool-specs` + `execute-tool` in `agent_tools.cljs` and how
      `run-tool` in `agent.cljs` consumes an executor's observable — the scout
      executor is the first ASYNC-long executor; confirm nothing assumes
      near-instant completion (UI shows a running chip meanwhile?)

## Checklist

- [ ] Tests: `execute_tool "explore_design"` runs a side turn with the read-only
      allowlist and returns `{:digest … :usage …}` shaped like any tool result;
      digest bounded (~6k chars — well under the 20k refusal); side-turn failure
      returns a readable tool ERROR (the main model should fall back to reading
      directly, and the description tells it so)
- [ ] Tool spec: description states WHEN to use it ("broad or multi-step
      reading — maps, inventories, audits across many shapes") and when NOT to
      (single-shape lookups: call read_design directly); input = `question`,
      optional `page-id`/`root-id` to scope the sweep
- [ ] Scout system prompt: read-only role, answer-the-question-only, name shapes
      by name+id so the main model can act on the digest without re-reading
- [ ] Executor in `agent_tools.cljs` calls `agent/run-side-turn` — watch the
      require direction (tools ns must not create a cycle with agent ns; if it
      does, register the executor from `agent.cljs` side like the ask_user
      resolver-atom pattern)
- [ ] One line in `inner-knowledge`'s tool notes: prefer `explore_design` for
      broad reading (keep it to a sentence — that section is always-on tokens)
- [ ] Transcript chip renders the scout like any tool call (name + collapsible
      digest); its usage lands in the meter via phase 05
- [ ] Lint + typecheck pass
- [ ] Preview review with MCP tools (drive `at/execute_tool("explore_design", …)`
      from the console per the established pattern — no LLM needed)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — tool spec + executor
- `frontend/src/app/main/data/workspace/agent.cljs` — side-turn hookup (or resolver registration)
- `frontend/src/app/main/data/workspace/agent_skills.cljs` — one-line inner-knowledge note
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — executor tests

## Notes

- The scout's Haiku spend is real but ~5–25× cheaper per read than the same reads
  in the main context — and it is paid ONCE, not re-sent every round afterward.
- Risk: the main model over-delegating trivial lookups (a scout call costs a
  side turn). The description's when-NOT-to-use line is the mitigation; watch it
  in live verification.
