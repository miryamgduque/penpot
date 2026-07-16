# Phase 32 — Cut and combine

**Status:** todo

Boolean operations — union, difference, intersection, exclusion — are the one shape-*making*
primitive left out of the registry. `ungroup_shapes` can already **dissolve** a boolean (it
handles bools explicitly), so the registry currently ends a capability it cannot begin. The
honest ranking: weakest case of the sweep's survivors — icon geometry is booleans' main use
and Phase 23's SVG import covers it — but "cut a hole in this shape" has no SVG-import answer
when the shapes are already on the canvas.

Internals: `create-bool` ([bool.cljs:72](../../../../../frontend/src/app/main/data/workspace/bool.cljs)),
`change-bool-type` (:148), plus `group-to-bool`/`bool-to-group` (:119/:139). The plugin wraps
it as `createBoolean(boolType, shapes)`.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Read `create-bool`'s filters: minimum shape count, what shape types it accepts (text? images? boards?), what it silently drops — mirror in a problem-checker
- [ ] **Confirm which operand order means what.** Difference is not commutative; determine whether z-order or argument order decides who cuts whom, and teach it in the description the way Phase 25 teaches the mask-child rule
- [ ] Check the result: a bool is a container (`ungroup_shapes` dissolves it) — the result should say so, and `summarize-shape` already types it; verify `look?` reads its computed fill sensibly
- [ ] Decide whether `change-bool-type` rides along (cheap, same guards) or waits

## Checklist

- [ ] Write tests: each of the four ops; order semantics asserted; ineligible shapes rejected with the fix named; round-trip with ungroup_shapes
- [ ] Add `boolean_shapes` (op + shapeIds), implement, wire into dispatch
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: cut a circle out of a rect, render it, dissolve it back
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec, order-semantics guard, dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs`

## Notes

Zero corpus demand; added by decision alongside phases 29–31. If it fights for a slot, it
loses to everything demand-backed — but it is small, and it pairs with Phase 23 as the two
ways the agent makes non-rectangular geometry.
