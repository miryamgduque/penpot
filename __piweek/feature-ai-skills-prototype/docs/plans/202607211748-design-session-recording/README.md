# Design Session Recording

**Status:** doing
**Created:** 2026-07-21
**Apps:** `frontend`, `backend`, `docker/devenv`
**Dependencies:** None
**Branch:** `feature/ai-skills-prototype-session-recording`, to be reconciled
into `feature/ai-skills-prototype` by PR. (Git cannot name it
`feature/ai-skills-prototype/session-recording` — `refs/heads/feature/ai-skills-prototype`
exists as a file, so it cannot also be a directory.)

## Context

Today the only durable record of what happens to a design file is the file itself
plus the agent's own chat transcript. There is **no provenance trail**: nothing
records who changed what, whether a change came from a human or the agent, or
which model was driving. The audit scanner (`agent_tools/agentic.cljs:83`) is a
stateless on-demand rule check with no ledger, and the spend meter is in-memory
token accounting. Neither is a history.

This plan adds **design session recording**: press Record on a file, every
layout interaction by *any* participant (each collaborator and the agent) is
captured with attribution, press Stop, and the agent reviews the session and
says what went well, what went badly, and what could improve.

The point is the feedback loop. A recorded session is training material for
critique — of the humans' process, of the agent's behaviour, and of the
playbooks that steer it.

### Decisions taken at discovery (2026-07-21)

| Question | Decision |
|---|---|
| Event granularity | **Both** — raw ops as source of truth, derived semantic timeline for the model |
| Participants | **All collaborators + agent** — not just the local browser |
| Storage | **Separate Postgres database on the existing server** — isolated from product tables, no new engine (Mongo considered and declined: a new service, driver, config, backup and deploy story for a branch that may head toward a Penpot PR) |
| Raw retention | **Raw is ephemeral, summary persists** — raw lives for the recording, the semantic timeline is what survives |
| Feedback surface | **In-panel review turn** — reuses the existing side-turn machinery and spend meter |

### Grounding — what the codebase actually offers

Verified by exploration on 2026-07-21, not from memory:

- **There is exactly one chokepoint.** The `::commit` potok event
  (`frontend/src/app/main/data/changes.cljs:161`, predicate `dch/commit?` at
  `:36`). Local *and* remote changes both converge there. Four existing
  subscribers already use `(rx/filter dch/commit?)`, so the hook is idiomatic
  (`persistence.cljs:217,255`, `workspace.cljs:455,509`).
- **The commit map is already a decent event.** It carries `:id`,
  `:created-at`, `:source` (`:local`/`:remote`), `:undo-group`, `:tags`,
  `:file-revn` (`changes.cljs:176-193`).

  > **CORRECTION (2026-07-21, Phase 01 Before Start).** This plan originally
  > claimed `:origin` was "a ready-made semantic label". **That is false in
  > practice.** `:origin` is `(ptk/type origin)`, and `origin` is passed by only
  > **6 of 133** `commit-changes` call sites — so ~95% of commits, including
  > every ordinary layout interaction, carry
  > `:origin :potok.v2.core/undefined` (`ptk/type` returns `::undefined` for
  > anything not implementing `Event`; potok `core.cljs:82-86`).
  >
  > The six that *do* pass it are, ironically, mostly not user interactions:
  > `undo/undo`, `undo/undo-to-index`, `undo/redo` (`undo.cljs:316,361,398`),
  > `fix-deleted-fonts-for-local-library` / `-for-page`
  > (`fix_deleted_fonts.cljs:102,120`), the `:position-data` writeback
  > (`workspace.cljs:243`), and one library sync (`libraries.cljs:1358`).
  >
  > **Revised design:** classify events from the **changes themselves** — the
  > `:redo-changes` `:type` values and, for `:mod-obj`, the `:operations`
  > `:attr` names. This is strictly better than `:origin`: it is
  > schema-validated on every commit (`cpc/check-changes`, schema at
  > `common/src/app/common/files/changes.cljc:42-69,191-429`), it is the actual
  > data model rather than an incidental UI event name, and it is identical
  > whether a human or an agent tool produced the change. `:origin` is still
  > recorded, but demoted to a *secondary* signal — its real value is that it
  > is exactly how undo/redo and machine font-repair identify themselves,
  > which the noise filter and the critique both want to know.
  >
  > This removes the plan's original risk #4 ("`:origin` is an implementation
  > detail") almost entirely, and replaces it with a much smaller one: change
  > types are a stable schema, but new change types can appear.
- **`:undo-group` gives operation boundaries for free.** Grouping commits by it
  yields user-meaningful units without inventing a coalescing heuristic.
  Recording at *undo-entry* granularity instead would be strictly worse — undo
  entries drop `:origin` (`workspace.cljs:514-518`).
- ~~**Remote changes are anonymous today.**~~ **FIXED in Phase 02.** The
  websocket schema required `profile-id`/`session-id`
  (`notifications.cljs:234-243`) but `handle-file-change` destructured neither
  (`:249`). It now forwards both into the commit.
- ~~**Local commits are anonymous too.**~~ **FIXED in Phase 02** —
  `commit-changes` stamps `(:profile-id state)` / `(:session-id state)`.
