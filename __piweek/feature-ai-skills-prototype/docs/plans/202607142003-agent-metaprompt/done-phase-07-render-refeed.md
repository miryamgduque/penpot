# Phase 07 — The agent must see

**Status:** done — **closed as superseded.** No code shipped here. Superseded by
[Agent vision — let the model see, both ways](../202607150027-agent-vision/), by the user's call
on 2026-07-15. That plan's README reserved this decision ("keep both, decide later… Do not let
both plans build the render tool. Whoever gets there first wins; the other closes"); this is the
decision.

## Why that plan wins

It is not merely a bigger version of this phase — it is better grounded, and it corrects this
phase on two points of fact:

- **The feasibility spike this phase opened with is already answered.** This phase said "Can we
  export a board/shape to PNG from native CLJS? Timebox it — if expensive, split Phase 07 into
  its own story." That exploration has since happened: `wasm.api/render-shape-pixels(shape-id,
  scale)` returns PNG bytes **synchronously** — no network, no exporter service, no promise —
  rendering to a dedicated export surface so off-screen boards work. The expensive path this
  phase feared (`rp/cmd! :export` → the headless `exporter` service) is exactly the one to avoid.
  Splitting it into its own story is precisely what happened, which is the outcome this phase
  named as the good one.
- **`render_region` is the wrong name, and this phase invented it.** The WASM API takes
  `(shape-id, scale)` — it renders **a shape, not a rectangle**. Arbitrary-region granularity
  does not exist for free; it would need a synthetic shape or the viewport-only snapshot path.
  The successor builds **`render_board`** and drops "region" as out of scope. Had this phase run
  as written, it would have shipped a tool named for a capability the platform does not have.

It is also strictly broader for the same foundation: an image block in the canonical model plus
both codecs unlocks **two** features, not one — the agent's own screenshot *and* user-attached
reference images. This phase only ever scoped the first.

## What this phase contributes to its successor

Not nothing — three things worth carrying, two of which the successor already has:

1. **The structural-tools caveat** (already carried there): our tools place shapes at explicit
   coordinates, so the agent may already know where things are because it put them there. The
   interesting case is a design it did *not* create, or a WASM layout that settled differently
   than requested. Design the prompts around that case or the experiment trivially favours "blind
   is fine".
2. **Cost honesty** (already carried there): if seeing costs 10× and improves quality 5%, the
   honest answer is "gate it behind an explicit user action", not "always on".
3. **A correction this phase learned the hard way, and the successor should inherit.** This phase
   proposed scoring blind-vs-seeing with *"violations (`audit_file`), plus a human judgement"*.
   **Phase 06 proved the first half of that is worthless for quality**: `audit_file` scores the
   floor (no default names, no raw hex) and is structurally blind to the ceiling. Both a good and
   a mediocre design score 0. The successor's Phase 07 must **not** lean on a violations count as
   its quality metric — it needs a judge model, a convention-aware check, or an honest human read.
   This is the single most useful thing this phase can hand over, and it is written into the
   successor's plan.

## Files

None.
