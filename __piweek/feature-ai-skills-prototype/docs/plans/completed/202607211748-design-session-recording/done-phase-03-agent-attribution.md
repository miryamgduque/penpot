# Phase 03 — Agent attribution

**Status:** done (code); live agent-turn verification outstanding → Phase 04

Distinguish "the agent did this, on model X" from "a human did this". The agent
has no mutation funnel — ~22 write sites across the tool families using nine
different write paths — so this is done with an ambient marker one level up,
not by touching every tool.

## Before Start

- [x] Verify plan is still valid — Phase 02 landed at `7ff0ab286e` on
      `feature/ai-skills-prototype-session-recording`
- [x] Re-read `agent_tools.cljs:1041` (`execute-tool`) and `agent.cljs:853`
      (`run-tool`) — both confirmed to wrap every tool invocation. **`run-tool`
      is the better hook**: it is the only wrapper that also has `settings` in
      scope, so no threading is needed
- [x] Confirm `settings {:provider :model}` is a closure param of `run-turn`
      (`agent.cljs:851-852`) and therefore visible inside `run-tool`
- [x] Enumerate side-turn entry points — see the DRIFT note below, they turn out
      not to matter
- [x] Confirm Phase 02 landed — this phase writes into the same commit map

### DRIFT: side turns cannot mutate, so there is nothing to attribute

The phase planned to cover `run-side-turn`, `tick-settings` and `fix-settings`
as agent paths running on *different* models. They are not mutation paths:

- `run-side-turn` validates its tools against `side-readonly-tools` =
  `#{"read_design" "find_shapes" "audit_file" "get_design_skills"}` and **throws
  at construction** for anything else (`agent.cljs:1140-1145`). Side turns are
  read-only by construction, so the scout (`explore_design`), the semantic tick
  and `detect-round` cannot write to the file.
- `run-turn` has exactly **one** call site (`ai_panel.cljs:658`), so every agent
  mutation in the app flows through the single `run-tool` this phase hooks.
- `fix-settings` seeds a chat prompt that goes through the normal
  `send-message` → `turn-stream` → `run-turn` path, so it is already covered.

Two checklist items were dropped as a result: "a side-turn mutation records the
side-turn's model" and "ensure side turns set the marker with THEIR model". Both
described a situation the code makes impossible. The general warning still
stands and is worth keeping: *if* a future side turn is ever granted a mutating
tool, it must mark itself — and the `side-readonly-tools` guard is what currently
makes that impossible to get wrong by accident.

## Checklist

- [x] Write tests first — `test/frontend_tests/data/session_actor_test.cljs`
      (marker semantics) and new cases in `changes_provenance_test.cljs`
      (commit integration)
  - [x] a commit emitted inside a tool is attributed `:who :agent` with the
        acting provider/model
  - [x] a commit emitted outside any tool is attributed `:who :user`
  - [x] the marker is released on the error path (a throwing tool must not leave
        later user edits labelled as agent) — `end-agent-action!` is called from
        both `rx/map` and `rx/catch`
  - [x] async settling after a tool returns is still attributed correctly — the
        grace window, pinned by `the-marker-survives-the-tool-by-a-grace-window`
        and `the-grace-window-clears-the-known-async-tail`
  - [x] ~~a side-turn mutation records the side-turn's model~~ **DROPPED** — side
        turns are read-only by construction (see DRIFT)
  - [x] a leaked marker expires on its own (cancelled turn never runs cleanup)
  - [x] overlapping tools: a finished tool must not start the grace countdown on
        a still-running one (generation guard)
  - [x] **an agent commit keeps the operating user's `profile-id`** — the hazard
        flagged when closing Phase 02
  - [x] a remote commit is never marked as our agent, even mid-tool
- [x] Add the ambient marker — `app.main.data.session-actor`
- [x] Read the marker where the commit map is built — `sa/stamp` in
      `commit-changes` (local path only)
- [x] Thread `settings` into the marker — not needed; `run-tool` already closes
      over it
- [x] ~~Ensure side turns set the marker~~ — N/A (see DRIFT)
- [x] Full suite green — **964 tests / 2955 assertions / 0 failures**
- [x] Lint + format — kondo 0/0, cljfmt clean, `compile main` 0 warnings
- [ ] **Live check (one real agent turn + one manual edit) — STILL OUTSTANDING**,
      same cause as Phase 02: the Claude-in-Chrome extension was unreachable.
      Carried onto Phase 04
