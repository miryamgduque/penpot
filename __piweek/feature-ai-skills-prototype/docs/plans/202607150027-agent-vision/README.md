<!-- TRANSIENT — part of the __piweek/ team scratch. Delete before the PR is finalized. -->

# Agent vision — let the model see, both ways

**Status:** todo
**Created:** 2026-07-15
**Apps:** `frontend`
**User story:** TBD — not yet in Taiga
**Depends on:** [Agent chat metaprompt](../202607142003-agent-metaprompt/) — start after it lands. This
plan reworks the same `agent.cljs` codecs that plan is actively editing.
**Supersedes:** that plan's
[Phase 07 — The agent must see](../202607142003-agent-metaprompt/done-phase-07-render-refeed.md)
— **decided 2026-07-15, closed as superseded.** See *Relationship to Phase 07* below.

## Context

Our agent is blind. It reasons entirely over JSON from `read_design`, and the only thing the
user can hand it is text. Two different holes, one underlying gap — **no image ever reaches the
model** — and therefore one shared foundation.

The two halves:

1. **The agent takes its own screenshot.** A tool it calls to render a board/shape to PNG and
   look at the result. Agent-initiated, no user in the loop. This is the design-agent analog of
   a code agent re-reading the file it just wrote.
2. **The user attaches images.** Paste, pick, or drop up to 5 photos into the composer — a
   reference screenshot, a competitor's UI, a whiteboard photo, a bug report.

Both need the same thing first: an image content block in the canonical message model and in
both provider codecs. Build that once, and each half becomes small.

### What exploration already settled (2026-07-15, grounded in the code)

- **The PNG is one synchronous call away.** `wasm.api/render-shape-pixels(shape-id, scale)`
  returns a `Uint8Array` of PNG bytes — no network, no exporter service, no promise. It renders
  to a dedicated **export surface**, not the viewport, with viewport state saved/restored, so it
  works for boards that are off-screen or scrolled away. `app.main.data.exports.wasm/export-image-uri`
  and the plugin API's `shape.export()` are both working precedents. **Phase 07's feasibility
  spike is effectively pre-answered** — the expensive path (`rp/cmd! :export` → the headless
  browser `exporter` service) is the one we avoid.
- **The backend needs no changes at all.** `ai_providers.clj` is a dumb pipe: "the payload is
  forwarded byte-for-byte and the response is never decoded." No content-block validation.
- **But there is a hard cap, and it is ours, not the provider's.** The RPC schema pins
  `:payload [:string {:max 4000000}]`. That is ~3 MB of raw image bytes **shared with the entire
  rest of the payload** — full history, system prompt, every tool schema. Overrun is an RPC
  *validation* error, not a provider error. Everything upstream is far more generous (nginx 300M,
  http.clj 350 MiB), so this cap is the whole budget story.
- **The seam is exactly one function.** `user-content` (`agent.cljs:41-53`) renders context+text
  to a plain string, and its docstring says: "Both providers take a plain string here, so one
  renderer serves both." That is precisely the assumption images break.
- **The dialects diverge.** Anthropic wants bare base64 + a separate `media_type`; OpenAI wants a
  full `data:` URI. So the canonical model stores `{:mtype :data}` and each encoder assembles its
  own shape — consistent with the existing re-encode-per-round design that lets users switch
  models mid-conversation.
- **`read-file-as-data-url` already exists** (`webapi.cljs:60-62`), returns an rx observable that
  drops straight into the rx-based send pipeline, and already carries a Safari/WebKit repeated-
  mimetype fix we would otherwise re-hit.
- **A free win:** the global canvas paste handler already declines `TEXTAREA` targets
  (`viewport/actions.cljs:585-586`), so a composer paste handler will not race the
  paste-image-onto-canvas flow. No coordination needed.

### Decisions locked in with the user (2026-07-15)

1. **Agent renders the canvas** — a clean export of a board/shape, *not* a capture of the
   literal viewport as the user sees it.
2. **Attachments are local-only, never stored.** Base64 in the browser, sent with the turn.
   Nothing touches Penpot's media storage. Accepted consequence: attachments vanish from the
   transcript on reload. Persistence is a follow-up, not this plan.
3. **Images only, max 5.** PNG/JPEG/WebP. Text/code file attachments are out of scope.
4. **Phase 07 stays where it is for now** — see below.

### Relationship to Phase 07 — **decided**

**2026-07-15, the user's call: metaprompt Phase 07 is closed as superseded by this plan.** The
metaprompt plan reached Phase 07 with this plan not yet started, and rather than run it as
written the user chose to close it — correctly, because the two corrections below are not
refinements, they are the difference between building the right tool and the wrong one:

- **The spike is already answered** — use `render-shape-pixels`, synchronously; the exporter
  service is the path to avoid.
- **`render_region` was the wrong name** — the WASM API is per-shape, so region granularity does
  not exist for free. This plan builds `render_board`.

Phase 07 there would also have shipped only half the value: the shared image-block foundation
unlocks user attachments too, which that phase never scoped.

**What this plan inherits from it** (see its
[closing note](../202607142003-agent-metaprompt/done-phase-07-render-refeed.md)): the
structural-tools caveat and the cost-honesty warning are already carried in the risks below. The
third inheritance is a hard-won correction and is folded into Phase 07 of this plan:
**do not score blind-vs-seeing with `audit_file`.** Metaprompt Phase 06 measured it — a
violations count scores the *floor* (no default names, no raw hex) and is structurally blind to
design quality; a good and a mediocre design both score 0. Scoring this needs a judge model, a
convention-aware check, or an honest human read.

