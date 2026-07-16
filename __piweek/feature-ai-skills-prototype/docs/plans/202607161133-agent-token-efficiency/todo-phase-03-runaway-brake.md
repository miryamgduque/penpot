# Phase 03 — Runaway brake (pause-and-ask checkpoint)

**Status:** todo

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

- [ ] Tests: loop emits `{:kind :checkpoint :rounds n :usage u :history …}` and
      completes (no further rounds) when the round threshold is crossed; spend
      threshold likewise (usage accumulated across the turn's rounds ×
      `estimate-cost-usd`); a turn under both thresholds never checkpoints
- [ ] Tests: resuming from a checkpoint history (ends in `:tool-results`) runs
      more rounds; stopping seals via the same path as cancel (`cancel-history`
      NOT needed — history at a checkpoint is already closed)
- [ ] Implement in `agent.cljs`: thresholds as defs; `run-turn` gains the
      checkpoint emission at the top of `step` (history at that point ends in
      tool-results = valid to resume); accept a `:start-round`/`:spent` seed so a
      resumed turn carries its counters forward for the NEXT checkpoint
- [ ] Implement in `data/workspace/ai_panel.cljs`: `continue-turn` event that
      re-invokes `run-turn` from the stored checkpoint history (no synthetic user
      message — the wire history must not grow); checkpoint state under
      `[:ai-panel file-id :checkpoint]`
- [ ] Implement in `ui/workspace/ai_panel.cljs`: transcript row with round/spend
      figures + Continue / Stop buttons; composer disabled while a checkpoint is
      pending (same guard as a running turn)
- [ ] Unpriced models (no `estimate-cost-usd`): fall back to the round threshold
      alone
- [ ] Lint + typecheck pass; SCSS via `build-app-assets.js` if styles added
- [ ] Preview review with MCP tools (checkpoint row, continue, stop)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:construction:` or `:zap:`)

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
