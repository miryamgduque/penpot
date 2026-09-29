# Phase 08 — Todo/plan tool: spec + go/no-go (GATED on phase 06)

**Status:** todo

opencode invests heavily in TodoWrite (plan visibly, mark items done as you
go); Kimi leans on subagent delegation instead. For us the pitch is: a pinned
checklist in the panel = progress visibility **without interruption** — exactly
the trade Santi asked for when the checkpoint and round cap were disabled
(both booleans off in `agent.cljs`). The counter-argument: it costs rounds and
prompt space, and the composition tools may already have collapsed the
meandering it would fix. This phase DECIDES; building the tool would be its
own plan.

## Before Start

- [ ] Phase 06 numbers exist (calls/round, rounds-per-deliverable, $) — this
      phase must not start without them
- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Skim phase 07's survey if done — Cline's focus-chain and OpenHands' task
      tracking are direct references for the spec

## Checklist

- [ ] Assess the evidence: does a post-composition-tools build still meander
      (rounds ≫ deliverable complexity, repeated re-reads, redundant work)?
      Cite the phase 06 session's transcript, not vibes
- [ ] Write the spec REGARDLESS of verdict (cheap now, ready if/when):
      - `set_plan` / `check_item` tool pair (or one tool with ops) writing to
        conversation-scoped state, NOT plugin-data (a plan is chat state, dies
        with the conversation; persists via the existing chat-persistence rows)
      - pinned checklist UI above the transcript (collapsed by default,
        item count + current item in the header row)
      - doctrine: plan only for multi-section builds (~>6 expected rounds);
        never for questions/single edits; update items as you go, not in batch
      - interaction with compaction: the plan block survives compaction
        verbatim (it IS the current-task anchor Kimi's priorities put first —
        synergy with phase 01)
      - cost estimate: 2 tool specs ≈ +400 prefix tokens; ~1 extra call per
        plan update
- [ ] Go/no-go with Santi, evidence attached
- [ ] If GO: spin up a new plan folder for the build (out of scope here);
      if NO-GO: record why + the re-evaluation trigger (e.g. "revisit if a
      session again exceeds 60 rounds for a one-screen build")
- [ ] Committed with a gitmoji commit (`:memo: Todo-tool spec and go/no-go
      decision`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Write the plan-level Completion Summary in README.md (this is the last
      phase), set plan status, move folder to `completed/`
- [ ] Note any follow-up items or discoveries below

## Files

- this plan folder — spec + decision record (no product code in this phase)

## Notes

- Explicitly a DECISION phase: shipping any tool code here would violate the
  plan's own scope. The deliverable is a document and a recorded choice.
- If phase 06 closed early because calls/round was already healthy, that same
  evidence likely argues NO-GO here — but rounds-per-deliverable, not
  calls/round, is the number this decision actually turns on.
