# Phase 12 — Group and ungroup

**Status:** done

18 mentions across 7 skills. `nest_shape` moves a shape into an *existing* parent; there is no
way to create a group around shapes that don't have one. `penpot-rename-layers` and
`penpot-migrate` both restructure layer trees, which is grouping work by definition.

## Before Start

- [x] Re-read `group-shapes` — `(id ids & …)`; `id` may be nil (`d/nilv id (uuid/next)`), so pre-generating it works, as in Phase 01
- [x] Re-read `ungroup-shapes` (:219)
- [x] Confirm `change-selection?` defaults to `false` — **it does**; left alone, so the user's selection never moves
- [x] Check the component-copy restriction — `ctn/has-any-copy-parent?` is the internal equivalent; **the events filter, they do not reject** (see Notes)

## Checklist

- [x] Write tests for validation
- [x] Add `group_shapes` / `ungroup_shapes` specs to `tool-specs`
- [x] Implement, wire into dispatch
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: grouped two rects into a real `:group` (both reparented), then ungrouped it away
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Notes — what execution found

**Two of the plan's four expected validations were wrong.** Both were guesses about Penpot's
rules, and both were cheap to check:

- ~~"fewer than 2 ids → needs at least 2 shapes"~~ — **Penpot groups a single shape happily**
  (⌘G on one shape makes a group). Requiring two would have rejected something legal.
- ~~"ungroup target is not a group → *X is a board, not a group*"~~ — **`ungroup-shapes`
  handles frames**, via `remove-frame-changes` (`groups.cljs:236`). Ungrouping a board is legal
  and does exactly what the planned message sarcastically suggested ("did you mean to delete it
  and keep the children?"). The real rule is `group | board | boolean`; the rejection now says
  that.

**Both events filter silently, then `(when-not (empty? …))`** — the pattern this whole plan
exists to close. `group-shapes` drops copy-children *and variants* (`:195-200`);
`ungroup-shapes` drops copy-children, components and variant containers (`:242-246`). A
fully-filtered call does nothing and returns success. Both now reject up front, mirroring the
events' own filters so anything accepted here is accepted there.

**The rules the events encode are worth reading as domain facts, and they became the messages:**

- `groups.cljs:244` carries its own comment — *"components can't be ungrouped"*.
- Ungrouping a **variant container** would destroy the set. Verified live against a set built in
  Wave 1: `"Ticket" (…) is a variant container — ungrouping one would destroy the set; delete it
  instead if that is the intent`.
- Grouping a **variant** is refused because the set owns its own structure.

**Group id is knowable up front**: `group-shapes` takes the id and only defaults it with
`d/nilv`, so the tool pre-generates one and returns it without awaiting — the same trick as
`create_variant`.

**Verified live**: two loose rects → a real `:group` with both children reparented; then
ungrouped, and the group is gone. Rejections confirmed for a rect (`only a group, a board or a
boolean can be ungrouped`), a variant container, and an empty call.

## Notes — from planning

**Groups are not boards.** A group has no layout, no clip, no fill — and the agent will conflate
them, since in Figma an auto-layout frame does both jobs. Now that Phase 06 has landed, "group
these and space them out" is a *board with a flex layout*, not a group. Both the tool
description and the success note say so, so the agent is told before it groups and after — or it
would group first and then discover it cannot lay out what it made.
