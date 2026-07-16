# Phase 06 — Scout tool (`explore_design`)

**Status:** done (tests written, execution + console drive deferred to the end-of-worktree verification pass)

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

- [x] Tests (with a stubbed runner via `register-side-turn-runner!`): missing
      question errors naming the fix; missing runner says to read directly;
      digest returned; ONLY the read-only tool set + the verbatim question
      reach the runner; digest bounded at 6k with a truncation marker; an
      EMPTY digest is an error, not "nothing found"
- [x] Tool spec: when to use (maps/inventories/cross-shape sweeps) and when
      NOT to (single lookups → read_design/find_shapes directly; cannot make
      changes; on error, read directly). Input = required `question` only —
      scoping rides in the question text, the scout scopes its own reads
      (page-id/root-id params deferred until a real need shows)
- [x] Scout system prompt: read-only role, exhaustive-within-scope, digest
      actionable WITHOUT re-reading (exact names + ids), <5000 chars, say
      what's missing rather than guess
- [x] Executor lives in `agent_tools.cljs`; the runner arrives via the
      ask_user-style module atom (`register-side-turn-runner!`, called by
      agent.cljs at load) — the require direction did cycle, as anticipated
- [x] One sentence in `inner-knowledge`'s tool notes
- [x] Scout usage → meter via a local `meter-scout-usage` event (requiring
      data.workspace.ai-panel would cycle too); transcript chip needs nothing
      — tool events already render generically by name
- [ ] Lint + typecheck — DEFERRED to end-of-worktree verification
- [ ] Console drive (`at.execute_tool("explore_design", …)`) — DEFERRED (devenv)
- [ ] Human approval received — DEFERRED to worktree merge review
- [x] Committed with a gitmoji commit (`:sparkles:`)

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
