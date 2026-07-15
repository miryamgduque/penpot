# Phase 22 — Grid layout

**Status:** todo

`set_layout` is flex-only. The skills' escape hatch from absolute positioning names two
options — *"Wrap them in a flex/**grid** Board; order by append + gaps/align"* — and
`addGridLayout()` appears in the corpus alongside `addFlexLayout()`. Flex was rightly built
first (its demand dwarfed everything), but a card gallery, a pricing table or a dashboard is a
grid, and today the agent either fakes it with wrapped flex or falls back to hand-placed x/y —
the exact behavior Wave 2 existed to end.

The internals already speak grid: `create-layout-from-id` takes `:grid` and initializes
tracks/cells ([shape_layout.cljs:61-96](../../../../../frontend/src/app/main/data/workspace/shape_layout.cljs)),
and row/column add/remove/reorder events exist right below it (:328, :361, :435).

Ranked last of the new phases: real demand, but a fraction of flex's, and wrapped flex covers
many gallery cases. Re-check the tally at Before Start — if it hasn't moved, this phase can
wait indefinitely.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions); re-tally grid-layout demand in the current aikit import (distinguish it from the 4px *spacing* grid, which dominates the raw count)
- [ ] Read the grid half of `shape_layout.cljs`: track model (`:layout-grid-rows/columns`, cells, auto vs fixed vs fr), `assign-cells` / `reorder-grid-children`
- [ ] Read the plugin's `grid.cljs` proxy for the public vocabulary skills expect (`addRow`, `addColumn`, cell placement)
- [ ] Decide the surface: extend `set_layout` with `type: flex|grid` + track params, vs a separate `set_grid_layout`. Extending keeps one "give this board a layout" tool (and one problem-checker); separate keeps each spec readable. Decide from the param shape — if grid tracks swamp the flex spec, split
- [ ] Decide the minimum: tracks + gaps + padding + auto-placement likely covers the skills' use; explicit cell assignment (`set_layout_child` grid params: row/column/span) may be a follow-up. Do not build cell surgery nobody asked for
- [ ] Check `set_layout_child`'s problem-checker: `ctl/any-layout?` already accepts grid parents — margins/align on a grid child may Just Work; verify rather than assume

## Checklist

- [ ] Write tests: grid on a board with N columns; rejection messages for non-boards mirror flex's; children reflow into cells
- [ ] Implement (spec + problem-checker + dispatch, whichever surface was chosen)
- [ ] Tokens: gaps/padding on a grid accept spacing tokens the same way flex's do — verify `apply_tokens` reaches them
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: a 3-column card gallery that stays a gallery when a fourth card is added
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — grid surface
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — track + rejection tests

## Notes

The Phase 06/07 notes are the map here: same silent-no-op hazards, same "names from
`layout.cljc`, not the plugin's aliasing" rule, same async-verify story. Grid is flex's phase
pair with a different track model, not a new kind of work.
