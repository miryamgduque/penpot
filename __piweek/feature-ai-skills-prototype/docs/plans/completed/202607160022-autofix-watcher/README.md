# Auto-fix Watcher

**Status:** done
**Created:** 2026-07-16

> **Execution mode (user directive, 2026-07-16):** built in the worktree
> `feature/autofix-watcher` (based on `feature/ai-skills-prototype` @
> `baed6fd6f3`), **no tests** during development — the test-first items in the
> phase files are waived. Per-phase gates are compile (`shadow-cljs compile
> main`, 0 warnings on our namespaces) + clj-kondo. Per-phase human-approval
> pauses are collapsed into one review at merge time: when all phases are done
> AND no other session is updating the main checkout (user confirms), merge into
> `feature/ai-skills-prototype` and live-test the whole loop in devenv there.
>
> **State 2026-07-16:** all five phases built and committed on
> `feature/autofix-watcher` (5 commits, each compile/lint/format-clean).
> AWAITING: merge gate + the post-merge live checklist below. The plan moves to
> `completed/` only after live testing passes.
>
> **Post-merge live checklist:** panel open → create default-named rect → strip
> within ~1s, zero requests; rename → entry leaves; panel closed → no work;
> expand/collapse + click-to-select+zoom; Fix it now idle → visible message →
> agent fixes → strip empties; Fix it now mid-turn → pending chip (+"via
> claude-haiku…" note) → auto-sends → ✕ cancel works; rules toggle refreshes
> strip; semantic tick: burst of edits → ONE request after ~4s idle (network
> tab), ✦ verdicts appear, fixing/deleting clears them, meter increments,
> record per-tick cost in phase-05 notes; strip + pending chip look right in
> both themes (run `build-app-assets.js` first — the SCSS watch is broken).
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
3. [Phase 03 — Fix it now](./done-phase-03-fix-it-now.md) — visible pre-resolved message into the current session; pending queue when a turn is running
4. [Phase 04 — Per-skill detect/model fields](./done-phase-04-per-skill-detect-model.md) — catalog fields; Fix-it-now turns route to the skill's declared model (Haiku) when available
5. [Phase 05 — Semantic audit tick](./done-phase-05-semantic-audit-tick.md) — single-in-flight batched Haiku detection over the dirty set; verdicts merge into the strip; spend in the meter *(may slip past the demo)*

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

## Completion Summary

**Completed:** 2026-07-16 (built in worktree `feature/autofix-watcher`, merged
as `e6f8e4e7e2` + seed fix `89a99792b0`, live-verified in devenv on the user's
account with a real Anthropic key)

### What Shipped
- Data-layer watcher on `::dch/commit` (IDeref → `:redo-changes`; sees remote
  collaborators too): debounced deterministic re-scan into
  `[:ai-panel <file-id> :violations]`, dirty-id accumulation, panel-open gated.
- Affected strip below the context chip: one-liner summary, expandable
  per-rule breakdown, chip click = select + zoom, ✦ marks semantic verdicts.
- Fix it now (global + per-rule): visible pre-resolved message (ids capped at
  20, overflow named), one-slot pending queue with preview + ✕ when a turn is
  running, auto-drain on turn end.
- Per-skill `:rule`/`:detect`/`:model` catalog fields; `fix-settings` routes
  fix turns to the skill's declared model with panel fallback; enforced rules
  seed from enabled skills' `:rule`s on panel open (`89a99792b0`).
- Semantic audit tick: single-in-flight batched buffered `:ai-agent-round` on
  the skill's model, ≤50 shapes/tick, self-re-arming until dry,
  evaluation-as-invalidation, usage in the spend meter.

### Live verification (2026-07-16, user's Chrome, file "New File 1")
- Panel open → initial scan: 137 layers · 2 rules (seeded rules worked).
- Draw rect → 138/111/40 within ~1.5s, zero requests. Rename → 40→39.
- Tick: exactly ONE request ~4s after idle; verdict merged ("Generic
  'Rectangle' name lacks semantic HTML or role identity"), ✦ rendered;
  rename → next tick cleared it; dirty drained to 0; ~$0.0007/tick.
- Chip click → select + zoom (758%) onto the shape.
- Fix it now (layer-naming): visible message with 20 ids + "+19 more via
  audit_file"; Haiku fetched the skill, audited, renamed in batches; the
  strip shrank live 39→0 deterministic as the agent worked.
- Mid-turn Fix (token colors) → "Queued — sends when the current turn ends"
  chip; auto-drained into the next turn, which created 11 tokens and
  batch-bound 111 violating shapes — Token-only colors left the strip.

### What Changed from Original Plan
- Tests waived (user directive); per-phase gates were compile+kondo+cljfmt,
  one review at merge.
- Added post-plan: enforced-rules seeding (`watched-rules`) — nothing ever
  dispatched `set-enforced-rules`, so the watcher would have shipped dead.
- `penpot-audit-tokens` carries `:rule "token-only-colors"`.

### Lessons & Follow-ups
- **Semantic tick over-flags** (the one real quality gap): blurb-only criteria
  make Haiku flag PascalCase component names and valid role names. Fix by
  inlining naming conventions in the tick prompt, pre-filtering
  obviously-valid names, and skipping component mains/root frames.
- Not live-exercised (code-reviewed only): pending-fix ✕ cancel; panel-closed
  no-work (double-guarded: take-until + open? no-op); "via <model>" note on
  the queued chip (panel model was already Haiku here).
- Skill enable/disable does not re-seed enforced rules until panel reopen.
- DB columns for `detect`/`model`/`rule` (custom skills in the watcher) remain
  the documented follow-up.
