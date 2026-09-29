# Phase 02 — History hygiene (microcompaction + token-budget trim)

**Status:** done (tests written, execution deferred to the end-of-worktree verification pass)

Two size-based bounds on the canonical history, both applied at the **turn
boundary** (in `send-message`, before round 0), where a history rewrite costs one
cache re-write instead of one per round:

1. **Stale tool-result stubbing** — port the TS app's `pruneStaleToolResults`: any
   tool result whose content exceeds ~400 chars and which is older than the last
   ~8 messages has its `:content` replaced by a stub
   (`"[result cleared to save space — call the tool again if needed]"`). Structure
   (`:id`, pairing with its tool call) stays intact so neither provider 400s.
2. **Token-budget trim** — `trim-history` currently cuts at 40 *messages*; a
   message can be 20k chars. Add a char budget (~60k chars ≈ 15k tokens) with the
   same cut-at-plain-user-message rule, applied after stubbing (stubbing usually
   makes the trim unnecessary).

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Phase 01 landed (interplay: this rewrites the cached prefix once per turn —
      still correct without phase 01, but measure after it)
- [ ] Re-read `send-message` in `data/workspace/ai_panel.cljs` and `trim-history`
      in `agent.cljs`

## Checklist

- [x] Tests: stub replaces content but preserves `:id`/`:error?`; protected tail
      (last 8 messages) untouched; short results untouched; wire invariant
      (tool_use/tool_result pairing) holds after stubbing
- [x] Tests: char-budget trim cuts only at a plain `:user` message; a single
      oversized turn is kept whole; small history returns identical
- [x] Implement `stub-stale-tool-results` in `agent.cljs` (pure, thresholds as
      defs: >400 chars, older than last 8 messages)
- [x] Extend `trim-history` with `max-history-chars` 60k (~15k tokens); message
      cap stays as backstop; images deliberately NOT counted (`message-chars`
      docstring says why — the image pruner owns that bound)
- [x] Wire both into `send-message` on `prior` before the turn's history builds
- [x] Stored history keeps the stubbed form automatically: the stubbed `prior`
      threads through the turn and `:done`/cancel store what it grew into
- [ ] Lint + typecheck pass — DEFERRED to end-of-worktree verification
- [ ] Human approval received — DEFERRED to worktree merge review
- [x] Committed with a gitmoji commit (`:zap:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — `stub-stale-tool-results`, budgeted `trim-history`
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — call site in `send-message`
- `frontend/test/frontend_tests/data/agent_test.cljs` — hygiene tests

## Notes

- Thresholds (400 chars / 8 messages / 60k chars) are the TS app's proven values
  plus one new budget; keep them tunable defs, not literals.
- Why turn-boundary and not per-round: mid-turn the model may still be reading a
  result it just requested; and per-round rewrites would defeat phase 01's cache.
