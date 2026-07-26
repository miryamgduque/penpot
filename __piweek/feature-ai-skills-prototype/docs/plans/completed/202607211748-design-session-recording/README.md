# Design Session Recording

**Status:** done
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
| Audience & access (added 2026-07-25) | **A bot reads it; admins export it.** Write = the recorder (needs file edit); read = team admins plus the recorder's own; bulk export = admins only. Santi: "The session is intended to be read by a bot. Something admins can export to feed agent sessions." |

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
  the `agent_tools/*` families, using at least nine different write paths.
  **FIXED in Phase 03** with an ambient marker (`app.main.data.session-actor`)
  set around the turn loop's `run-tool` and read in `commit-changes`.
- ~~**The model name is not reachable.**~~ Resolved in Phase 03: `run-tool`
  already closes over `settings`, so no threading was needed. **And the side-turn
  worry was unfounded** — `run-side-turn` validates against
  `side-readonly-tools` and *throws at construction* for anything else
  (`agent.cljs:1140-1145`), so side turns cannot mutate the file at all. With
  `run-turn` having exactly one call site (`ai_panel.cljs:658`), every agent
  mutation flows through the single hook.
- **Not every commit is a human interaction.** WASM text-layout writebacks are
  tagged `:position-data` and already filtered at `workspace.cljs:459`; WASM
  sync commits exist at `changes.cljs:136`. Without a filter the recording will
  be dominated by machine noise.
- **Plugin-data is the wrong home** (considered, rejected): writes there are
  themselves commits, so recording would feed back through the very chokepoint
  it observes, and pollute undo.
- **A second pool is configuration, not surgery** — but **NOT via `ig/derive`**
  (found in Phase 05): integrant resolves `ig/ref` by `isa?`, so deriving from
  `::db/pool` makes every pre-existing `(ig/ref ::db/pool)` ambiguous and Penpot
  refuses to boot. Use a distinct key that delegates to `::db/pool`'s
  multimethods. Optionality is free either way: `init-key` is wrapped in
  `(when uri ...)` (`db.clj:87`), so no config means no pool.
- **The migration machinery is already module-scoped** (Phase 05):
  `apply-migrations!` takes a module name and `mg/setup!` creates a `migrations`
  table in whichever database the pool points at, with `unique(module, step)`. A
  second database needed **no changes** to it.
- **`ig/assert-key` validates nothing in this build** (Phase 05): `*assert*` is
  false, so every assert-key body in the backend is compiled out. Do not rely on
  it for input validation anywhere.

## Phases

1. [Phase 01 — Event model](./done-phase-01-event-model.md) — pure schema + coalescing + noise filter, no infra ✅
2. [Phase 02 — Human attribution](./done-phase-02-human-attribution.md) — carry profile/session id on local and remote commits ✅ *(live two-session check deferred to Phase 04)*
3. [Phase 03 — Agent attribution](./done-phase-03-agent-attribution.md) — ambient marker around the turn loop's `run-tool`, carrying provider/model ✅ *(live agent-turn check deferred to Phase 04)*
4. [Phase 04 — Client recorder](./done-phase-04-client-recorder.md) — start/stop lifecycle, in-memory buffers, caps ✅ *(logic only; not reachable in the app until Phase 08)*
5. [Phase 05 — Separate database](./done-phase-05-separate-database.md) — second Postgres DB, its own pool and migrations ✅ **live-verified**
6. [Phase 06 — Session RPC](./done-phase-06-session-rpc.md) — idempotent upsert + list/get + **admin export for a bot** ✅
7. [Phase 07 — Persistence wiring](./done-phase-07-persistence-wiring.md) — debounced flush, retry-then-disclose, reload resume ✅ *(logic only; reachable in Phase 08)*
8. [Phase 08 — Recording UI](./done-phase-08-recording-ui.md) — record control, session browser + export ✅ **LIVE-VERIFIED**
9. [Phase 09 — Review turn](./done-phase-09-review-turn.md) — the feedback loop itself ✅ **LIVE-VERIFIED with a real Opus 4.8 turn**

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

- ~~**Attribution correctness is the whole feature.**~~ **VERIFIED LIVE
  (2026-07-26, phase 08).** A two-session run proved a collaborator's edit is
  recorded as `source: :remote` and attributed to *their* session, and an agent
  turn proved `who: :agent` with the right model — including the reflow commit
  that lands ~100ms after the tool returns, which is the case the 400ms grace
  window exists for. Details in the phase 08 file.
