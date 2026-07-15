# Phase 03 — Name the axes

**Status:** todo

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

- [ ] Phases 01 and 02 merged
- [ ] Re-read in `variants.cljs`: `update-property-name` (:100), `update-property-value` (:147), `add-new-property` (:261), `remove-property` (:191)
- [ ] Confirm the positional contract in `variant_properties.cljc` still holds — properties addressed by `pos`, uniform across the set

## Checklist

- [ ] Write tests for property-index resolution and validation
- [ ] Add the `set_variant_property` spec to `tool-specs`
- [ ] Implement `set-variant-property`
- [ ] Wire into the `execute-tool` dispatch `case`
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: rename an axis to `Size`, set values `Compact` / `Large`, confirm the design-tab switcher reads correctly
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

## Notes

Order-of-operations gotcha for the rename+value combined call: resolve `pos` from the
**current** name first, then rename, then set the value at that `pos`. Getting this backwards
means the rename invalidates the lookup. Pin the chosen order with the test above.

`remove-empty-properties` (:214) exists for cleanup after a set is edited down. Out of scope,
but relevant if a "clean up this variant set" ask appears later.
