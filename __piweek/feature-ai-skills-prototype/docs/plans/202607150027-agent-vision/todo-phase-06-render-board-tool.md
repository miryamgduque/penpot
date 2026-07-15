# Phase 06 — `render_board` tool

**Status:** todo
**Depends on:** Phases 01 (verdict), 02, 03, 05
**Gated on:** ✅ **Phase 01 said yes** — 4–40 ms, ~6 KB, legible text, byte-identical off-screen.
The render itself is a solved problem; do not re-litigate it.

## Goal

Give the agent eyes it controls. A tool it calls to render a named board/shape — or the current
selection — to PNG and receive the image back. Agent-initiated, no user in the loop.

Phase 04 proved an image can reach the model. Phase 01 proved the bytes are cheap and correct.
So this phase is **only** the genuinely hard unknown: **how a tool hands an image back.**

## Decisions locked in with the user (2026-07-15, after Phase 01)

1. **Guard on `render-wasm/v1` and degrade — no SVG fallback.** The tool is offered only when the
   WASM renderer is active. Users on the SVG renderer do not get agent vision, and that is
   accepted. **Plus: enable the WASM renderer in settings** for our own profile/demo so the
   prototype actually exercises this path. The SVG fallback (`render/render-frame` →
   `app.main.rasterizer/render`) is **explicitly not built** — record it as the known cost of
   universal support if it ever comes back.
2. **Multi-image return, no compositing.** There is no full-page render (Phase 01: the root frame
   is 0.01×0.01 → a 1×1 PNG). Rather than stitch boards together, the tool accepts **several
   boards** and returns **several image blocks** in one turn. More tokens, no stitching code, and
   nothing pretends to be a page that is not one.

## Before Start

- [x] **Read Phase 01's Findings.** The measured cost and the flag state decide this phase's
      design, and possibly its existence → done; the two decisions above are its output
- [ ] **Resolve the tool-result asymmetry — before writing any code.** Anthropic's `tool_result`
      content is block-capable (`agent.cljs:107`), so the PNG can go straight back. Most
      OpenAI-compatible implementations **reject images in `tool` role messages**. Confirm this
      against the providers we actually enable, then pick a return shape that works for both.
      The likely answer: the tool returns *text*, and the image is appended as a following
      **user** message. **This is now the ONLY large unknown left in the plan** — Phase 01
      retired the render risk, so this is where the surprises will come from. Multi-image return
      (decision 2) makes it sharper, not easier: N images from one tool call have to land
      somewhere legal in both dialects
- [ ] Confirm the **WASM renderer is enabled in settings** for the demo profile, and note *where*
      that setting lives — the tool is untestable without it, and Phase 01 only ever forced it
      with `?wasm=true` (a URL param, not a durable setting)
- [ ] Re-read how existing tools are declared in `agent_tools.cljs`

## Checklist

- [ ] Name it **`render_board`**, not `render_region`. The WASM API is per-shape — `_render_shape_pixels`
      takes `(id, scale)` and there is no rectangle. Do not promise a region we cannot deliver
- [ ] Accept **one or more** boards/shapes by name or id, defaulting to the current selection;
      return one image block per board (decision 2). Cap the count — 5 is the number Phase 04
      already established for attachments, and matching it keeps one rule in the user's head
- [ ] Call `wasm.api/render-shape-pixels` directly. **Skip the blob-URI round-trip** the plugin
      API does at `plugins/shape.cljs:1524` — it re-fetches its own blob over `http/send!` purely
      to satisfy a JS promise contract we do not have
- [ ] **Guard on `(features/active-feature? state "render-wasm/v1")`** — **not** `:wasm-export`.
      Phase 01 proved `:wasm-export` is a red herring (a policy gate for Penpot's own export
      feature, undeclared in `all-flags`, off everywhere); copying `wasm-export-enabled?` would
      leave the tool dead for everyone. Unguarded, the call throws `:wasm-critical` — catchable,
      and the app survives, but the guard is what makes it a clear refusal instead of an abort
- [ ] Degrade on text-only models: the tool is not offered, or returns a useful refusal. It must
      never produce a provider error
- [ ] **Default to scale 2.** Phase 01 measured it as the sweet spot: text legible, 15 KB, 23 ms.
      Scale 8 costs a 112 ms main-thread block on a *small* board and buys nothing visible
- [ ] Cap the scale. The call is synchronous through a full Skia render + PNG encode; a careless
      scale is a main-thread freeze, not just a token bill
- [ ] Reject the root frame with a real message. Phase 01: it renders a **1×1 PNG silently**, so
      an agent asking to "see the page" gets a useless image and no signal that anything is
      wrong. Name the boards it can render instead
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
