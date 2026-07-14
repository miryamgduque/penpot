# Phase 02 — Fix the layering

**Status:** todo
**Gated on:** Phase 01 confirming the hypothesis. If Phase 01 refutes it, this phase is cancelled.

## Goal

Get the volatile content out of the cached prefix, so the stable knowledge (persona → modes →
rules → skills index → tool defs) is cached once and re-read at ~10% cost, while the per-turn
context (current page + selection) rides at the end where it belongs. Then prove it with the
same experiments Phase 01 ran.

## Before Start

- [ ] Read Phase 01's Findings — this phase's success is defined as moving those numbers
- [ ] Confirm the canonical history is the right carrier for per-turn context: the last user
      message is the natural "volatile last" slot, and `encode-anthropic` / `encode-openai`
      both already re-encode it per provider
- [ ] Check the design context is not needed *before* the first user message (it is not — the
      agent calls `read_design` when it wants ground truth; the chip is orientation only)

## Checklist

- [ ] **Split the prompt.** `build-system-prompt` keeps only stable content: persona, operating
      modes, inner-knowledge rules, the skills routing index. The `## Current design context`
      block leaves it.
- [ ] **Move context to the volatile slot.** Attach the `{file, page, selection}` orientation
      to the turn's user message (a short prefix or a separate content block) rather than the
      system prompt, so the cached prefix is byte-identical across turns.
- [ ] Keep the `cache_control` marker on the (now genuinely stable) system block.
- [ ] **Re-run Phase 01's experiments A, B and C** unchanged, and fill the After column.
      Success = B's `% cached` no longer collapses on a selection change and approaches A's.
- [ ] Sanity-check behaviour, not just cost: the agent must still correctly answer "what is
      selected?" — moving context must not blind it.
- [ ] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [ ] Human approval; commit `:zap: Keep volatile design context out of the cached prompt prefix`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] Record the measured saving (tokens and $ per turn) — this is the number worth quoting in
      the story and the pitch

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — `build-system-prompt` (stable only),
  `encode-*` / round body (volatile context on the user turn)
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — `send-message` passes context to the
  turn rather than into the system prompt

## Before / After

| Experiment | Metric | Before (Phase 01) | After | Δ |
|---|---|---|---|---|
| A — stable | % cached | | | |
| B — selection changes | % cached | | | |
| C — skills toggled | % cached | | | |
| B | ~$ per turn | | | |

## Notes

- Experiment C is expected to *stay* invalidated: toggling skills genuinely changes the stable
  prefix, and the hypothesis says that is correct behaviour, not a bug. The story already
  frames this as a visible, accepted cost. If C also improves, something is wrong — the skills
  index would not be in the cached prefix where it belongs.
- Watch for a subtle trap: if the volatile context is appended to the *system* prompt's tail
  rather than the user message, the prefix is still stable but the cache breakpoint may land
  after it. The marker's position is what defines the cached prefix — verify with the numbers,
  not by reading.
