# Phase 22 — Grid layout

**Status:** done

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

- [x] Re-tally grid demand — real but a fraction of flex's; the value is the intent ("3-column gallery") that wrapped flex fakes badly
- [x] Read the grid half of `shape_layout.cljs`: `initial-grid-layout`, `calculate-params` (infers tracks from child *positions*), `assign-cells`, `create-cells`, `reorder-grid-children`
- [x] Read the track model: `grid-track-types` `#{:percent :flex :auto :fixed}`, `default-track-value` = `{:type :flex :value 1}` (a `1fr`)
- [x] **Decide the surface: extend `set_layout` with `type: flex|grid`** — grid adds only `type` + `columns`, so it does not swamp the flex spec, and one "give this board a layout" tool keeps one problem-checker
- [x] Decide the minimum: tracks + auto-flow; NO explicit per-cell row/column/span surgery
- [x] Check `set_layout_child` on grid parents — `ctl/any-layout?` already accepts grid, so margins/align Just Work

## Checklist

- [x] Write tests: grid is a valid type; columns → track vector; a CSS-flavoured type is rejected; non-boards mirror flex
- [x] Implement — `type` + `columns` on `set_layout`, `grid-tracks`, `rebuild-grid`
- [x] Tokens: gaps/padding on a grid accept spacing tokens the same way flex's do (same `layout-gap`/`layout-padding` keys — unchanged from Phase 09)
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] **Preview review: a 3-column gallery that STAYS a gallery when a fourth card is added** — 3×1 → 3×2 (see Notes)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — grid surface
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — track + rejection tests

## Notes — what execution found

**Two real bugs, both found live, neither visible in unit tests.** The tool "worked" (real
`:grid` layout, 3 columns, right gaps) while producing a *wrong* result — exactly the class this
plan exists to catch, and only a live check caught it.

**Bug 1: `columns: 3` gave 3 columns but stacked every child in column 1.** `create-layout-from-id`'s
auto-grid infers tracks from child *positions* (`calculate-params`), so three near-aligned
children collapsed into one column with cells at (1,1)(1,2)(1,3) — then my override *widened the
track vector* under those already-placed cells. `rebuild-grid` fixes it: reset tracks and cells,
`create-cells` a clean N×⌈kids/N⌉ area, `assign-cells` to flow the children across. After:
cards in columns 1/2/3, three distinct x positions.

**Bug 2 — the phase's own acceptance, and a latent gap older than this phase: a child added to a
laid-out board didn't flow into it.** `create_shape` committed the add-object change and stopped;
it never told the layout. So the fourth card landed at its raw x/y, orphaned, overlapping the
first. The fix distinguishes the two layout kinds, because `:layout/update` only reflows
*positions*:

- **flex** → `:layout/update` (the child appends into the flow)
- **grid** → `assign-cells` first (it auto-adds a track for the orphan and gives it a cell),
  then `:layout/update`

Verified: adding a fourth card grew the grid **3×1 → 3×2**, cell `c1r2` — it stays a gallery.

**This helps flex too, and closes a Phase 06/07 blind spot.** Those phases applied the layout
*after* adding children, so they never exercised "add a child to an existing layout" — which
did nothing until now. Any flex board the agent builds incrementally now reflows on each new
child.

**Surface: `type` on `set_layout`, not a separate tool.** Grid added only `type` + `columns`, so
it did not swamp the flex spec, and the single problem-checker gained one `enum-problem` line.
`flexbox`/`css-grid` guesses are rejected naming `flex, grid`.

**No cell surgery, as the phase required.** `columns` is the only track control; explicit
row/column/span assignment is not offered. `assign-cells` is Penpot's own auto-flow primitive,
not hand-rolled placement.

**Pre-existing unrelated failures noted, not touched:** two tests in `workspace_skill_gen_test`
(`clamp-category` mapping, `parse-generation` mode) fail against another session's `skill_gen.cljs`
(commits `a5a77e8a85`, `f92320faf5`) — outside this plan. Filed as a background task, left for
that code's owner.

## Notes — from planning

The Phase 06/07 notes are the map here: same silent-no-op hazards, same "names from
`layout.cljc`, not the plugin's aliasing" rule, same async-verify story. Grid is flex's phase
pair with a different track model, not a new kind of work.
