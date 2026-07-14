# Phase 01 — Measure the layering

**Status:** todo

## Goal

Settle the prompt-layering hypothesis with numbers before touching any code. Prediction: because
the volatile `{file, page, selection}` JSON sits inside the block carrying the `cache_control`
marker, **changing the selection between turns collapses the cache hit rate** — the agent pays
full price to re-read a system prompt + tool defs that did not meaningfully change.

If the numbers say otherwise, the hypothesis is wrong and Phase 02 is cancelled. That is the
point of measuring first.

## Before Start

- [ ] Re-read `agent/build-round-body` — confirm the Anthropic `system` block carries
      `cache_control {:type "ephemeral"}` and that `:tools` sit alongside it
- [ ] Re-read `agent/build-system-prompt` — confirm the `## Current design context` JSON is
      inside that same cached block
- [ ] Re-read `ai-panel/send-message` — confirm `system` is rebuilt per turn from live context
- [ ] Confirm the usage decode already captures `cache-read-tokens` / `cache-write-tokens`
      (Phase 09) — the raw material is there; only per-turn visibility is missing
- [ ] Note the provider caveat: run on Claude models only (OpenAI-compat reports no write count)

## Checklist

- [ ] **Per-turn cache telemetry.** The spend meter aggregates across a conversation, which
      hides per-turn behaviour. Add a dev-only readout of each round's
      `{input, output, cache-read, cache-write}` — the `:usage` turn event already carries it,
      so this is a reporting change, not new plumbing. Console-level is enough; no UI surface.
- [ ] **Experiment A — stable selection (control).** Fresh chat, select nothing, send 3 turns
      back-to-back. Record cache-read/write per turn. Expect: turn 1 writes the cache, turns
      2–3 read it (high `% cached`).
- [ ] **Experiment B — selection changes (treatment).** Fresh chat, send a turn, change the
      canvas selection, send another, change again, send a third. Record per turn.
      Prediction: every turn after a selection change shows cache-write ≈ full prefix and
      cache-read ≈ 0.
- [ ] **Experiment C — skills toggled mid-session.** Same shape, but toggle a skill between
      turns instead of the selection (tests the "don't mutate rules mid-session" corollary).
- [ ] Record all three in the Findings table below: tokens, `% cached`, and the wall-clock gap
      between turns (a >5min gap can masquerade as a miss — the TTL, not the layering).
- [ ] Write the verdict: hypothesis **confirmed / refuted / partial**, with the numbers.
- [ ] Human approval; commit `:memo: Measure agent prompt-layering cache behaviour`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] If refuted: cancel Phase 02, record why, and re-scope the plan
- [ ] If confirmed: Phase 02 inherits the baseline numbers as its before/after benchmark

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — per-turn usage readout (dev-only)
- this file — the Findings table

## Findings

| Experiment | Turn | Input | Cache read | Cache write | % cached | Gap | Note |
|---|---|---|---|---|---|---|---|
| A — stable | 1 | | | | | | |
| A — stable | 2 | | | | | | |
| A — stable | 3 | | | | | | |
| B — selection changes | 1 | | | | | | |
| B — selection changes | 2 | | | | | | |
| B — selection changes | 3 | | | | | | |
| C — skills toggled | 1 | | | | | | |
| C — skills toggled | 2 | | | | | | |

**Verdict:** _(to fill)_

## Notes

- The control (A) is not optional: without it, a low `% cached` in B could just mean caching
  never worked at all. We have already seen 97% cached on a stable conversation, so A is
  expected to reproduce that — it is the baseline that makes B's collapse meaningful.
- Prompt caching has a minimum cacheable prefix (~1024 tokens on most Claude models). Our
  system + tool defs are comfortably over it, but if A shows no caching at all, check this
  before concluding anything about layering.
