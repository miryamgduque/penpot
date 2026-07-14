# Phase 09 — Chat polish: model picker, spend meter, history

**Status:** todo

## Goal

Bring the Chat tab to parity with `Chat.tsx` for the pieces beyond the US #2 shell: the multi-provider model picker (switching carries history), the usage/spend meter (Claude pricing), and canonical-history trim/persist. Closes out the "chat agent core" milestone.

## Before Start

- [ ] Re-read `Chat.tsx` niceties: `ModelPicker` (`:396-494`, grouped by provider over the pool), `UsageMeter` (`:502-516`), `clearChat` (`:156-161`), `trimHistory` (`:50-60`, cut only at user-message boundaries), persist/hydrate (`:114-154`, `PERSIST_VERSION`, `MAX_PERSISTED_ITEMS 200`, `MAX_HISTORY_MESSAGES 40`)
- [ ] Re-read cost math: `estimateCostUSD` + `PRICING` (`agent.ts:65-83`, Claude-only)
- [ ] Check what US #2 already delivered (transcript render, composer, per-file persistence, context chip) — build only the delta

## Checklist

- [ ] Model picker: popover grouped by connected provider over `refs/ai-providers`' enabled models; selecting sets `{provider, model}` for `run-turn`; switching mid-thread carries the canonical history (already provider-agnostic from Phase 01). First-run empty-pool state links to settings/integrations.
- [ ] Spend meter: accumulate `UsageTotals` across rounds; show calls · prompt tokens (with cached %) · output tokens · estimated `$` (Claude only, hidden otherwise).
- [ ] History management: port `trim-history` (cut at user-message turn boundaries to keep tool_use/tool_result pairs intact) and the persisted-size caps; ensure the per-file store (US #2) versions its payload.
- [ ] Clear button parity (guarded while a turn is busy).
- [ ] `make lint/frontend`, `make typecheck/frontend`
- [ ] Preview: switch Claude→OpenAI mid-conversation and continue with history intact; meter shows tokens + `$` for Claude; clear resets; reopen panel → transcript restored (nav-persistence from US #2).
- [ ] Human approval; commit `feat(workspace): chat model picker, spend meter, history trim`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] Write the plan **Completion Summary** in the plan README (what shipped, deviations, follow-ups: skills-manager tabs, live violations ledger, MCP scope sync, deleting the `ai-skills` React app)

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — model picker, usage meter, clear
- `frontend/src/app/main/data/workspace/agent.cljs` — `trim-history`, usage/cost helpers

## Notes

- Cross-provider history carry is the payoff of the canonical model from Phase 01 — verify it explicitly here.
- Pricing table is Claude-only by design; the meter hides `$` for other providers.
- After this phase the "chat agent core" milestone is complete; the `ai-skills` React app can be scheduled for removal in a final cleanup once everything is verified live.
