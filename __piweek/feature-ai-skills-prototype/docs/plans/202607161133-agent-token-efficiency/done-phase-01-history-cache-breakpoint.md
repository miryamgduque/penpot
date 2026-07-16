# Phase 01 — History cache breakpoint

**Status:** done (tests written, execution deferred to the end-of-worktree verification pass — user direction 2026-07-16)

Add an ephemeral `cache_control` marker to the **last content block of the last
message** in each Anthropic-dialect request, alongside the existing marker on the
system block. Each round then reads all prior history at 0.1× and pays the 1.25×
write only on the new tail — the single biggest lever in the plan (~8–10× on the
history term of a multi-round turn).

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Re-read `build-round-body` / `encode-anthropic` in `agent.cljs` — the marker
      placement depends on their current shape

## Checklist

- [x] Tests in `agent_test.cljs`: marker lands on the last block of the last
      message, exactly one marker across the history, earlier messages untouched;
      string content promoted to blocks only for the marked message
- [x] Test: a tool-results message as the last message carries the marker on its
      last `tool_result` block; sibling results unmarked
- [x] Tests: empty-string content never mints an empty text block (unmarked
      beats a 400); empty history no-op; image tail is markable
- [x] Implement `mark-history-breakpoint` in `agent.cljs`; wired in
      `build-round-body`'s Anthropic branch (OpenAI dialect untouched — those
      providers cache long prefixes automatically)
- [x] Comment documenting the invalidation interplay (`trim-history` /
      `prune-history-images` rewrite the head → one re-write when they fire)
- [x] `detect-round` (watcher tick) stays UNcached on purpose — comment added
- [ ] Lint + typecheck pass — DEFERRED to end-of-worktree verification
- [ ] Human approval received — DEFERRED to worktree merge review
- [x] Committed with a gitmoji commit (`:zap:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — marker in the encoder/body builder
- `frontend/test/frontend_tests/data/agent_test.cljs` — breakpoint placement tests

## Notes

- Anthropic allows 4 breakpoints; we use 2. A third (e.g. before the volatile last
  user turn) is possible later but adds little — the incremental prefix already
  covers it.
- Verification instrument: the spend meter's cached %. Today it FALLS as a turn
  progresses; after this phase it should climb above 90% by round 3 of any turn.
