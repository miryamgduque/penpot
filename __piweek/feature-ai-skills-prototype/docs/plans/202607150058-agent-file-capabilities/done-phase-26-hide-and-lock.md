# Phase 26 — Hide and lock

**Status:** done (worktree caps-23-32; suite+live at merge)

Two shape flags the agent can neither set nor *see*: `:hidden` and `:blocked`. The write gap
is ordinary; the read gap is worse — `summarize-shape` reports neither, so a hidden shape reads
exactly like a visible one. The agent will reason about layout, overlap and spacing using
shapes that are not on screen, and `render_board` will contradict `read_design` with no
explanation the model can act on.

Demand is adjacency rather than symbol counts: `penpot-document-handoff`'s entire deliverable
lives in "one **hideable group**" (`hide` ×10, all that skill) — the group toggle is the
reviewer's move, but an agent that documents a design should at least be able to deliver the
annotation layer hidden, and must be able to *see* that a layer is hidden before reporting on
it. Locking is the symmetric flag with the same read problem.

Internals, all shipped: `update-shape-flags`
([shapes.cljs:444](../../../../../frontend/src/app/main/data/workspace/shapes.cljs) — note it
cascades `:blocked` to children), and the selection toggles at `:468`/`:476`. The plugin sets
both as plain properties (`shape.cljs:336,355`).

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] **Decide whether the agent respects the lock.** `:blocked` gates the UI (selection), not the events — today every agent tool will happily mutate a locked shape. That is arguably a bug in every existing tool, not a feature of this one: a user locks a layer to say *hands off*. Decide: reject mutations on blocked shapes across the problem-checkers (one shared check), or leave writes alone and only surface the flag. Leaning reject-with-message ("shape X is locked — ask the user, or unlock it deliberately with modify_shape locked:false")
- [ ] Surface: `hidden`/`locked` booleans on `modify_shape` vs a separate tool. Two booleans do not justify a new tool — lean `modify_shape`
- [ ] Read side: flags on `summarize-shape` only when truthy (same discipline as the variant keys); check `find_shapes` can filter on them

## Checklist

- [ ] Write tests: set/clear both flags; truthy-only read; blocked cascade matches the event's; (if chosen) mutation-on-locked rejected across tools
- [ ] `modify_shape` gains `hidden` and `locked`; `summarize-shape` reports them when true
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: hide a group, confirm read_design says so and render_board agrees; lock a shape and watch the (chosen) policy hold
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — params, read flags, (maybe) the shared lock check
- `frontend/test/frontend_tests/data/agent_tools_test.cljs`

## Notes

The lock-respect decision is the phase's real content and it retroactively touches every
mutating tool — like Phase 24's ownership plumbing, it gets more expensive the later it lands.
