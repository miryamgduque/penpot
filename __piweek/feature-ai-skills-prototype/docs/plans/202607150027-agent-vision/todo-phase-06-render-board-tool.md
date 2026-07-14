# Phase 06 — `render_board` tool

**Status:** todo
**Depends on:** Phases 01 (verdict), 02, 03, 05
**Gated on:** Phase 01 saying yes. If it said no, this phase is deferred, not attempted.

## Goal

Give the agent eyes it controls. A tool it calls to render a named board/shape — or the current
selection — to PNG and receive the image back. Agent-initiated, no user in the loop.

Phase 04 proved an image can reach the model. This phase is *only* the question of where the
bytes come from, plus one genuinely hard unknown: **how a tool hands an image back.**

## Before Start

- [ ] **Read Phase 01's Findings.** The measured cost and the flag state decide this phase's
      design, and possibly its existence
- [ ] **Resolve the tool-result asymmetry — before writing any code.** Anthropic's `tool_result`
      content is block-capable (`agent.cljs:107`), so the PNG can go straight back. Most
      OpenAI-compatible implementations **reject images in `tool` role messages**. Confirm this
      against the providers we actually enable, then pick a return shape that works for both.
      The likely answer: the tool returns *text*, and the image is appended as a following
      **user** message. **This is the biggest unknown in the plan** — if it is ugly, it is better
      to find out here than in Phase 07
- [ ] Re-read the metaprompt plan's Phase 07 — if it has started, stop and reconcile. Only one
      plan builds this tool
- [ ] Re-read how existing tools are declared in `agent_tools.cljs`

## Checklist

- [ ] Name it **`render_board`**, not `render_region`. The WASM API is per-shape — `_render_shape_pixels`
      takes `(id, scale)` and there is no rectangle. Do not promise a region we cannot deliver
- [ ] Accept a board/shape by name or id, defaulting to the current selection
- [ ] Call `wasm.api/render-shape-pixels` directly. **Skip the blob-URI round-trip** the plugin
      API does at `plugins/shape.cljs:1524` — it re-fetches its own blob over `http/send!` purely
      to satisfy a JS promise contract we do not have
- [ ] **Guard the flags.** `assets.cljs:168-174` warns a WASM render **crashes** when render-wasm
      is inactive. The tool must return a clear error, never take the workspace down
- [ ] Degrade on text-only models: the tool is not offered, or returns a useful refusal. It must
      never produce a provider error
- [ ] Apply Phase 05's downscale to rendered output too — a full page at scale 1 is large
- [ ] Cap the scale. The call is synchronous through a full Skia render + PNG encode; a careless
      scale is a main-thread freeze, not just a token bill
- [ ] Verify live: ask the agent to look at a board it did not create and describe it
- [ ] Record cost: tokens and $ per render
- [ ] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [ ] Human approval; commit `:sparkles: Let the agent render a board and see it`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] **Close the metaprompt plan's Phase 07 as superseded**, citing this phase — the deferred
      decision from this plan's README now resolves
- [ ] Record what the tool-result asymmetry actually turned out to be. It is the finding most
      likely to matter to whoever adds the next image-returning tool

## Findings

| Measure | Value |
|---|---|
| Tokens per render (typical board) | |
| $ per render | |
| Tool-result path used (Anthropic) | |
| Tool-result path used (OpenAI-compat) | |

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — the `render_board` tool
- `frontend/src/app/main/data/workspace/agent.cljs` — image-carrying tool results, if that is the
  resolved shape

## Notes

- The agent already has `read_design` for structure. Be precise in the tool description about
  **when** to render — an agent that renders on every turn out of enthusiasm is a cost bug. The
  honest framing is: render when you need to see something the JSON cannot tell you.
- If the OpenAI path cannot carry tool-result images and the user-message workaround is
  unpalatable, an acceptable outcome is **Anthropic-only for now**, recorded as such. Our
  experiments run on Claude models anyway.
