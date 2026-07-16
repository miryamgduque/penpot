# Phase 29 — Turn, mirror, stroke

**Status:** done (worktree caps-23-32; suite+live at merge)

The last shape attributes that are neither writable nor readable: **rotation**, **flip**, and
**stroke depth** (width, style). The read half is what makes this more than completionism —
`summarize-shape` reports no `:rotation` and the `look?` summary no stroke detail, so a rotated
shape reads as an axis-aligned box and a 4px dashed border reads as identical to a 1px solid
one. Phases 17/18 built read → copy → write for fills and effects; these attrs are the loop's
remaining blind spots (the write half of stroke was flagged in Phase 14's notes and shipped
without it).

Internals:

- Rotation: `dw/increase-rotation` ([transforms.cljs:555](../../../../../frontend/src/app/main/data/workspace/transforms.cljs))
  — sets **absolute** rotation by default (it computes the delta itself), `:delta? true` for relative
- Flips: `flip-horizontal-selected` / `flip-vertical-selected` (:1245/:1266, both take explicit ids)
- Stroke width/style: the same `update-shapes` assoc on `:strokes` that `modify_shape`'s stroke
  color already uses — today it hardcodes `width 1, solid, center`

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Rotation interactions: what does rotating a flex **child** do to its parent's layout, and a rotated **board** to render_board? Check `increase-rotation`'s modifier path settles async like resize does
- [ ] Read side: `:rotation` on `summarize-shape` only when non-zero (the truthy-only discipline); stroke `{width, style}` joins the `look?` stroke/fill summary, mirroring the write params 1:1 (the Phase 18 rule: read → copy → write with no translation)
- [ ] Flip semantics: flip is a toggle, not a settable state — decide `flipH: true` (toggle once, result reports the new state) vs exposing `flip-x`/`flip-y` attrs; check what the shape actually stores
- [ ] Stroke style enum: solid/dotted/dashed/mixed — confirm against the schema, not the plugin's list

## Checklist

- [ ] Write tests: absolute rotation set + read back; flip toggles and reports; stroke width/style write + `look?` read round-trip; stroke color guard (`token-only-colors`) still holds when width/style ride along
- [ ] Widen `modify_shape` (rotation, flipH/flipV, strokeWidth, strokeStyle); widen the read summaries
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: replicate a rotated, dashed-border shape from read_design alone — no render peeking
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — modify_shape params + read summaries
- `frontend/test/frontend_tests/data/agent_tools_test.cljs`

## Notes

Stroke caps and alignment stay out (near-zero value until someone draws diagrams); if a skill
ever wants arrowheads, they slot here. Zero corpus demand across the board — this phase exists
because the *read* gap breaks replicate-accurately, and the writes are the same one-line assocs
the reads make copyable.
