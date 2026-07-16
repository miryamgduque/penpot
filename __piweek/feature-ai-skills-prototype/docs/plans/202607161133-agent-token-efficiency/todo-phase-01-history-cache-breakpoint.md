# Phase 01 — History cache breakpoint

**Status:** todo

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

- [ ] Tests in `agent_test.cljs`: encoded Anthropic body has exactly TWO
      breakpoints (system + last block of last message); string-content messages
      get promoted to a block vector only where the marker lands; OpenAI dialect
      is untouched (its providers cache automatically, no marker exists)
- [ ] Test: a tool-results message as the last message carries the marker on its
      last `tool_result` block (that is the common case mid-turn)
- [ ] Implement in `encode-anthropic` / `build-round-body`: marker on the last
      block; promote a plain-string `:content` to `[{:type "text" …}]` only for
      that one message (strings are the fast path everywhere else)
- [ ] Do NOT mark image blocks (marker goes on the message's final block; if that
      is an image, mark it — Anthropic allows cache_control on image blocks — but
      note `prune-history-images` will rewrite that prefix when the image ages out;
      acceptable, document in a comment)
- [ ] Comment documenting the invalidation interplay: `trim-history` and
      `prune-history-images` rewrite the head and eat one cache re-write when they
      fire — rare, net-positive, deliberate
- [ ] `detect-round` (watcher tick) stays UNcached on purpose — ticks are sporadic
      relative to the 5-min TTL; note why in a comment
- [ ] Lint + typecheck pass (`pnpm run lint:clj` in devenv; shadow compile green)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:zap:`)

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