- [x] Human approval — standing approval from Santi 2026-07-25
- [x] Committed with a gitmoji commit

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — **Phase 04 can
      proceed.** It now has everything it needs from the commit map: `:who`,
      `:provider`, `:model`, `:profile-id`, `:session-id`, and Phase 01's
      `coalesce` already merges only within one actor. Phase 04 is the first
      phase with no new provenance work — purely lifecycle and buffers.

## Files

- `frontend/src/app/main/data/session_actor.cljs` — new; the marker
- `frontend/src/app/main/data/changes.cljs` — `commit` carries
  `:who`/`:provider`/`:model`; `commit-changes` stamps via `sa/stamp`
- `frontend/src/app/main/data/workspace/agent.cljs` — `run-tool` marks the agent
  around each tool, releases on both terminal paths
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — turn teardown clears
- `frontend/test/frontend_tests/data/session_actor_test.cljs` — new
- `frontend/test/frontend_tests/data/changes_provenance_test.cljs` — extended

## Notes

### The async tail — resolved with a self-expiring marker, not a timer

The phase called this out as the hard part, and it was. Two mechanisms, both
necessary:

**A grace window (`grace-ms` = 400).** Writes settle *after* a tool's observable
completes. `reflow-parent!` (`agent_tools/common.cljs:456`) emits a
`:layout/update` that `shape-layout.cljs` buffers by **100ms**, so a
`create_shape` into a laid-out board produces its reposition commits after the
tool has returned. Clearing on completion would hand the agent's own reflow to
whoever edits next. 400ms clears that buffer plus the transform-modifier / WASM
tick — the same reasoning behind `modify_shape`'s 220ms settle wait. (That tool
holds its own observable open across the settle, so it was already covered; the
*quick* tools are the ones that needed this.)

**Intrinsic expiry (`max-action-ms` = 30000).** This is the part that matters
most and was not in the original plan. A turn cancelled mid-tool unsubscribes via
`rx/take-until` and **never runs `end-agent-action!`**. A marker stuck on
`:agent` would then misattribute every human edit for the rest of the session —
far worse than missing a reflow. So `current-actor` is a function of the clock: a
leaked marker releases itself. The turn teardown (`turn-stream`'s deferred
`::end`, which runs on done / checkpoint / cancel / error alike) also clears
explicitly, but correctness does not depend on that firing.

Expressing the grace window *as* an expiry rather than a `setTimeout` removed the
need for timers entirely, which is also what makes the timing rules unit-testable
— every fn takes an optional `now`.

### Residual risks, stated plainly

- **A human editing within 400ms of the agent's last tool is recorded as the
  agent.** The mirror image of the reflow problem; one of the two directions had
  to be chosen. This one requires a person to act before they could plausibly have
  seen the tool's result, and the turn teardown clears the marker as soon as the
  turn actually ends.
- **Overlapping tools share one marker slot.** `run-tool` runs under `rx/mapcat`,
  which interleaves, so two tools can be in flight. They share provider/model, so
  `:who`/`:provider`/`:model` stay correct regardless — which is exactly why the
  marker deliberately carries **no tool name**. A single slot cannot honestly say
  which tool produced a given write, so it does not claim to.
- **30s is a long ceiling** for a leak to persist if a cancel happens mid-tool
  *and* the teardown somehow does not run. It has to exceed the slowest
  legitimate tool (`screenshot_page` and `fetch_page` make network round trips),
  so it cannot be tightened much without risking a tool outliving its own marker.

### Layering

`session-actor` deliberately lives at `app.main.data` alongside `data.helpers`
and `data.event`, **not** under `data/workspace/`: `app.main.data.changes` is
core infrastructure that everything depends on, and having it require a
`workspace/` namespace is the kind of inverted dependency a Penpot reviewer would
rightly flag. It was written under `workspace/` first and moved once that became
obvious. It has a single require (`app.common.data`), so there is no cycle risk
in either direction.

This does split the recording feature across two directories —
`data/session_actor.cljs` and `data/workspace/session_events.cljs` — slightly
less cohesive, but correct: the marker is read by core infra, the event model is
workspace-level logic.
