# Phase 12 — Group and ungroup

**Status:** todo

18 mentions across 7 skills. `nest_shape` moves a shape into an *existing* parent; there is no
way to create a group around shapes that don't have one. `penpot-rename-layers` and
`penpot-migrate` both restructure layer trees, which is grouping work by definition.

## Before Start

- [ ] Re-read `group-shapes` ([groups.cljs:186](../../../../../frontend/src/app/main/data/workspace/groups.cljs)) — `(id ids & {:keys [change-selection?]})`. **Note the leading `id`**: the caller supplies the new group's id, so the tool can return it without awaiting (same trick as `create_variant` in Phase 01)
- [ ] Re-read `ungroup-shapes` (:219) — `(ids & {:keys [change-selection?]})`
- [ ] Confirm `change-selection?` defaults to `false` and leave it there — an agent tool should not move the user's selection out from under them
- [ ] Check the component-copy restriction: the plugin API refuses `group`/`ungroup` inside a component copy (`u/inside-component-copy?`). Find the internal equivalent and whether the event enforces it or the caller must

## Checklist

- [ ] Write tests for validation
- [ ] Add `group_shapes` / `ungroup_shapes` specs to `tool-specs`
- [ ] Implement, wire into dispatch
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: group 3 shapes, confirm one group in the layers tree; ungroup, confirm children survive in place
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Notes

Expected validation, to confirm against the events rather than assume:

| Condition | Message |
|---|---|
| fewer than 2 ids | `group_shapes: needs at least 2 shapes` |
| shapes have different parents | (check — does `group-shapes` handle this, or produce something odd?) |
| inside a component copy | `group_shapes: X is inside a component copy — its structure is owned by the main component` |
| ungroup target is not a group | `ungroup_shapes: X is a board, not a group — did you mean to delete it and keep the children?` |

**Groups are not boards.** A group has no layout, no clip, no fill — Penpot treats them
differently and the agent will conflate them, since in Figma an auto-layout frame does both
jobs. Once Phase 06 lands, "group these and space them out" is a *board with a flex layout*,
not a group. Say so in the tool description, or the agent will group first and then discover it
cannot lay out what it made.
