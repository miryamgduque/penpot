# Phase 05 — Side-context runner

**Status:** done (tests written, execution deferred to the end-of-worktree verification pass)

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

- [x] Tests: write tool in the requested set throws at construction; the full
      read-only set constructs; `decode-buffered-round` parses text (blocks
      concatenated), tool_use calls and usage, and throws the provider's own
      message on non-200. (The rx loop itself follows the file's convention —
      pure/constructible pieces unit-tested, the loop exercised live.)
- [x] Implement `run-side-turn` in `agent.cljs`:
      `{:model :system :user-text :tools :max-rounds :cache? :max-tokens}` →
      one `{:text :usage}` observable; buffered `:ai-agent-round` transport;
      canonical mini-history re-using `encode-anthropic`
- [x] Extracted `decode-buffered-round` from `detect-round`; `detect-round` is
      now a one-round `:cache? false` `run-side-turn` call — net deletion
- [x] Allowlist enforced TWICE: at construction (throw) and per call (a model
      naming an un-offered tool gets an error result, not an execution) —
      writes from an unwatched context would bypass apply-with-review
- [x] Side results ride `result->content` (same 20k refusal bound); images
      dropped (text digests only)
- [x] System cache marker on by default (pays across a side turn's own
      rounds); message breakpoint skipped — side histories stay small
- [ ] Usage → spend meter: wired in phase 06 at the call site (the runner
      returns summed usage; detect-round's tick path already metered)
- [ ] Lint + typecheck pass — DEFERRED to end-of-worktree verification
- [ ] Human approval received — DEFERRED to worktree merge review
- [x] Committed with a gitmoji commit (`:recycle:`)

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
