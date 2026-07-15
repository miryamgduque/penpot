# Auto-fix Watcher

**Status:** doing
**Created:** 2026-07-16

> **Execution mode (user directive, 2026-07-16):** built in the worktree
> `feature/autofix-watcher` (based on `feature/ai-skills-prototype` @
> `baed6fd6f3`), **no tests** during development — the test-first items in the
> phase files are waived. Per-phase gates are compile (`shadow-cljs compile
> main`, 0 warnings on our namespaces) + clj-kondo. Per-phase human-approval
> pauses are collapsed into one review at merge time: when all phases are done
> AND no other session is updating the main checkout (user confirms), merge into
> `feature/ai-skills-prototype` and live-test the whole loop in devenv there.
**Apps:** `frontend`
**Dependencies:** None (builds on the native agent panel; the deferred "live violations ledger + change-watcher" item from the CLJS-port plan becomes this plan)

## Context

Auto-fix today is a *governance mode*, not a watcher: a skill marked `autofix` may
apply changes without pausing for review, but only when the user invokes it in
chat. Detection is on-demand only (`audit_file` — a fresh scan against
`token-only-colors` + `layer-naming`). The old React plugin's live watcher +
violations ledger was never ported.

This plan makes auto-fix ambient: a **live set of affected elements** that grows
and shrinks as people work — a shape created with a default name enters the set;
the user renames it later and it leaves. The set is surfaced as a one-liner strip
at the top of the chat tab (below the context chip) that expands into a per-rule
breakdown with a **Fix it now** action running in the *current* session (no
second session, no hidden turns).

Design decisions (from the discovery interview, 2026-07-16):

- **Demo-lean ordering** — the demo is Friday 2026-07-17 (Anthropic models only).
  Phases 01–03 (deterministic watcher → strip → Fix it now) are the demoable
  loop; the Haiku semantic tick (Phase 05) can slip past the demo.
- **Request budget: at most one detection request in flight, ever.** The watcher
  itself never calls a model. Deterministic checks (regex names, raw-color-not-
  token) recompute the live set for free on every debounced change. Semantic
  judgement runs as a single *audit tick*: one batched Haiku call covering all
  dirty shapes × all model-detect skills, fired only when idle and no tick is
  outstanding; mid-flight edits join the next tick. Request rate is bounded by
  quiet periods, not edit rate.
- **Consent = panel open.** The watcher (and later the tick) runs only while the
  AI panel is open; spend lands in the existing usage meter. Closed panel = zero
  work, zero requests.
- **Fix it now is a visible user-style message** in the transcript, pre-resolved
  with the shape ids and skill name. If a turn is running it renders as pending
  and auto-sends when the turn ends.
- **Per-skill `detect`/`model` fields live in the builtin catalog only**
  (`agent_skills.cljs`) for the prototype — no DB migration. DB columns become a
  follow-up when user-created skills need watching.

Grounding (verified in code, 2026-07-16):

- Every mutation flows through `dch/commit-changes`
  ([changes.cljs:222](../../../../../frontend/src/app/main/data/changes.cljs)) —
  a `ptk/reify ::commit-changes` whose params carry `:redo-changes` naming the
  touched shape ids. A data-layer watcher can `rx/filter (ptk/type? ::dch/commit-changes)`
  on the global stream, exactly like `persistence.cljs` filters its own events.
- The deterministic scan already exists: `at/audit-violations`
  ([agent_tools.cljs:1840](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs))
  is a pure `state → violations` fn (cap 1000 shapes, only *enforced* rules) — the
  watcher reuses it verbatim; no new detection code in Phase 01.
- Panel lifecycle + state live in `data/workspace/ai_panel.cljs`
  (`toggle-panel`/`close-panel`, state under `[:ai-panel <file-id> …]`,
  `send-message`, `set-busy`, `accumulate-usage`); refs in `refs.cljs:294-320`.
- The chat tab renders the context chip (page + selection) above the transcript
  in `ui/workspace/ai_panel.cljs` (`chat-tab*`, `selection-label` :141) — the
  strip slots directly below it.
- Model plumbing: `agent/build-round-body` already encodes a buffered no-stream
  Anthropic round; Haiku 4.5 is in the priced pool (`agent.cljs:453`).

## Phases

1. [Phase 01 — Live violations watcher](./done-phase-01-live-violations-watcher.md) — data-layer watcher: debounced re-audit on `commit-changes` into `[:ai-panel <file-id> :violations]`, panel-open gated; dirty-id accumulation for Phase 05
2. [Phase 02 — Affected strip UI](./done-phase-02-affected-strip-ui.md) — collapsed one-liner below the context chip, expandable per-rule breakdown
3. [Phase 03 — Fix it now](./todo-phase-03-fix-it-now.md) — visible pre-resolved message into the current session; pending queue when a turn is running
4. [Phase 04 — Per-skill detect/model fields](./todo-phase-04-per-skill-detect-model.md) — catalog fields; Fix-it-now turns route to the skill's declared model (Haiku) when available
5. [Phase 05 — Semantic audit tick](./todo-phase-05-semantic-audit-tick.md) — single-in-flight batched Haiku detection over the dirty set; verdicts merge into the strip; spend in the meter *(may slip past the demo)*

## Acceptance Criteria

- With the panel open and rules enforced, creating a `Rectangle`-named shape or a
  raw-hex fill makes the strip appear within ~1s without any model request;
  renaming/fixing or deleting the shape makes the entry leave the set on the next
  debounce with no manual re-scan.
- With the panel closed, no watcher work runs (no state churn, no requests).
- The strip reads as one line ("⚡ N layers need attention · M rules"),
  expands to a per-rule breakdown, and disappears at zero.
- **Fix it now** posts a visible message into the current conversation carrying
  the resolved shape ids; if a turn is in flight the message shows as pending and
  sends itself when the turn ends. No second session is ever created.
- A skill can declare `detect` and `model` in the builtin catalog; a Fix-it-now
  turn driven by such a skill runs on the declared model when the user's pool has
  it, and falls back to the panel's selected model otherwise.
- (Phase 05) Semantic detection issues **at most one request at a time**, batched
  over all dirty shapes; a burst of 40 edits costs one Haiku call; usage appears
  in the existing spend meter.
