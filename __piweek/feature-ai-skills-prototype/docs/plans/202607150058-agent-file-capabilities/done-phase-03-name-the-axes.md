# Phase 03 — Name the axes

**Status:** done

After Phase 01 the set is real but its axis is anonymous. A designer wants `Size = Compact`,
not `Property 1 = Tag`. This phase closes the gap between "a real variant set" and "the
variant set they asked for".

> **Corrected by Phase 01 execution — read this before planning the work.** This phase was
> written believing properties seed as `Property 1` / `Value 1`. Only the axis *name* is a
> placeholder. Each member's **value is taken from its own component name**: two components
> named `Tag` and `Tag Solid` combine into `Property 1: Tag, Tag Solid` — verified live.
>
> So the weight shifts. **Renaming the axis** (`update-property-name`, set-wide) is the
> capability that's actually missing. **Setting a value** (`update-property-value`,
> per-member) matters only when the source component names were poor — which, given
> `create_component` renames boards to "Component" (see Phase 01 notes), may be common
> enough to keep. Confirm which is true against a real agent transcript before building
> both.

## Before Start

- [x] Phases 01 and 02 merged
- [x] Re-read `update-property-name` (:100) and `update-property-value` (:147) — found a third silent no-op, see Notes
- [x] Confirm the positional contract — holds; properties addressed by `pos`, uniform across the set

## Checklist

- [x] Write tests for property-index resolution and validation
- [x] Add the `set_variant_property` spec to `tool-specs`
- [x] Implement `set-variant-property`
- [x] Wire into the `execute-tool` dispatch `case`
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: design tab reads `Size | Compact, Tag Solid`, live
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Implementation

The API design decision: **the agent addresses properties by name, not by index.**
Internally properties are positional (`pos`), but exposing an index invites off-by-one
errors and desynchronized sets. The tool resolves name → `pos` and rejects unknown names.

Two operations, one tool:

- **Rename an axis** (set-wide): `update-property-name variant-id pos new-name` — affects
  every member, by definition.
- **Set a value** (per-member): `update-property-value component-id pos value` — affects one
  component.

```clojure
{:name "set_variant_property"
 :description
 (str "Names a variant axis and sets a member's value on it — turns the default "
      "\"Property 1 = Value 1\" into e.g. \"Size = Compact\". Renaming an axis "
      "affects every member of the set (properties are uniform by design); a value "
      "applies to the one component given. Create the set with create_variant first. "
      "Asynchronous: verify with read_design.")
 :input-schema
 {:type "object"
  :properties {:variantId {:type "string" :description "the variant container id"}
               :property {:type "string" :description "axis name, e.g. Size"}
               :rename {:type "string" :description "optional: rename this axis"}
               :componentId {:type "string" :description "required when setting a value"}
               :value {:type "string" :description "e.g. Compact"}}
  :required ["variantId" "property"]}}
```

Validation, same standard as Phase 01 — reject before emitting, name the fix:

| Condition | Message |
|---|---|
| `variantId` is not a container | `set_variant_property: X is not a variant container — call create_variant first` |
| unknown `property` | `set_variant_property: no axis named "Size" on this set (has: Property 1) — add it with add_variant_property, or rename an existing axis with rename` |
| `value` without `componentId` | `set_variant_property: setting a value needs componentId — a value belongs to one member, not the whole set` |
| `componentId` not in this set | `set_variant_property: component X is not a member of variant set Y` |

**Adding a new axis is deliberately not in this tool.** `add-new-property` mutates every
member and is a different shape of operation; folding it in behind an implicit "unknown name
creates it" would make typos silently restructure the set. If the need appears, add a
separate `add_variant_property` tool — flagged here, not built.

Go through the `dwv/` events, never `update-shapes` directly: each event rewrites the
`:variant-name` cache on the main shape alongside the component's `:variant-properties`.
Bypassing them desynchronizes the two, and `:variant-name` is what the UI displays.

## Tests

- [ ] name → `pos` resolution finds the right index
- [ ] unknown axis name is rejected, message lists the axes that exist
- [ ] `value` without `componentId` is rejected
- [ ] a `componentId` outside the set is rejected
- [ ] rename + value in one call resolves the index before the rename (or document the order explicitly)

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec, `set-variant-property`, dispatch entry
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — resolution and validation tests

## Notes — what execution found

**A third silent no-op, and the reason positions are resolved the way they are.**
`update-property-name` guards on `valid-pos?` (`variants.cljs:131`) and emits nothing when
the index is out of range — no error, no event. It reads the property list from **`(last
(find-variant-components …))`**, so `variant-facts` reads the axes from that same component:
a pos the tool resolves is a pos the event accepts. Resolving against any other member would
be a coin flip if the set ever diverged.

**The order-of-operations gotcha is real and now pinned by a test.** `axis-pos` resolves from
the *current* name, before any rename is emitted. Verified live in one call: `Property 1` →
`Size` set-wide **and** member 0's value → `Compact`, in the same request. Had the index been
resolved after the rename, the value update would have silently no-op'd — the failure this
whole plan exists to prevent, and the one an integration test would never have caught because
it looks like success.

**"nothing to do" was added beyond the plan.** A call with neither `rename` nor `value` would
emit nothing and return success, teaching the agent the axis was named when it wasn't. Now
rejected explicitly.

**Live proof**: design tab reads `Size | Compact, Tag Solid` after the call, and all four
rejections fire with actionable text — notably `no axis named "Size" on this set (it has:
Property 1) — pass one of those`, which converts the agent's most likely guess into a correct
retry.

**`update-property-value` was kept**, despite the Phase 01 finding that values arrive
meaningful. It earns its place for a different reason than planned: not because values start
as junk, but because the *source component names* are junk (everything is "Component"), so
values inherit that. Until the naming phase lands, per-member values are the only way to make
a set readable.

`add-new-property` / `remove-property` / `remove-empty-properties` stay out of scope — a
second axis is a real ask ("Size × State") but wants its own phase, and an unknown-name-creates
-an-axis shortcut would let a typo restructure the set.

## Notes — from planning

`remove-empty-properties` (:214) exists for cleanup after a set is edited down. Out of scope,
but relevant if a "clean up this variant set" ask appears later.
