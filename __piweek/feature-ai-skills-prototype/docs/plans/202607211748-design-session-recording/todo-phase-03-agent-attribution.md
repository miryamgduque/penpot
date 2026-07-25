# Phase 03 — Agent attribution

**Status:** todo

Distinguish "the agent did this, on model X" from "a human did this". The agent
has no mutation funnel — ~22 write sites across the tool families using nine
different write paths — so this is done with an ambient marker one level up,
not by touching every tool.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `frontend/src/app/main/data/workspace/agent_tools.cljs:1041` (`execute-tool`) and `agent.cljs:854` (`run-tool`) — confirm both still wrap every tool invocation
- [ ] Confirm `settings {:provider :model}` is still a closure-local in `run-turn` (`agent.cljs:847`) and reaches `stream-round` (`:805`)
- [ ] Enumerate the side-turn entry points that mutate on a DIFFERENT model: `run-side-turn` (`agent.cljs:1130`), `tick-settings` (`ai_panel.cljs:382`), `fix-settings` (`ai_panel.cljs:558`)
- [ ] Confirm Phase 02 landed — this phase writes into the same commit map

## Checklist

- [ ] Write tests first
  - [ ] a commit emitted inside `execute-tool` is attributed `:who :agent` with the acting provider/model
  - [ ] a commit emitted outside any tool is attributed `:who :user`
  - [ ] the marker is cleared on the error path (a throwing tool must not leave later user edits labelled as agent)
  - [ ] async settling AFTER a tool returns (e.g. the 220ms geometry readback) is still attributed correctly, or is explicitly documented as out of scope
  - [ ] a side-turn mutation records the side-turn's model, not the panel selection
- [ ] Add the ambient marker (atom holding `{:who :agent :provider :model :tool :call-id}`) set/cleared around the `execute-tool` call
- [ ] Read the marker where the commit map is built and stamp `:who`/`:provider`/`:model`
- [ ] Thread `settings` from `run-turn` down to the marker (it is currently invisible below `execute-tool`)
- [ ] Ensure side turns set the marker with THEIR model
- [ ] Full suite green
- [ ] Lint + format
- [ ] Live check: run one real agent turn and one manual edit, confirm the timeline separates them
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — set/clear the marker around dispatch
- `frontend/src/app/main/data/workspace/agent.cljs` — thread `settings` into the marker; cover side turns
- `frontend/src/app/main/data/changes.cljs` — read the marker when building the commit map
- `frontend/test/frontend_tests/data/session_events_test.cljs` — attribution assertions

## Notes

**The async tail is the hard part and must not be hand-waved.** Several tools
emit writes that settle *after* the tool's observable completes — the
`geometry-settle-ms` 220ms readback in `modify_shape` is the known case, and
`apply_tokens` was documented as landing ~100ms later. A naive
set-marker/clear-marker around a synchronous call will mis-attribute those to
whoever edits next. Options, decide during the phase: keep the marker alive for
a grace window keyed by tool-call id; or attribute by comparing against the
tool's own returned shape ids; or explicitly accept and document the gap. Pick
one deliberately — do not discover it in production.

An atom is the pragmatic choice over a dynamic var: CLJS `binding` does not
survive an async boundary, and every one of these write paths is async.

"The agent" is not one model. A recording that flattens panel turns, the
observer tick, and auto-fix into a single `:agent` actor will produce critique
that blames the wrong model. Record the acting model per event.
