# Phase 09 — Chat polish: model picker, spend meter, history

**Status:** done

## Goal

Bring the Chat tab to parity with `Chat.tsx` for the pieces beyond the US #2 shell: the multi-provider model picker (switching carries history), the usage/spend meter (Claude pricing), and canonical-history trim/persist. Closes out the "chat agent core" milestone.

## Before Start

- [x] Re-read `Chat.tsx` niceties: `ModelPicker` (`:396-494`, grouped by provider over the pool), `UsageMeter` (`:502-516`), `clearChat` (`:156-161`), `trimHistory` (`:50-60`, cut only at user-message boundaries), persist/hydrate (`:114-154`, `PERSIST_VERSION`, `MAX_PERSISTED_ITEMS 200`, `MAX_HISTORY_MESSAGES 40`)
- [x] Re-read cost math: `estimateCostUSD` + `PRICING` (`agent.ts:65-83`, Claude-only)
- [x] Check what US #2 already delivered (transcript render, composer, per-file in-memory persistence, context chip, a basic `<select>` picker) — build only the delta

## Checklist

- [x] Model picker: native `<select>` **grouped by connected provider** via `<optgroup>` over `refs/ai-providers`' enabled models; selecting sets `{provider, model}` for `run-turn`; switching mid-thread carries the canonical history (already provider-agnostic from Phase 01). First-run empty-pool state links to settings/integrations (kept from US #2). *Deviation: a native grouped select instead of the Cursor-style popover — accessible and far less code for the prototype; the popover styling is not worth the surface here.*
- [x] Spend meter: `agent/add-usage` accumulates `UsageTotals` across rounds into `[:ai-panel <file> :usage]` (emitted as a `:usage` turn event per round); meter shows calls · prompt tokens (with cached %) · output tokens · estimated `$` via `agent/estimate-cost-usd` (Claude pricing only; `$` hidden otherwise).
- [x] History management: port `trim-history` (cut at `:user` turn boundaries to keep tool_use/tool_result pairs intact, cap 40 messages) — applied in `run-turn` before the `:done` history is stored. *Persisted-size caps / hard-refresh survival stay out of scope: the store is in-memory per-file (US #2 decision); disk persistence is a separate story.*
- [x] Clear button parity (`dwaip/clear-chat` drops messages+history+usage; disabled while busy).
- [x] `clj-kondo` 0/0, `cljfmt` clean, `shadow-cljs compile main` → 0 warnings
- [x] Preview: sent a turn on haiku → meter `1 calls · 2.3k in (0% cached) · 5 out · ~$0.00`; **switched haiku→opus mid-thread and opus correctly recalled "42" from the haiku turn** (canonical history carried across the model change); meter accumulated to `2 calls · 5.0k in · 8 out · ~$0.03` (cost jump reflects opus pricing); a later turn showed `97% cached` (ephemeral cache marker working); Clear reset transcript+meter to the empty state. *(Cross-provider switch not exercised — only Anthropic is connected on this machine; the codec path is provider-agnostic and identical.)*
- [x] Human approval; commit `:sparkles: Chat model picker grouping, spend meter, history trim`

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] Write the plan **Completion Summary** in the plan README

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — model picker, usage meter, clear
- `frontend/src/app/main/data/workspace/agent.cljs` — `trim-history`, usage/cost helpers

## Notes

- Cross-provider history carry is the payoff of the canonical model from Phase 01 — verify it explicitly here.
- Pricing table is Claude-only by design; the meter hides `$` for other providers.
- After this phase the "chat agent core" milestone is complete; the `ai-skills` React app can be scheduled for removal in a final cleanup once everything is verified live.
