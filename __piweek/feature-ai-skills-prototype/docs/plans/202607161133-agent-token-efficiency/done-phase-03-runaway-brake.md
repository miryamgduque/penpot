# Phase 03 — Runaway brake (pause-and-ask checkpoint)

**Status:** done (tests written, execution + preview review deferred to the end-of-worktree verification pass)

A mid-turn checkpoint: when a turn crosses **N rounds (12)** or **~$X estimated
spend this turn ($1.00)**, the loop stops cleanly and the panel renders a
"Still working — N tool rounds and ~$X so far. Continue?" row with
Continue / Stop. Continuing resumes the loop with the counters reset; stopping
seals the turn like a user cancel. Motivated by the 2026-07-15 incident: Haiku
ignored a skill's stop rule and spent ~$4 building an unrequested landing page.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `run-turn`'s `step`/`tool-round` loop and `send-message`'s event
      consumption (`:turn-history`, cancel path) — the checkpoint must reuse the
      existing clean-stop machinery
- [ ] Check how `ask_user`'s pending-form UI mounts — the checkpoint row should
      NOT reuse ask_user (it is a tool the model calls; this is a harness event),
      but its rendering pattern is the reference

## Checklist

- [x] Tests: pure `checkpoint-due?` — not due early, due at the round cap, due
      on spend alone (priced model), round 0 never fires (resume safety),
      unpriced model brakes on rounds only. (The rx loop wiring follows the
      file's convention of testing pure pieces — the loop itself was and stays
      untested at unit level; live verification covers it.)
- [x] Implement in `agent.cljs`: `checkpoint-rounds` 12 / `checkpoint-usd` 1.0;
      `run-turn` gained a `seed` arity and threads segment `spent` through
      `step`/`tool-round`; checkpoint emitted at the top of `step` (history
      ends in tool-results = resumable as-is); thresholds run on the fresh
      segment, the event reports seed+segment cumulative figures
- [x] Implement in `data/workspace/ai_panel.cljs`: send-message's pipeline
      extracted into shared `turn-stream`; `set-checkpoint` state event;
      `continue-turn` (no synthetic user message) + `dismiss-checkpoint`;
      `clear-chat` also drops the checkpoint; a new send supersedes a pending
      checkpoint (history already stored)
- [x] Implement in UI: `refs/ai-panel-checkpoint`; transcript-tail row with
      rounds + ~$ figures and Continue / Stop here buttons (scroll-follow deps
      updated); send guard, Fix-it-now queue AND its drain effect all treat a
      pending checkpoint like a running turn
- [x] Unpriced models fall back to the round threshold alone
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

- `frontend/src/app/main/data/workspace/agent.cljs` — checkpoint emission + resume seed
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — `continue-turn`, checkpoint state
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — checkpoint transcript row
- `frontend/test/frontend_tests/data/agent_test.cljs` — threshold + resume tests

## Notes

- `max-rounds 32` stays as the hard backstop behind the soft checkpoint.
- The checkpoint history ends in `:tool-results`, so resuming is just calling the
  loop again — no cancel-history synthesis needed. That is why the emission point
  is the top of `step`, not mid-round.
- Fits the agent's own governance ("apply-with-review"): the harness enforces on
  itself what `inner-knowledge` asks of the model.