### Naming correction

Phase 07 calls the tool `render_region`. **The WASM API renders a shape, not a rectangle** —
`_render_shape_pixels` takes `(id, scale)` only. An arbitrary region would need a synthetic shape
or the viewport-only `capture-canvas-snapshot`. This plan therefore builds **`render_board`**
(a named board/shape, or the current selection) and treats "region" as out of scope.

## Phases

1. [Phase 01 — Prove the pixels](./todo-phase-01-prove-the-pixels.md) — a timeboxed spike:
   does `render-shape-pixels` actually produce a usable PNG in the devenv, and are the flags on?
   Cheap, and it decides whether Phases 06–07 exist at all.
2. [Phase 02 — Image blocks in the codecs](./todo-phase-02-image-blocks.md) — the shared
   foundation: `:images` on the canonical user message, both encoders, tests.
3. [Phase 03 — Vision capability per model](./todo-phase-03-vision-capability.md) — a `:vision`
   flag in the curated catalog; text-only models degrade gracefully instead of erroring.
4. [Phase 04 — Attach images in the composer](./todo-phase-04-composer-attach.md) — pick, paste,
   drop; ≤5; thumbnails; remove. The first end-to-end proof that an image reaches the model.
5. [Phase 05 — Fit the budget](./todo-phase-05-fit-the-budget.md) — downscale on attach, and
   strip images from old history. Without this the 4M cap is a time bomb, not an edge case.
6. [Phase 06 — `render_board` tool](./todo-phase-06-render-board-tool.md) — the agent takes its
   own screenshot. Gated on Phase 01's verdict.
7. [Phase 07 — See what you did](./todo-phase-07-see-what-you-did.md) — re-feed after mutation,
   plus the blind-vs-seeing experiment. The one hypothesis that resists an automatic score.

**Sequencing note.** Attachments (02→05) come before the render tool (06→07) deliberately, even
though the user asked about screenshots first. Attaching a photo is the cheapest possible
end-to-end proof of the whole vision pipeline — codecs, provider, model — and it needs no WASM
flag, no new tool, and no render. Once a pasted screenshot demonstrably reaches the model, the
render tool is *only* the question of where the bytes come from. Phase 01 still runs first
because it is cheap and its verdict can reshape the plan.

## Acceptance Criteria

- The canonical message model carries images, and **both** codecs encode them in their own
  dialect, with tests covering each.
- A user can attach up to 5 images by picker, paste, and drop; they render as thumbnails, are
  individually removable, and the model demonstrably describes them.
- Selecting a text-only model degrades gracefully — a clear affordance, never a provider error.
- The 4M payload cap cannot be reached by normal use: images are downscaled on attach, and old
  images are stripped from history.
- Nothing is written to Penpot's media storage.
- The agent can call `render_board` and act on what it sees, **or** the phase is explicitly
  deferred with the spike's reason recorded.
- The blind-vs-seeing claim is settled with evidence, including the honest cost per render.
- `clj-kondo`, `cljfmt`, and `shadow-cljs compile main` stay clean; each phase verified live in
  the devenv.

## Open questions / risks

- **Tool results may not be able to carry images on the OpenAI path.** Anthropic's `tool_result`
  content is block-capable, so `render_board` can hand the PNG straight back. Most
  OpenAI-compatible implementations **reject images in `tool` role messages**. The likely
  workaround is: the tool returns text, and the image is appended as a following *user* message.
  That asymmetry lands squarely in Phase 06 and is the single biggest unknown in this plan.
  **Confirm it before designing the tool's return shape.**
- **The WASM flags may simply not be on.** `render-shape-pixels` requires `:wasm-export` **and**
  `render-wasm/v1`, and `assets.cljs:168-174` warns a WASM render **will crash** if render-wasm
  is inactive — the shape tree is not loaded. If the devenv does not have them, Phase 01's
  fallback is `render/render-frame` → `app.main.rasterizer/render` (async, iframe, no flag, no
  backend). Slower, but it works today.
- **The render blocks the main thread** through a full Skia render + PNG encode. Fine for one
  board; a re-feed loop that renders after every edit is a UI-jank risk, not just a token cost.
- **Cost honesty, inherited from Phase 07.** An image is worth a lot of tokens. If seeing costs
  10× and improves quality 5%, the honest answer is "gate it behind an explicit user action",
  not "always on". Be genuinely open to refuting the hypothesis.
- **Our tools are structural**, not freehand — the agent may already know where things are
  because it placed them with explicit coordinates. The interesting case is a design it did
  *not* create, or a WASM layout that settles differently than requested. Design the Phase 07
  prompts around that case or the experiment trivially favours "blind is fine".
- **Attachments do not survive reload** (decision 2). If that turns out to be annoying in
  practice, persistence is a new plan, not a scope expansion here.
- **A known upstream bug, adjacent but not ours:** `:jpeg`/`:webp` exports produce **PNG bytes
  mislabelled** with the requested mimetype — the Rust side hardcodes PNG
  (`render.rs:2419-2423`) while `export-image-uri` labels the blob from the requested type.
  Harmless for us (we want PNG), worth reporting separately.
