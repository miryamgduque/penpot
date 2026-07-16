# Agent Token Efficiency

**Status:** doing
**Created:** 2026-07-16
**Apps:** `frontend`
**Dependencies:** None (builds on the US #26 metaprompt work — context already rides the user message)

## Context

Sessions in the agent panel spend far more tokens than the work they produce. A code
audit (2026-07-16) found the causes, in order of impact:

1. **The conversation history is never cached.** `build-round-body` puts the
   `cache_control` breakpoint on the system block only, so tools+system (~9–10k
   tokens) are cached but every *round* re-sends the whole message history at full
   input price. A turn is up to 32 rounds and history grows every round (tool results
   up to 20k chars each), so cost is roughly quadratic in rounds — a single
   build-screen turn burns ~300k uncached input tokens (~$0.90 Sonnet / ~$1.50 Opus).
2. **Stale-tool-result pruning was lost in the native port.** The retired TS app
   stubbed tool results >400 chars once older than the last 8 messages; the CLJS
   loop only trims at 40 *messages* (count-based, not size-based). Old `read_design`
   dumps and 15k-char skill bodies ride every round of every later turn.
3. **No runaway brake.** `max-rounds 32` is the only per-turn limit. Evidence: Haiku
   ignored the vibes stop rule and spent ~$4 building an unrequested landing page
   (2026-07-15).
4. **No compaction.** History grows without bound until the 40-message cliff; there
   is no summarize-and-restart, so long sessions carry everything.
5. **Exploratory reads run in the expensive context.** Audit sweeps and whole-file
   scans put multi-round 20k-char dumps into the main (Opus/Sonnet) history, when a
   cheap side context could do the reading and return a digest.

This plan applies the technique stack agent harnesses (e.g. Claude Code) use, safest
first: cache the history, prune dead tool results, brake runaway turns, compact at a
threshold, and isolate exploration into a cheap side context. Expected combined
effect on a heavy session: roughly an order of magnitude.

The panel's spend meter (input/output/cache-read/cache-write per round, cached %
displayed) is the built-in verification instrument for every phase.

**Implementation timing is deliberately open** — the user decides when each phase
lands (demo is Friday 2026-07-17; phase 01 is the safest pre-demo candidate).

## Phases

1. [Phase 01 — History cache breakpoint](./done-phase-01-history-cache-breakpoint.md) — cache the conversation, not just tools+system (~8–10× on the dominant term)
2. [Phase 02 — History hygiene](./todo-phase-02-history-hygiene.md) — stub stale tool results (microcompaction) + token-budget trim
3. [Phase 03 — Runaway brake](./todo-phase-03-runaway-brake.md) — mid-turn pause-and-ask checkpoint after N rounds / ~$X
4. [Phase 04 — Auto-compaction](./todo-phase-04-auto-compaction.md) — summarize-and-restart at a history threshold, subtle transcript note
5. [Phase 05 — Side-context runner](./todo-phase-05-side-context-runner.md) — a buffered tool-loop on a cheap model (generalizes `detect-round`)
6. [Phase 06 — Scout tool](./todo-phase-06-scout-tool.md) — `explore_design` delegates read sweeps to the side context, returns a digest
7. [Phase 07 — Live verification + docs](./todo-phase-07-live-verification.md) — before/after measurements in devenv on a scripted scenario

## Acceptance Criteria

- Spend meter shows ≥90% cached input on a multi-round turn (today it *falls* as a
  turn progresses).
- A session's later turns do not re-send earlier turns' large tool results verbatim.
- A turn cannot exceed the checkpoint threshold without the user explicitly
  continuing; the $4-runaway scenario is no longer possible silently.
- A long session (history past the compaction threshold) keeps working with a
  visible "compacted" note and a wire history reset to summary + recent tail.
- Exploratory sweeps of a large file arrive in the main history as one digest tool
  result, not N raw dumps.
- All existing agent/agent-tools tests stay green; each phase adds tests first.
