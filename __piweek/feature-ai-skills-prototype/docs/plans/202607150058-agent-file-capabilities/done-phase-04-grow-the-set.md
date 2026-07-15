# Phase 04 — Grow the set

**Status:** done

`create_variant` builds a set from components that already exist. `add_variant` extends a set
that already exists — "add a Disabled state to the Button" without rebuilding it. It is the
cheapest phase here (`dwv/add-new-variant` does the work) and the most-used one once the
agent starts maintaining component libraries rather than creating them.

## Before Start

- [x] Phases 01–03 merged
- [x] Re-read `dwv/add-new-variant` (:347) — **the plan was wrong about its input; it takes the container too** (see Notes)
- [x] Re-read `generate-add-new-variant` — **wrong about the copied values too** (see Notes)

## Checklist

- [x] Write tests for validation
- [x] Add the `add_variant` spec to `tool-specs`
- [x] Implement `add-variant`
- [x] Wire into the `execute-tool` dispatch `case`
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: full matrix built live, design tab reads `Size: Small, Large` / `State: Idle, Hover`
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

## Notes — what execution found

Three of this phase's assumptions were wrong. All three were cheap to check and would have
been expensive to ship.

**1. The container id is valid input — the plan wanted to reject it.** `add-new-variant:360`
does `(if (ctc/is-variant-container? shape) (get objects (last (:shapes shape))) shape)` — pass
a container and it resolves to the primary variant. The planned message ("X is the variant
container — pass one of its members") would have rejected the *most natural* call the agent
could make, and the one `read_design` most prominently offers. Both forms are now accepted and
the description says what each means.

**2. The new variant does not inherit its source's value.** The plan predicted two members with
identical values and a `:variant-error` to surface. Reality: `prop-num` is
`(dec (count (:variant-properties component)))` (`:373`), so the copy keeps every property
*except the last*, which gets a fresh placeholder — `Value 3` on a 2-member set. There is no
ambiguity and no error to report. Live, growing a `Size × State` set gave
`Size=Small, State=Value 2`: same size, placeholder state. Penpot's model is "the new variant
differs on the last axis", which is a better default than the plan imagined.

**3. `Value N` is real, just from here.** Phase 01 corrected the claim that `create_variant`
seeds `Value 1`. It doesn't — but **`add_variant` does**, via this path. The original claim was
right about the string and wrong about the tool.

**The id question, answered.** `add-new-variant` generates `new-shape-id` internally (`:371`)
and exposes no id-ref, so it cannot be passed one — but it ends by selecting the new shape
(`:392`). The tool diffs the selection across the emit and reports the id only when it actually
changed: an unchanged selection means "no id to report", never a wrong id. Verified live —
`shapeId` comes back real.

**Wave 1 end-to-end, one run, agent tools only:**

```
create_shape ×2  "Ticket / Small / Idle", "Ticket / Large / Idle"
create_component ×2
create_variant   -> set "Ticket", TWO axes (from the path), no naming hint
add_variant      -> third member, Size=Small, State=Value 2
set_variant_property ×3 -> axes named Size / State, placeholder -> Hover
```

Result in Penpot's own design tab: **`Ticket` / `Size: Small, Large` / `State: Idle, Hover`.**
That is the transcript's original request — a card with a real variant matrix — built without
one fake layer name.

## Notes — from planning

`duplicate-or-add-variant` (:533) and `add-component-or-variant` (:498) are UI shortcut
dispatchers — they branch on selection state to serve a keyboard shortcut. Do not call them
from a tool; the agent should express intent explicitly, and an ambiguous dispatcher is how
you get a variant when you asked for a duplicate.
