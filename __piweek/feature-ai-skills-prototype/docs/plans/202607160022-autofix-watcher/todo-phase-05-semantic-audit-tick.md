# Phase 05 — Semantic audit tick (batched Haiku)

**Status:** todo *(may slip past the Friday demo — deliberately last)*

## Goal

Model-backed detection for judgement calls the regex can't make (is
`"final-v2-copy"` a semantic layer name?), under a hard request budget: **at
most one detection request in flight, ever**. When the file goes idle and no
tick is outstanding, one buffered Haiku call carries *all* dirty shapes × *all*
`detect: "model"` skills (compact JSON in: id/name/type/relevant attrs; JSON
verdicts out). Mid-flight edits rejoin the dirty set for the next tick. Verdicts
land in `[:ai-panel <file-id> :semantic-violations]`; the strip shows the union
with the deterministic set; usage feeds the existing spend meter. Runs only
while the panel is open.

## Before Start

- [ ] Verify plan is still valid; Phases 01 + 04 merged (dirty set exists; the
      catalog says which skills are model-detect and on what model)
- [ ] Re-read `agent/build-round-body` + the buffered (non-stream) request path
      in `agent.cljs` — the tick reuses this, no tools, no streaming
- [ ] Check the RPC `:payload` cap interplay (4M chars — a text-only tick is
      nowhere near it, but confirm no image blocks can leak in)

## Checklist

- [ ] Tests first: tick payload builder (dirty ids → compact shape JSON, cap ~50
      shapes/tick with overflow carried to the next tick); verdict merge
      (parse → replace semantic violations *for the shapes evaluated*, keep the
      rest); prune (shape deleted or re-clean → verdict dropped); in-flight
      gating as a pure decision fn (dirty? × in-flight? × panel-open? → fire?)
- [ ] `agent/detect-round`: one buffered Anthropic round on the skill-declared
      model (Phase 04 resolution, Haiku default) — system prompt = the
      model-detect skills' criteria + strict JSON-verdict output contract;
      no tool specs
- [ ] Tick loop in `data/workspace/ai_panel.cljs`, piggybacking the Phase 01
      watcher stream: dirty set non-empty → idle debounce (~4s) → gate (single
      in-flight, panel open, a model-detect skill enabled, provider key
      present) → fire; clear evaluated ids from the dirty set on response;
      malformed JSON → log, drop, don't crash the watcher
- [ ] Usage from the response → `accumulate-usage` (spend meter shows tick cost)
- [ ] Strip renders the union (deterministic + semantic), semantic entries
      visually distinguishable (e.g. a subtle ✦); Fix it now includes them
- [ ] Lint + format; compile 0 warnings; tests green and listed in runner output
- [ ] Preview verify in devenv with a real Anthropic key: burst of edits → ONE
      request (network tab); verdicts appear on the strip; fixing/deleting the
      shape clears them; panel closed → no ticks; meter increments
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README link
- [ ] Record observed per-tick cost (tokens + $) at Haiku pricing — this is the
      number that decides whether "auto when panel open" stays the right consent
      model
- [ ] Plan complete → completion summary in README, move folder to `completed/`,
      update memory + BRANCH_NOTES

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — `detect-round`
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — tick loop, gating,
  verdict merge, dirty-set consumption
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — semantic marker on the
  strip
- `frontend/test/frontend_tests/data/agent_watcher_test.cljs` — payload/merge/
  gating tests

## Notes

- The tick asks for verdicts on *every* dirty shape (including "clean"), so a
  previously-flagged shape the user fixed gets its semantic violation cleared by
  the same response that evaluated it — no separate invalidation path.
- Rate ceiling worth stating in code comments: requests ≤ one per idle period,
  independent of edit volume. If this ever feels chatty, lengthen the idle
  debounce — never parallelize ticks.
- Keep the verdict contract tiny: `[{"shapeId": "...", "skill": "...",
  "ok": false, "reason": "..."}]`. Reasons feed the strip rows directly.
