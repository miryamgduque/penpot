# Phase 04 — Auto-compaction (summarize and restart)

**Status:** todo

When the canonical history at turn start exceeds a threshold (~100k chars ≈ 25k
tokens — above the phase-02 trim budget, so compaction replaces the lossy trim
rather than racing it), run ONE cheap buffered round (Haiku, reusing the
`detect-round` machinery) that writes a structured summary — what was asked, what
was built/changed (shape names!), decisions made, what is in flight — and replace
the wire history with that summary plus the last user turn. The visible transcript
is untouched; a subtle "✦ Conversation compacted to save tokens" row marks the
seam.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Phases 01–02 landed (compaction is the backstop BEHIND hygiene; measure what
      actually reaches the threshold after them)
- [ ] Re-read `detect-round` (buffered, tool-less — exactly the shape compaction
      needs) and `send-message`'s seeding of the history atom

## Checklist

- [ ] Tests: below threshold → history untouched, no extra request; above →
      history becomes `[summary-user-message, …last-turn-tail]`, tool pairs in the
      tail intact; summarizer failure → turn proceeds UNcompacted (a background
      optimization must never block the user's turn)
- [ ] Write the summary prompt: structured sections (task, done-so-far with shape
      names/ids, decisions, open items); cap the summary (~3k chars); instruct
      "you are writing your own working memory, not prose for a human"
- [ ] Implement `compact-history` in `agent.cljs`: threshold def; summary rides a
      `:user` message flagged `:compacted? true` (canonical form stays
      provider-agnostic; the flag lets the UI and future code recognize the seam)
- [ ] Wire into `send-message`: check → (maybe) compact via one buffered Haiku
      round → then seed the turn; the turn's SSE stream starts after compaction
      resolves (rx/concat, cancel-safe)
- [ ] Compaction model: hardcode Haiku (same rationale as the watcher tick —
      ambient work never bills like design work); its usage feeds the same spend
      meter
- [ ] Transcript note row in `ui/workspace/ai_panel.cljs` (subtle, non-interactive)
- [ ] Stored history (`[:ai-panel … :history]`) keeps the compacted form —
      reloads and later turns inherit the savings
- [ ] Lint + typecheck pass; SCSS via `build-app-assets.js` if styles added
- [ ] Preview review with MCP tools (note row renders; agent still knows what it
      built pre-compaction when asked)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:zap:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — `compact-history`, summary prompt
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — pre-turn compaction step
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — compaction note row
- `frontend/test/frontend_tests/data/agent_test.cljs` — threshold/replacement/failure tests

## Notes

- Deliberately LOSSY — that is the point and the risk. Turn-boundary-only, good
  summary prompt, and the phase-02 hygiene doing the routine work keep compaction
  rare (long sessions only).
- Phase-02 trim vs compaction: once this lands, raise the trim budget so the
  order is hygiene (cheap, lossless-ish) → compaction (rare, lossy, smart) →
  40-message cliff (vestigial backstop).
