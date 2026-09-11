# Phase 01 — Measure the layering

**Status:** done

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

- [x] **Per-turn cache telemetry.** *Not needed — no code written.* The accumulated
      `[:ai-panel <file> :usage]` total (Phase 09) plus single-round prompts ("Reply with
      exactly: OK. Do not use any tools.") makes each turn's delta equal to that round's usage.
      Read it from the console via `refs/ai-panel-usage`. **Zero production code changed this
      phase** — the instrument we already shipped was sufficient.
- [x] **Experiment A — stable selection (control).** Fresh chat, nothing selected, 3 turns
      back-to-back.
- [x] **Experiment B — selection changes (treatment).** A **distinctly-named** shape selected
      between each turn (see the trap in Notes).
- [x] **Experiment C — rules changed mid-session.** No product toggle exists yet, so simulated
      by patching the cross-namespace `agent/build-system-prompt` call to append a rule.
- [x] Record all three in the Findings table below.
- [x] Write the verdict.
- [x] Human approval; commit `:memo: Measure agent prompt-layering cache behaviour`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] If refuted: cancel Phase 02, record why, and re-scope the plan
- [ ] If confirmed: Phase 02 inherits the baseline numbers as its before/after benchmark

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — per-turn usage readout (dev-only)
- this file — the Findings table

## Findings

All runs on `claude-opus-4-8`, turns back-to-back (3–4s each, far inside the 5-min TTL).

| Experiment | Turn | Input | Cache read | Cache write | % cached | Note |
|---|---|---|---|---|---|---|
| A — stable | 1 | 93 | 2556 | 0 | **96%** | read the warm prefix |
| A — stable | 2 | 118 | 2556 | 0 | **96%** | |
| A — stable | 3 | 143 | 2556 | 0 | **95%** | input creeps: history growing |
| B — selection changes | 1 | 93 | **0** | 2571 | **0%** | selected "Card" |
| B — selection changes | 2 | 119 | **0** | 2572 | **0%** | selected "Card2" |
| B — selection changes | 3 | 145 | **0** | 2576 | **0%** | selected "Dashboard Heading" |
| C — rules changed | 1 | 93 | 2556 | 0 | 96% | baseline, unmodified |
| C — rules changed | 2 | 119 | 2556 | 0 | 96% | no-op control (see Notes) |
| C — rules changed | 3 | 145 | **0** | 2586 | **0%** | rule appended → prefix rewritten |
| C — rules changed | 4 | 171 | 2586 | 0 | **94%** | *same* changed rule → re-stabilises |

**Verdict: CONFIRMED.** A stable conversation caches at ~96%. Changing the canvas selection
collapses it to **0% on every turn** — each one re-writes the whole ~2.5k-token prefix because
the `{file, page, selection}` JSON lives inside the block carrying the `cache_control` marker.
Mutating the rules mid-session does the same (C3), and C4 proves the invalidation is caused by
*the change itself*, not by the presence of the patch: re-running with the same changed rule
caches again at 94%.

**Cost of the defect.** Writes bill at **1.25×** input (5-min ephemeral TTL), reads at **0.1×** —
so the prefix costs **12.5× more** on a miss. At Opus's $5/M input, that is ~**$0.0018/turn
cached vs ~$0.0166/turn uncached — roughly 9× per turn**, paid on every turn where the user
touches the canvas. Which is to say: paid on essentially every real turn, since selecting the
thing you want to talk about is the normal way to use the panel.

## Notes

- **A trap that would have silently faked a refutation.** The context only carries
  `[:name :type]` per selected shape — no ids. The first four shapes on the page are all named
  "Rectangle", so selecting between them produces a **byte-identical** prompt and would have
  shown a healthy cache while "changing the selection". Experiment B only means anything
  because it selects *distinctly-named* shapes. Any re-run must preserve that.
- **C's first attempt didn't measure what it looked like it measured.** Reassigning
  `agent_skills.catalog` from the console changed nothing (routing index stayed 788 chars,
  cache still read) — shadow-cljs compiles same-namespace references directly, so the property
  assignment set something no reader consults. C2 is that no-op, and it is a useful control:
  it shows the cache holding when the "change" didn't actually land. Patching the
  *cross-namespace* `agent/build-system-prompt` call (which `send-message` genuinely goes
  through) is what worked.
- **There is no runtime skills toggle** (`enabled-skills` reads the static `:enabled` defaults),
  so the "don't mutate rules mid-session" corollary isn't reachable through the product today —
  it becomes directly testable when the persisted enabled-state lands (porting plan Phase 10).
- **The prefix is small enough that caching is fragile and model-dependent.** Anthropic's
  documented minimum cacheable prefix is **4096 tokens for Opus 4.8 and Haiku 4.5** (2048 for
  Sonnet 4.6 / Fable 5, 1024 for Sonnet 4.5). Our tools+system prefix is only ~2.5k. That
  predicts exactly what we measured on **haiku: no caching at all** — 3 identical turns, every
  one `cache-read 0, cache-write 0`, ~2341 input each. (Opus caching at 2556 sits oddly against
  its documented 4096 floor; the empirical behaviour is what the table above records. Worth a
  doc check before quoting the number publicly.) Two consequences worth carrying forward:
  **(a)** the spend meter reads a permanent 0% cached for haiku users, and Phase 02's fix will
  do nothing for them; **(b)** Phase 03's inner-knowledge layer *grows* the always-on prefix,
  which pushes it clear of the minimum — so on this axis a bigger stable layer makes caching
  more reliable, not less. That inverts the usual "always-on = bloat" instinct and should be
  said explicitly in Phase 03.
- **Phase 02 has a canonical, documented fix** better than the one the plan drafted. Anthropic
  supports **mid-conversation system messages** on **Opus 4.8** (no beta header): append
  `{"role": "system", content}` to `messages[]` instead of editing top-level `system`. It
  preserves the cached prefix and is the non-spoofable operator channel. It is *not* supported
  on haiku (400: `role 'system' is not supported on this model`), so the portable form — the
  volatile context on the last user turn — stays the baseline, with `role: "system"` as an
  Opus-4.8 upgrade. Phase 02 should implement the portable path first.