- **A client never sees its own echo** (found in Phase 02, correcting an earlier
  assumption in this plan): the `:subscribe-file` websocket handler filters
  messages from the subscriber's own session
  (`backend/src/app/http/websocket.clj:153-155`). So no dedup is needed. But
  **`session-id` is per browser TAB** (`config.cljs:116`), so one person with the
  file open twice is two sessions — attribution is the
  `(profile-id, session-id)` pair, and nothing may assume one session per
  person.
- **The agent has no mutation funnel.** ~22 mutation call sites spread across
  the `agent_tools/*` families, using at least nine different write paths. The
  only viable tagging point is one level up: `execute-tool`
  (`agent_tools.cljs:1041`) or `run-tool` (`agent.cljs:854`), via an ambient
  marker read at commit time.
- **The model name is not reachable.** `settings {:provider :model}` is a
  closure-local in `run-turn` (`agent.cljs:847`), never in app-db except
  mid-checkpoint (`ai_panel.cljs:684`). It must be threaded down. Side turns
  (`run-side-turn` `agent.cljs:1130`, `tick-settings` `ai_panel.cljs:382`,
  `fix-settings` `:558`) run on *different* models — so "the agent" is not one
  model and the recording must not pretend otherwise.
- **Not every commit is a human interaction.** WASM text-layout writebacks are
  tagged `:position-data` and already filtered at `workspace.cljs:459`; WASM
  sync commits exist at `changes.cljs:136`. Without a filter the recording will
  be dominated by machine noise.
- **Plugin-data is the wrong home** (considered, rejected): writes there are
  themselves commits, so recording would feed back through the very chokepoint
  it observes, and pollute undo.
- **A second pool is a known pattern.** `::db/pool` is an integrant component
  parameterized by uri/username/password (`backend/src/app/main.clj:151`), so a
  derived key for a second database is configuration, not surgery.

## Phases

1. [Phase 01 — Event model](./done-phase-01-event-model.md) — pure schema + coalescing + noise filter, no infra ✅
2. [Phase 02 — Human attribution](./done-phase-02-human-attribution.md) — carry profile/session id on local and remote commits ✅ *(live two-session check deferred to Phase 04)*
3. [Phase 03 — Agent attribution](./todo-phase-03-agent-attribution.md) — ambient marker around `execute-tool`, thread provider/model
4. [Phase 04 — Client recorder](./todo-phase-04-client-recorder.md) — start/stop lifecycle, in-memory buffers, caps
5. [Phase 05 — Separate database](./todo-phase-05-separate-database.md) — second Postgres DB, derived pool, its own migrations
6. [Phase 06 — Session RPC](./todo-phase-06-session-rpc.md) — create/append/finish/list/get commands
7. [Phase 07 — Persistence wiring](./todo-phase-07-persistence-wiring.md) — debounced flush, lifecycle, reload resume
8. [Phase 08 — Recording UI](./todo-phase-08-recording-ui.md) — record control + session browser
9. [Phase 09 — Review turn](./todo-phase-09-review-turn.md) — the feedback loop itself

Phases 01–04 deliver a working recorder with no backend at all (drivable from
the console, the way agent tools were verified in past sessions). 05–07 make it
durable. 08–09 make it usable and close the loop.

## Acceptance Criteria

- Recording can be started and stopped on a file, and its state is visible.
- A session captures interactions from **every** participant on the file — a
  teammate editing in another browser appears in the timeline, attributed.
- Every event states `who` (`:user`/`:agent`), the acting profile, and for agent
  events the provider + model that produced it.
- Machine-generated commits (WASM position-data, sync) do not appear.
- Raw ops back the session during recording and are not retained afterward; the
  semantic timeline persists.
- Session data lives in a database isolated from Penpot's product tables, and
  Penpot runs normally if that database is absent (recording degrades, nothing
  else breaks).
- Stopping a recording can produce an agent critique in the panel, metered like
  any other turn.

## Risks

- **Attribution correctness is the whole feature.** If remote events are
  mislabelled the critique is worse than useless — it would blame the wrong
  person. Phase 02 shipped the code and traced every link of the chain in
  source, but the **two-session live check is still outstanding** (Chrome
  extension was unreachable) and is carried on Phase 04's checklist. Until then,
  remote attribution is code-traced and unit-tested, *not* live-proven.
- **Volume.** A drag emits dozens of commits. Caps and the noise filter are not
  polish; without them the first real session will be unusable and may blow the
  4M payload cap that already aborted two agent turns (NYT postmortem).
- **A second database is new operational surface.** It must be optional — a
  Penpot that cannot reach it should log and continue, never fail to boot.
- ~~**`:origin` is an implementation detail.**~~ **Superseded** by the Phase 01
  correction above — events are classified from change types, not `:origin`.
  The residual risk is smaller: a *new* change type appearing upstream falls
  through to a generic label. The pinning test covers the known vocabulary so
  an unmapped type is visible rather than silent.
- **Privacy.** This records identifiable per-person activity on a shared document.
  Before anything ships beyond the branch, decide who may read a session and
  whether participants are told they are being recorded.
