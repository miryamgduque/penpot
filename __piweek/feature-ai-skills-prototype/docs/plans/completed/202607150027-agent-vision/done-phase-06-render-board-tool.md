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
- [x] **Resolve the tool-result asymmetry — before writing any code.** Anthropic's `tool_result`
      content is block-capable (`agent.cljs:107`), so the PNG can go straight back. Most
      OpenAI-compatible implementations **reject images in `tool` role messages**. Confirm this
      against the providers we actually enable, then pick a return shape that works for both.
      The likely answer: the tool returns *text*, and the image is appended as a following
      **user** message. **This is now the ONLY large unknown left in the plan** — Phase 01
      retired the render risk, so this is where the surprises will come from. Multi-image return
      (decision 2) makes it sharper, not easier: N images from one tool call have to land
      somewhere legal in both dialects
- [x] Confirm the **WASM renderer is enabled in settings** for the demo profile, and note *where*
      that setting lives — the tool is untestable without it, and Phase 01 only ever forced it
      with `?wasm=true` (a URL param, not a durable setting)
- [x] Re-read how existing tools are declared in `agent_tools.cljs`

## Checklist

- [x] Name it **`render_board`**, not `render_region`. The WASM API is per-shape — `_render_shape_pixels`
      takes `(id, scale)` and there is no rectangle. Do not promise a region we cannot deliver
- [x] Accept **one or more** boards/shapes by name or id, defaulting to the current selection;
      return one image block per board (decision 2). Cap the count — 5 is the number Phase 04
      already established for attachments, and matching it keeps one rule in the user's head
- [x] Call `wasm.api/render-shape-pixels` directly. **Skip the blob-URI round-trip** the plugin
      API does at `plugins/shape.cljs:1524` — it re-fetches its own blob over `http/send!` purely
      to satisfy a JS promise contract we do not have
- [x] **Guard on `(features/active-feature? state "render-wasm/v1")`** — **not** `:wasm-export`.
      Phase 01 proved `:wasm-export` is a red herring (a policy gate for Penpot's own export
      feature, undeclared in `all-flags`, off everywhere); copying `wasm-export-enabled?` would
      leave the tool dead for everyone. Unguarded, the call throws `:wasm-critical` — catchable,
      and the app survives, but the guard is what makes it a clear refusal instead of an abort
- [x] Degrade on text-only models: the tool is not offered, or returns a useful refusal. It must
      never produce a provider error
- [x] **Default to scale 2.** Phase 01 measured it as the sweet spot: text legible, 15 KB, 23 ms.
      Scale 8 costs a 112 ms main-thread block on a *small* board and buys nothing visible
- [x] Cap the scale. The call is synchronous through a full Skia render + PNG encode; a careless
      scale is a main-thread freeze, not just a token bill
- [x] Reject the root frame with a real message. Phase 01: it renders a **1×1 PNG silently**, so
      an agent asking to "see the page" gets a useless image and no signal that anything is
      wrong. Name the boards it can render instead
- [x] Verify live: ask the agent to look at a board it did not create and describe it
- [x] Record cost: tokens and $ per render
- [x] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [x] Human approval; commit `:sparkles: Let the agent render a board and see it`

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] **Close the metaprompt plan's Phase 07 as superseded**, citing this phase — the deferred
      decision from this plan's README now resolves
      → already closed: the user closed it on 2026-07-15 (commit `1c481e0f`) before this plan
      started building. The "keep both, decide later" question is settled; nothing left to do
- [x] Record what the tool-result asymmetry actually turned out to be. It is the finding most
      likely to matter to whoever adds the next image-returning tool → see Findings

## Findings

### ✅ The agent has eyes, and used them unprompted

Asked Opus 4.8 to describe a board it did not create. It reached for `render_board` on its own,
and opened with:

> "I looked at an actual rendered image of the board (not inferred from file data)."

Its description — purple square top-left, "Card Title" / "Description text goes here" /
"Additional content" stacked to its right, "a large empty area below and to the right" — matches
the Phase 01 screenshot of that board exactly. That last clause is the interesting one: *empty
space* is a visual judgement, not something `read_design` can state — only something it would
have to be inferred from. It also spontaneously flagged the board's malformed variant name
(`Card=Size=Large`, two `=`), which suggests the picture didn't crowd out the rest of its
attention.

**Verified from the wire, not the model's word** — a model claiming to have looked is not
evidence of anything. Re-encoding the stored history found exactly **1 real `image` block nested
inside a `tool_result`**: `media_type: "image/png"`, 21,020 base64 chars, magic `iVBORw0K`
(`\x89PNG`).

### The asymmetry, resolved: Anthropic carries images in a tool result, first-class

This was billed as "the ONLY large unknown left in the plan". It turned out not to need a
workaround at all. From the tool-use docs, on `tool_result` `content`:

> "These content blocks can use the `text`, `image`, `document`, or `search_result` types."

— with a worked *"Example of tool result with images"*. So the render goes **straight back inside
the `tool_result`**: no appended user message, no reordering. Two constraints that would have
bitten us are already satisfied by the encoder: tool_result blocks must **immediately follow**
their `tool_use`, and must come **first** in the content array with text after.

**Text first, images after** *inside* a tool_result — the opposite of a user message, where
images lead. The text is the caption for the images beneath it, and the docs' own example does it
that way.

**OpenAI-compatible: there is nowhere to put it.** A `tool` message has no image affordance at
all. That path now appends a note saying N images were rendered but cannot be shown, and to fall
back to `read_design`. Dropping them silently was the trap — the model would have answered as
though it had seen the picture. Untested against a live provider (Anthropic-only for the demo —
see the README scope note).

### Cost

| Measure | Value |
|---|---|
| Render call itself | **131 ms** for 2 boards, resolved by name, scale 2 |
| Per board | ~15 kB PNG → **~20–21 k base64 chars** |
| Turn cost (Opus 4.8, one render) | 8.4k in / 380 out, **~$0.04**, 45% cached |
| Tool-result path (Anthropic) | `image` blocks nested in `tool_result` content ✅ |
| Tool-result path (OpenAI-compat) | impossible — text note, degrade to `read_design` |

A render is roughly **a tenth** the size of a user-attached screenshot, so Phase 05's budget work
already covers it with room to spare. `strip-images` and `prune-history-images` were extended to
cover tool-result images too, so renders do not accumulate across turns either.

Worth noting the meter read **45% cached** here where the Phase 04 attachment turn read 0%. Not
investigated — caching belongs to the metaprompt plan — but it does suggest the 0% is not a
blanket failure.

### 🚩 I changed a profile setting

`render_board` needs the **WASM renderer**, and the demo profile was on SVG (the default), so the
tool correctly refused. **I switched the profile to `:wasm`** — Settings › Options › "Use WebGL
renderer". This is a durable per-profile setting, not the `?wasm=true` URL param Phase 01 leaned
on. The workspace header now reads "WebGL rendering" and `render_board` works.

**This is required for Friday's demo**, but it is a real change to the account and it switches the
renderer for *every* file, not just this one. If some other file starts behaving oddly, that is
the first thing to suspect. The guard means turning it back off degrades the tool to a clear
refusal rather than breaking anything.

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