- **Volume.** A drag emits dozens of commits. Caps and the noise filter are not
  polish; without them the first real session will be unusable and may blow the
  4M payload cap that already aborted two agent turns (NYT postmortem).
- ~~**A second database is new operational surface.**~~ **Resolved and
  live-verified in Phase 05**: with the URI unset the backend logs
  `"sessions database not configured, skipping migrations"` and boots healthy;
  with it set, both pools init and the migration applies under its own module.
  Isolation confirmed — `design_session` exists only in `penpot_sessions`.
- ~~**`:origin` is an implementation detail.**~~ **Superseded** by the Phase 01
  correction above — events are classified from change types, not `:origin`.
  The residual risk is smaller: a *new* change type appearing upstream falls
  through to a generic label. The pinning test covers the known vocabulary so
  an unmapped type is visible rather than silent.
- **Privacy.** This records identifiable per-person activity on a shared document.
  Before anything ships beyond the branch, decide who may read a session and
  whether participants are told they are being recorded.


## Completion Summary

**Completed:** 2026-07-26

### What shipped

Press record on a file; every layout interaction by every participant is captured
with attribution; press stop; hand it to an agent for critique — or export it for
a bot. Eleven commits on `feature/ai-skills-prototype-session-recording`.

- **Event model** (`session-events`) — commits become a semantic timeline, one
  event per gesture, machine noise filtered out.
- **Provenance** (`changes`, `notifications`, `session-actor`) — every commit now
  says which profile, which browser session, human or agent, and on which model.
- **Recorder** (`session-recorder`) — start/stop, bounded buffers, three ceilings
  that never truncate silently.
- **Isolated storage** — recordings live in their own Postgres database with its
  own pool and migrations, optional everywhere, droppable without touching Penpot.
- **RPC** (`design-sessions`) — idempotent upsert, list, get, admin-only export,
  review storage.
- **Persistence** (`session-persist`) — debounced flush, retry-then-disclose,
  resume across reloads.
- **UI** (`ui/session-recorder`) — record control, live counter, session browser,
  clipboard export.
  - ⚠️ **Open privacy gap.** The plan called for a REC badge on the presence
    widget so everyone on the file knows they are being recorded. It shipped and
    was then removed as out of place in the header (Santi, 2026-07-26), so a
    collaborator with the AI panel closed currently sees nothing. Recordings are
    still announced over the websocket — the surface for it is what is missing.
- **Review** (`session-review`) — a tool-less Opus turn that critiques the session
  and stores its verdict.
- Behind `:design-session-recording`, **off by default**.

### What changed from the original plan

- **`:origin` was unusable as a semantic label** (6 of 133 call sites populate
  it). Events are classified from the changes themselves — schema-validated and
  refactor-proof.
- **`ig/derive` for a second DB pool breaks Penpot's boot** (ambiguous refs). A
  delegating key was needed instead.
- **Create/append/finish collapsed into one idempotent upsert**, which then
  dissolved the mid-gesture flush hazard phases 01 and 04 had both flagged.
- **Side turns cannot mutate**, so phase 03's side-turn marking was unnecessary.
- **Session detail view and participant count were descoped**, not half-built —
  the latter because a wrong participant count on a record of who did what is
  worse than none.
- **Two bugs beyond the plan's scope were fixed**: `PENPOT_FLAGS` never reached
  the frontend in dev builds (affecting every flag, `:mcp` included), and resume
  restarted capture without flushing.

### Lessons & follow-ups

- **Live verification earned its keep.** Every phase passed its unit tests; the
  resume bug only appeared with a real reload against a real backend, because
  each piece worked and only the composition was wrong.
- **The devenv OOM-killed the backend** mid-verification (swap exhausted, 13
  other containers up). Not a code fault, but worth knowing before a long session.
- **Open follow-ups**: no orphan reaper for recordings whose file is deleted
  (cross-database cascades do not exist); no per-file quota; participant count
  wants a denormalized column; the 3s flush debounce is untuned; and **any editor
  can start a recording while only admins can export** — deliberate, now visible
  via the badge, but worth revisiting.
- **Privacy is resolved for a prototype, not for a product**: the recorder is
  announced and flag-gated. Whether participants must consent, rather than merely
  be informed, remains a product decision.
