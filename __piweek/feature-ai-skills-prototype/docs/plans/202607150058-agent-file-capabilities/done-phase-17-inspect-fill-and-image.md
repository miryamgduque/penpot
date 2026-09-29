# Phase 17 — Inspect fills and image content

**Status:** done

Asked to *"replicate the selected element as accurately as possible"*, the agent read the
design, rendered the board (agent-vision), and then reported the wall directly:

> To fully replicate it with exact accuracy, I would need access to:
> 1. **Fill/image content** — the current `mars` shape contains imagery/styling that I can see
>    visually but cannot directly inspect through the API

The report was accurate. `summarize-shape` ([agent_tools.cljs:186](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs)) returns `:id :name :type :x :y :width :height` — geometry only. A shape's `:fills` are never surfaced, so the agent cannot tell a solid from a gradient from an **image**, cannot read the fill colour it would need to copy, and has no handle on the raster at all. `create_shape`/`modify_shape` can *set* a **solid** hex ([:104, :120](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs)) — that is the whole of fill, read or write.

This is the read/inspect counterpart to Phase 14 (which *writes* style) and is orthogonal to Phase 15 (which reads *depth*, not paint). The image case is the novel part: images are wholly unreachable today.

## Before Start

- [x] Re-read `summarize-shape` + `read-design` — and measured the JSON cost: fills on every shape was **+11% of the 20k budget** (see Notes)
- [x] Confirm the `:fills` shape — solid (`:fill-color`/`:fill-opacity`), gradient (`:fill-color-gradient`), image (`:fill-image` → `{:id :width :height :mtype :name}`); exactly one per fill (`cfl/valid-fill-attrs`)
- [x] Decide the read summary — compact descriptors, per the phase's own instruction
- [x] **Image write scope — confirmed with the user: gradient + image write both in scope.** Resolved without the media pipeline (see Notes)
- [x] Check the `color-violation` guard — **a gradient is several raw colours; every stop is now guarded** (see Notes)

## Checklist

- [x] Write tests: solid, gradient and image reads; the summary distinguishes them and carries the copyable values / image id
- [x] `read_design` surfaces fills (compact descriptors) — **on the selection and find_shapes hits, not the broad list** (see Notes)
- [x] `modify_shape`/`create_shape` accept gradient **and** image fills
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: read → copy → write round-trips a gradient faithfully; an image fill reuses its raster by id
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## Notes — what execution found

**Image write did not need the media pipeline.** The phase scoped it as a separate cost
("upload/reference, not just an attr write") and it is — *for a new raster*. But "replicate this
element" never needs a new one: it needs **the same** one. An image fill is a reference to a
media object by id, and `read_design` now hands the agent that id. So image write is
`fill: {type: "image", imageId: …}` — no upload, no media flow. Uploading a *new* image is still
out, and still its own phase if a demo wants it.

**The write shape mirrors the read shape, so the loop closes.** `fill` accepts exactly the
descriptor `fill-summary` emits, which means read → copy → write round-trips with no
translation:

```
read GradBox   → {"type":"gradient","gradient":"linear","stops":["#ff0000","#0000ff"]}
write that back → CopiedFill reads back byte-identical      ← verified live
```

A bare hex string still works — it was the shipped param, and the agent may already use it.

**The guard was the sharp edge, exactly as the phase predicted.** A gradient is several raw
colours in a trench coat: `input-colors` previously saw `fill` as a *string*, so every stop
would have walked straight past `token-only-colors`. `fill-colors` now unpacks them. Verified
live under enforcement, and the third case is the one that matters:

```
solid  "#ff0000"                        → blocked
gradient ["#ff0000", …]                 → blocked
gradient ["#6366f1"(token), "#abcdef"]  → blocked on the SECOND stop   ← the real test
image fill                              → allowed (carries no colour)
```

A gradient whose opening stop is a legitimate token would have smuggled the rest through. This
is Phase 14's "guard by construction" paying for itself: gradient stops joined the guard by
extending one collector, not by remembering to add a third `color-violation` call.

**Payload: the phase's own warning came true, and was acted on.** Fills on every shape took
`read_design` from 68% → **79%** of the 20k budget (46 of 58 shapes carry a fill), with Phase 18's
effects still to come — "breadth × depth × every paint attr", exactly as the notes predicted.
Resolved by putting paint where paint is wanted: the **selection** (which is what "replicate
this" means) and **find_shapes** hits (which is what drill-in is for). The broad list is back to
geometry + `childCount`, and the payload back to **68%**. The constant-cost orientation call
stays about *what is here*; *what it looks like* is one query away.

**Offsets matter.** A gradient's stops need spreading across the axis — built naively they all
sit at offset 0 and the "gradient" is a flat colour. Pinned by a test (`[0 0.5 1]` for three
stops).

## Notes — from planning

- Pairs with Phase 18 (effects) — together they are "read a shape's look", the inspection side of
  the replicate-accurately request. Keep the read summaries compact; breadth × depth (Phase 15) ×
  every paint attr is exactly the 20k-truncation risk the plan already flags.
