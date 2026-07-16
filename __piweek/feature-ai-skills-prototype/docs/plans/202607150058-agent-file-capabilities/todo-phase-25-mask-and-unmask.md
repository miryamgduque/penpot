# Phase 25 — Mask and unmask

**Status:** todo

Clipping content to a shape — the circle avatar, the image cropped to a card's rounded corner,
the photo peeking through a logotype. Boards clip to a rectangle; a mask clips to *any* shape.
Today the agent has no reach: `group_shapes` creates only plain groups, and nothing can set or
clear `:masked-group`.

Corpus demand is zero (`mask`/`clip` never appear in the playbooks) — this phase is added by
request, with eyes open, because the capability is Phase-12-sized and its natural demand
(clipped images) arrives with Phase 17's image-fill work. The internals are shipped and
UI-called: `dwg/mask-group` / `dwg/unmask-group`
([groups.cljs:281,328](../../../../../frontend/src/app/main/data/workspace/groups.cljs)), and
the plugin wraps them the same way this tool would
([shape.cljs:1288,1305](../../../../../frontend/src/app/plugins/shape.cljs)).

Both events carry this plan's usual disease, silent filtering:

- `mask-group` drops shapes inside component copies (`:292`) and no-ops entirely on an empty
  result (`:295`) — a fully-filtered call reads as success.
- `unmask-group` keeps only groups/bools (`:341`) and then commits whatever survived — pass a
  rect's id and it succeeds having done nothing.
- `mask-group` also **changes the user's selection** (`dws/select-shapes`, `:324`) — the exact
  thing `duplicate_shape` deliberately refuses to do.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `mask-group`: the single-group fast path (masks an existing group in place) vs the create-a-group path; children get `:constraints-h/v :scale`; the group adopts the mask shape's selrect/transform
- [ ] **Confirm which child acts as the mask** — the event takes the first of `shapes-for-grouping`, which normalizes to z-order; verify whether that means bottom-most or top-most on canvas, and state it plainly in the tool description AND the result (the agent will otherwise mask with the wrong shape and see only "masked" back)
- [ ] Decide the selection question: the event selects the new mask group — suppress, or accept and disclose in the result (`duplicate_shape` chose suppress; consistency argues the same here, but the event offers no flag — check the cost of restoring selection after)
- [ ] Decide the tool surface: `mask_shapes` + `unmask_shapes` mirroring group/ungroup, vs params on the existing pair. Separate tools match the registry's grain and keep `group_shapes`' already-long description from growing a second concept
- [ ] Mirror Phase 12's problem-checkers: copy-parent rejection with names, variant checks, and for unmask a "not a masked group" rejection listing what the shape actually is

## Checklist

- [ ] Write tests: mask N shapes returns the group id and names the masking child; shapes in a copy are rejected with the fix named; unmask on a non-masked shape is rejected, not silently skipped; unmask restores a plain group
- [ ] Add `mask_shapes` / `unmask_shapes` to `tool-specs`, implement over `dwg/mask-group`/`unmask-group`, wire into dispatch
- [ ] `read_design`/`summarize-shape` surfaces `isMask` on masked groups — a mask the agent cannot see is a mask it will ungroup or reparent by accident
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: a circle-cropped image (or rect stand-in until Phase 17 lands image fills), then unmask it back
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — specs, problem-checkers, dispatch, read-side flag
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — rejection + round-trip tests

## Notes

**The mask-child rule is the whole UX of this tool.** Which shape clips is decided by order,
not by a parameter — so the tool description must teach it the way `create_variant` teaches
naming ("the FIRST/BOTTOM shape becomes the window; put the circle there and the image behind"),
and the result should echo which shape ended up as the mask so a mistake is visible in one
round-trip instead of after a render.

**Relationship to `ungroup_shapes`:** a masked group is still a group, so `ungroup_shapes`
already dissolves one (mask and all). `unmask_shapes` is the gentler verb — keep the group,
drop the clipping — and the two descriptions should point at each other.
