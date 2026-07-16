# Phase 05 — Side-context runner

**Status:** todo

Generalize `detect-round` into `run-side-turn`: a buffered, bounded, tool-capable
mini-loop on a cheap model, whose entire conversation lives and dies outside the
main history. This is the infrastructure phase for subagent isolation (phase 06
puts a tool in front of it). Anthropic-only, like `detect-round`, and for the same
reason: we control which model runs it.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `detect-round` and `run-turn` — the side runner is the buffered
      third sibling: `run-turn`'s loop shape on `detect-round`'s transport
- [ ] Confirm which tools are safe to expose: read-only set only
      (`read_design`, `find_shapes`, `audit_file`, `get_design_skills`)

## Checklist

- [ ] Tests: a scripted tool round loops (tool_use → execute → tool_result →
      next round) and returns the final text + summed usage; round cap (~8)
      enforced; a write-tool name in the allowlist position is rejected at
      construction, not at call time; provider error surfaces as stream error
- [ ] Implement `run-side-turn` in `agent.cljs` (or a new `agent_side.cljs` if it
      crowds the ns): takes `{:model :system :user-text :tools :max-rounds}`,
      returns one `{:text :usage}` observable; buffered `:ai-agent-round`
      transport (nobody watches a side context type — same rationale as the tick)
- [ ] Reuse the existing buffered Anthropic decode (extract the shared piece from
      `detect-round` rather than duplicating it); rewrite `detect-round` as a
      one-round `run-side-turn` call — net deletion
- [ ] Side-turn usage merges into the panel's spend meter (flagged so the meter
      could later break it out; for now it just must not be invisible spend)
- [ ] Tool results inside the side context get the SAME `result->content` 20k
      bound; no images in the side context (text digests only, keeps it cheap)
- [ ] Lint + typecheck pass
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:recycle:` — it refactors detect-round)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — `run-side-turn`, shared buffered decode, `detect-round` rewrite
- `frontend/test/frontend_tests/data/agent_test.cljs` — loop/cap/allowlist tests

## Notes

- Cache marker: side turns are one-shot-ish (≤8 rounds, small system) — put the
  system breakpoint on anyway (free) but skip the message breakpoint (the history
  never gets big enough to matter).
- This is where the plan's architecture risk concentrates; phase 06 is thin by
  design so this one can be reviewed as pure plumbing with tests.
