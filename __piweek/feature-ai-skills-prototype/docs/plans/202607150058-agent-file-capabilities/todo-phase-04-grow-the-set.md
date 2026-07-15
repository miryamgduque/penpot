# Phase 04 — Grow the set

**Status:** todo

`create_variant` builds a set from components that already exist. `add_variant` extends a set
that already exists — "add a Disabled state to the Button" without rebuilding it. It is the
cheapest phase here (`dwv/add-new-variant` does the work) and the most-used one once the
agent starts maintaining component libraries rather than creating them.

## Before Start

- [ ] Phases 01–03 merged
- [ ] Re-read `dwv/add-new-variant` (:347) — confirm the arity `(shape-id)` / `(shape-id multiselect?)` and what `shape-id` refers to (an **existing member's** main-instance id, not the container id)
- [ ] Re-read `generate-add-new-variant` in `common/src/app/common/logic/variants.cljc` (:16) — it delegates to `cll/generate-duplicate-component`, so the new member is a **copy of the given member**, not an empty frame

## Checklist

- [ ] Write tests for validation
- [ ] Add the `add_variant` spec to `tool-specs`
- [ ] Implement `add-variant`
- [ ] Wire into the `execute-tool` dispatch `case`
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: add a member to an existing set, name its axis value, confirm it switches
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Implementation

The semantics worth surfacing in the description: the new variant is a **duplicate of the
member you name**, so which member you pass matters — duplicating the closest sibling means
less to modify afterwards.

```clojure
{:name "add_variant"
 :description
 (str "Adds a new variant to an existing set by duplicating one of its members. "
      "Pass the member closest to what you want — the copy inherits its content, "
      "so you modify the difference. The new variant starts with the same property "
      "values as its source: set them with set_variant_property, or it will be "
      "ambiguous with the member it was copied from. Asynchronous: verify with "
      "read_design.")
 :input-schema
 {:type "object"
  :properties {:shapeId {:type "string"
                         :description "main-instance id of an existing member to copy"}}
  :required ["shapeId"]}}
```

Validation:

| Condition | Message |
|---|---|
| shape not found | `add_variant: no shape with id X on this page` |
| not `ctc/is-variant?` | `add_variant: shape "Card" (X) is not part of a variant set — use create_variant to make one from 2+ main components` |
| container id passed instead of a member | `add_variant: X is the variant container — pass one of its members to copy (see read_design)` |

That third row matters: `read_design` (Phase 02) reports both `variantId` and member
`componentId`s, and the container id is the more prominent of the two. The agent will reach
for it. Catch it and redirect rather than no-op.

The duplicate inherits its source's property values, so the set momentarily holds two members
with identical values — Penpot flags this via `:variant-error`. Surface that in the result so
the agent knows a `set_variant_property` call is expected next, rather than treating the add
as complete:

```clojure
(rx/of {:note (str "variant added as a copy of \"" src-name "\" — it currently shares that "
                   "member's property values. Give it its own value with "
                   "set_variant_property.")})
```

Getting the new member's id back is the open question: `add-new-variant` does not take an
id-ref the way `dwl/add-component` does. Check whether it exposes one before promising an id
in the result. If it does not, return the source id plus the note above and let the agent find
the new member with `read_design` — do **not** guess an id, and do not add an id-ref
parameter to a shared event just for this tool without discussing it first.

## Tests

- [ ] rejects a non-variant shape, message mentions `create_variant`
- [ ] rejects the container id, message says to pass a member
- [ ] accepts a valid member

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec, `add-variant`, dispatch entry
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — validation tests

## Notes

`duplicate-or-add-variant` (:533) and `add-component-or-variant` (:498) are UI shortcut
dispatchers — they branch on selection state to serve a keyboard shortcut. Do not call them
from a tool; the agent should express intent explicitly, and an ambiguous dispatcher is how
you get a variant when you asked for a duplicate.
