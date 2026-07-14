# Phase 07 — The agent must see

**Status:** todo
**Gated on:** Phases 01–06. Largest build in the plan; do not start it early.

## Goal

Test the spec's boldest claim, and the biggest difference between a design agent and a code
agent: **the model must see what it produced.** A code agent re-reads the file after an edit; a
design agent must re-export the canvas to an image and look at it. Without that, the argument
goes, it goes blind and hallucinates confidence about layouts it cannot perceive.

Today our agent is fully blind. It reasons entirely over JSON from `read_design`. This phase
gives it eyes and measures whether that actually changes output quality — or whether the JSON
was enough all along.

## Before Start

- [ ] **Feasibility spike first.** Can we export a board/shape to PNG from native CLJS? The
      plugin API could; the internal exporter is a different surface
      (`app.main.render` / the exporter RPC). Timebox this — if it is expensive, stop and
      split Phase 07 into its own story rather than bloating this plan.
- [ ] Confirm the vision path: `build-round-body` currently sends text-only content blocks.
      Anthropic takes `{type: "image", source: {type: "base64", ...}}`; the OpenAI-compat path
      differs. Both codecs need an image block.
- [ ] Check the backend proxy passes an image payload through `:ai-agent-round` unmodified, and
      what size limits apply
- [ ] Confirm which enabled models are actually vision-capable — a text-only model must degrade
      gracefully, not error

## Checklist

- [ ] `render_region` tool: export a named board/shape (or the current selection) to PNG
- [ ] Canonical model + both codecs carry an image block; text-only providers degrade gracefully
- [ ] Re-feed after mutation: after an edit batch, export the affected region and hand it back,
      so the next round sees the result (the design analog of re-reading code)
- [ ] Bound it: renders are expensive in tokens and latency. Affected region, not the whole
      page; cap resolution; do not re-feed after every trivial tool call
- [ ] **Experiment H — blind vs seeing.** Same generative prompts as Phase 06, with and without
      the render loop. Score: violations (`audit_file`), plus a human judgement of whether the
      layout is actually right — this is the one hypothesis that resists a purely automatic score
- [ ] Record the cost: tokens and $ per render, and per turn
- [ ] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [ ] Human approval; commit `:sparkles: Native render_region and the see-what-you-did loop`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] Write the plan's **Completion Summary**: the verdict on all four hypotheses, with numbers
- [ ] If deferred instead of built: record *why*, and what the spike found

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `render_region`
- `frontend/src/app/main/data/workspace/agent.cljs` — image blocks in the canonical model and
  both codecs

## Findings

| Experiment | Condition | Prompt | Violations | Layout correct? | Tokens | $ | Note |
|---|---|---|---|---|---|---|---|
| H | blind | | | | | | |
| H | seeing | | | | | | |

## Notes

- Be genuinely open to refuting this one. The spec asserts seeing is essential, but our tools
  are *structural* (`create_shape` with explicit coordinates, layout via flex/grid), not
  freehand — the agent may already know where things are because it put them there. The
  interesting case is the one where it inherits a design it did not create, or where a WASM
  layout settles differently than requested. Design the prompts to include that case, or the
  experiment will trivially favour "blind is fine".
- Cost reality check: an image is worth a lot of tokens. If seeing costs 10× and improves
  quality 5%, the honest answer is "gate it behind an explicit user action", not "always on".
