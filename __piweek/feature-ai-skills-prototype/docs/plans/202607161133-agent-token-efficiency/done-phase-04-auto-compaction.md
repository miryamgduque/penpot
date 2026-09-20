# Phase 04 — Auto-compaction (summarize and restart)

**Status:** done (tests written, execution + preview review deferred to the end-of-worktree verification pass)

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

- [x] Tests (pure halves): `compact-due?` threshold both sides;
      `compacted-history` = summary message (`:compacted? true`, framed as a
      replacement) + last turn verbatim with pairs intact; a lone-turn history
      returned as-is; `compaction-transcript` strips images and keeps both
      sides' words. The summarizer round follows detect-round's pattern
      (exercised live, like detect-round itself); failure-degrades-to-
      uncompacted lives in send-message's rx/catch
- [x] Summary prompt: working-memory framing, Task/Done-so-far(exact
      names+ids)/Decisions/Open sections, never-invent-names, <3000 chars
- [x] `compact-threshold-chars` 100k; `max-history-chars` RAISED 60k→150k so
      the defense order is stubbing → compaction → trim backstop (the plan's
      phase-02 note anticipated this)
- [x] Wired into `send-message`: compact-due? on the hygiened prior → one
      buffered Haiku round → turn runs on the rewrite; rx/catch degrades to
      the uncompacted history; compaction usage feeds the spend meter
- [x] Haiku hardcoded (`compact-model`), Anthropic-only like detect-round —
      no Anthropic key means the catch path, and the trim still bounds
- [x] Transcript note: role "note" messages render as a quiet centered seam
      (`message-note`), plain text, non-interactive
- [x] Stored history keeps the compacted form (turn-stream seeds from it, so
      :done/cancel store what grew from the rewrite)
- [ ] Lint + typecheck + `build-app-assets.js` — DEFERRED to end-of-worktree verification
- [ ] Preview review — DEFERRED (needs devenv compile)
- [ ] Human approval received — DEFERRED to worktree merge review
- [x] Committed with a gitmoji commit (`:zap:`)

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
