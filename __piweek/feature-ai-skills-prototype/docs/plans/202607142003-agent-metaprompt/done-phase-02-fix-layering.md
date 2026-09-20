# Phase 02 — Fix the layering

**Status:** done
**Gated on:** ✅ [Phase 01](./done-phase-01-measure-layering.md) **confirmed** the hypothesis —
96% cached stable vs **0% on every selection change**, ~9× the per-turn cost. Proceed.

## Goal

Get the volatile content out of the cached prefix, so the stable knowledge (persona → modes →
rules → skills index → tool defs) is cached once and re-read at ~10% cost, while the per-turn
context (current page + selection) rides at the end where it belongs. Then prove it with the
same experiments Phase 01 ran.

## Before Start

- [x] Read Phase 01's Findings — this phase's success is defined as moving those numbers
- [x] Confirm the canonical history is the right carrier for per-turn context: the last user
      message is the natural "volatile last" slot, and `encode-anthropic` / `encode-openai`
      both already re-encode it per provider
- [x] Check the design context is not needed *before* the first user message (it is not — the
      agent calls `read_design` when it wants ground truth; the chip is orientation only)
- [x] **Re-use Phase 01's harness verbatim** — the console runner, the single-round prompt, and
      above all the **distinctly-named shapes** (identical names produce a byte-identical
      prompt and would fake a pass). Same model (`claude-opus-4-8`), turns back-to-back.
- [x] Note Phase 01's finding that **haiku never caches at our prefix size** — do not expect
      this fix to move haiku's numbers, and do not read that as the fix failing.

## Checklist

- [x] **Split the prompt.** `build-system-prompt` keeps only stable content: persona, operating
      modes, inner-knowledge rules, the skills routing index. The `## Current design context`
      block leaves it.
- [x] **Move context to the volatile slot.** Attach the `{file, page, selection}` orientation
      to the turn's user message (a short prefix or a separate content block) rather than the
      system prompt, so the cached prefix is byte-identical across turns. **This portable form
      is the baseline** — it works on every provider and model.
- [~] *(Optional, Opus 4.8 only — **deliberately skipped**, see Notes)* Anthropic supports **mid-conversation system messages** with
      no beta header: `{"role": "system", content}` appended to `messages[]` keeps the cached
      prefix intact *and* is the non-spoofable operator channel (text in a user turn can be
      forged by anything that writes to user-visible input). It 400s on haiku
      (`role 'system' is not supported on this model`), so it must degrade to the user-turn
      form per model rather than replace it. Only worth it if the orientation is ever
      security-relevant; otherwise the portable path alone is enough.
- [x] Keep the `cache_control` marker on the (now genuinely stable) system block.
- [x] **Re-run Phase 01's experiments A, B and C** unchanged, and fill the After column.
      Success = B's `% cached` no longer collapses on a selection change and approaches A's.
- [x] Sanity-check behaviour, not just cost: the agent must still correctly answer "what is
      selected?" — moving context must not blind it.
- [x] `clj-kondo` 0/0, `cljfmt` clean, `shadow-cljs compile main` → 0 warnings
- [x] Human approval; commit `:zap: Keep volatile design context out of the cached prompt prefix`

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] Record the measured saving (tokens and $ per turn) — this is the number worth quoting in
      the story and the pitch

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — `build-system-prompt` (stable only),
  `encode-*` / round body (volatile context on the user turn)
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — `send-message` passes context to the
  turn rather than into the system prompt

## Before / After

Same harness, same model (`claude-opus-4-8`), same distinctly-named shapes, turns back-to-back.

| Experiment | Metric | Before (Phase 01) | After | Δ |
|---|---|---|---|---|
| A — stable | % cached (turns 2,3) | 96, 95 | 93, 91 | holds ✅ |
| **B — selection changes** | **% cached** | **0, 0, 0** | **95, 92, 89** | **fixed ✅** |
| C — rules changed | % cached | 96 → **0** | 95 → **0** | unchanged ✅ *(correct — see Notes)* |
| B | prefix tokens/turn | 2571 **written** @1.25× | 2562 **read** @0.1× | 12.5× cheaper |
| B | ~$ per turn (Opus $5/M in) | ~$0.0165 | ~$0.0020 | **~8.3× cheaper** |

Turn-1 of A writes the cache cold (`0%`) — expected: the system prompt itself changed, so the
first turn on the new prefix pays the write. Every turn after reads it.

**Behaviour check (the one that mattered):** with "Dashboard Heading" selected, asked *"What is
currently selected?"* → the agent answered **"Dashboard Heading"**, and that turn read 2562 from
cache. Moving the context out of the system prompt did not blind it.

**The trade the fix makes:** input tokens per turn rise (~93 → ~144) because the context JSON is
now uncached in the user turn. That is the point — ~50 tokens at full price instead of ~2,570 at
1.25×.

## Notes

- **C staying at 0% is a pass, not a miss.** Changing the rules genuinely changes the stable
  prefix, so invalidating is correct — the skills index belongs *inside* the cached block. If C
  had also "improved", it would mean the index had leaked out of the cached prefix. A/B/C
  together say exactly the right thing: volatile content moved out, stable content stayed in.
- **Implementation.** `build-system-prompt` is now 0-arity and stable-only; the turn's context
  rides on the canonical user message as `{:role :user :text … :context {…}}`, rendered by a
  single `user-content` helper that both `encode-anthropic` and `encode-openai` call — so the
  portable path works on every provider with no per-provider branch. The transcript still shows
  the user's clean text; only the wire form carries the context block.
- **Not done: the Opus-4.8 `role: "system"` upgrade.** It would preserve the cache *and* give a
  non-spoofable operator channel, but it 400s on haiku, so it needs a per-model branch and only
  pays off if the orientation ever becomes security-relevant. The portable path already captures
  the entire ~8× win. Left out deliberately; revisit only with a reason.
- **Haiku is unaffected and still caches nothing** — its prefix (~2.4k) is under the documented
  ~4096 minimum. Phase 01 predicted this; it is not a regression and not something this phase
  could fix. Phase 03's inner-knowledge layer is the lever that might.
- The residual per-turn input growth across a conversation (129 → 191 → 253 in A) is just the
  history accumulating uncached, exactly as it should — each turn's own context is small.
