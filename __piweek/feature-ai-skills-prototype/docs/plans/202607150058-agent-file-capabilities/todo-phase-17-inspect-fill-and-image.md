# Phase 17 — Inspect fills and image content

**Status:** todo

Asked to *"replicate the selected element as accurately as possible"*, the agent read the
design, rendered the board (agent-vision), and then reported the wall directly:

> To fully replicate it with exact accuracy, I would need access to:
> 1. **Fill/image content** — the current `mars` shape contains imagery/styling that I can see
>    visually but cannot directly inspect through the API

The report was accurate. `summarize-shape` ([agent_tools.cljs:186](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs)) returns `:id :name :type :x :y :width :height` — geometry only. A shape's `:fills` are never surfaced, so the agent cannot tell a solid from a gradient from an **image**, cannot read the fill colour it would need to copy, and has no handle on the raster at all. `create_shape`/`modify_shape` can *set* a **solid** hex ([:104, :120](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs)) — that is the whole of fill, read or write.

This is the read/inspect counterpart to Phase 14 (which *writes* style) and is orthogonal to Phase 15 (which reads *depth*, not paint). The image case is the novel part: images are wholly unreachable today.

## Before Start

- [ ] Re-read `summarize-shape` + `read-design` (agent_tools.cljs:166–193) — where a `:fills` summary hangs, and the JSON-size cost (agent.cljs 20k truncation) of adding it to every shape
- [ ] Confirm the `:fills` shape: solid (`:fill-color` / `:fill-opacity`), gradient (`:fill-color-gradient`), and image (`:fill-image` → `{:id :width :height :mtype :keep-aspect-ratio}`) — see `common/src/app/common/types/shape/attrs.cljc` and the fill schema
- [ ] Decide the read summary: emit a compact per-fill descriptor (`{:type "solid"|"gradient"|"image", :color …}` / `{:type "image" :imageId … :width … :height …}`) rather than the raw attr — the agent needs to *recognize and copy*, not round-trip internals
- [ ] **Image write is a separate cost.** Setting an image fill needs the media pipeline (upload/reference), not just an attr write — scope it explicitly: read-only inspection may be enough for "replicate as accurately as possible" (the agent copies solids/gradients precisely and *names* the image it can't reproduce), or include image-fill write via the existing media-object flow. Confirm with the user before building the write half.
- [ ] Check the `color-violation` guard (agent_tools.cljs:255) — a gradient/solid fill written here must pass the same `token-only-colors` gate as `modify_shape` (see Phase 14's identical note)

## Checklist

- [ ] Write tests: read a shape with a solid, a gradient, and an image fill; assert the summary distinguishes them and carries the copyable values / image id
- [ ] `read_design` surfaces each shape's fills (compact descriptors)
- [ ] (Scope-gated) `modify_shape`/`create_shape` accept a gradient fill; image-fill write only if confirmed in scope
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv)
- [ ] Preview review: point the agent at an image-filled shape and confirm it reports the fill type + copies a sibling solid/gradient accurately

## Notes

- Pairs with Phase 18 (effects) — together they are "read a shape's look", the inspection side of
  the replicate-accurately request. Keep the read summaries compact; breadth × depth (Phase 15) ×
  every paint attr is exactly the 20k-truncation risk the plan already flags.
