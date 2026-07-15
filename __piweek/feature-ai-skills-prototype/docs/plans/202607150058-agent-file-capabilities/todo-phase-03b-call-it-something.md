# Phase 03b — Call it something

**Status:** todo

Added during Wave 1 execution, not in the original plan. Phase 02's live payload is the
argument — this is what `read_design` actually returns today, verbatim:

```json
{"variantId":"…","name":"Component",
 "members":[{"name":"Component","properties":[{"name":"Size","value":"Compact"}]},
            {"name":"Component","properties":[{"name":"Size","value":"Tag Solid"}]}]}
```

Every set and every member is called "Component", because `create_component` renames the
source board (`dwl/add-component`). Only the property values distinguish them. A designer
asking for a Card and a Button gets two sets both called "Component", and the agent reasons
from this payload — so a library of a dozen agent-built sets is unreadable to the agent that
built it, not only to the human.

Split out of Phase 03 deliberately: that phase names *axes* (`Property 1` → `Size`); this one
names the *set*. Different event, different target, and folding them together would have made
one unreviewable phase out of two small ones.

## Before Start

- [ ] Phase 03 merged
- [ ] Re-read `dwv/rename-variant` ([variants.cljs:554](../../../../../frontend/src/app/main/data/workspace/variants.cljs)) — `(variant-id name)`, renames the container **and** every member component via `dwl/rename-component-and-main-instance`; applies `cpn/clean-path` to the name
- [ ] Re-read `dwv/rename-comp-or-variant-and-main` (:577) — routes by whether the component is a variant; may be the better entry point since it covers plain components too
- [ ] **Decide the real question below before writing code**

## The real question — fix the tool, or give the agent a rename?

Two different fixes, and they are not exclusive:

1. **`rename_shape` / widen `create_component`** — the root cause is that
   `create_component` throws the name away. If it kept "Card", nothing downstream would need
   renaming. Note `modify_shape` already takes `name`, so the agent *can* rename a board — but
   `create_component` renames it *afterwards*, so the fix is ordering, not a new tool. Check
   whether `dwl/add-component` accepts a name before assuming it must be a second call.
2. **`rename_variant`** — a tool wrapping `dwv/rename-variant`, letting the agent name a set
   after the fact. Needed regardless for "rename this set to Button", and the only fix for
   sets that already exist.

Recommend investigating (1) first — if `add-component` can take a name, that is a one-line
change that prevents the mess rather than cleaning it up, and it fixes plain components too
(they are equally "Component" today). Then ship (2) for the after-the-fact case.

Do **not** ship only (2): an agent that must rename everything it creates will forget, and
the default stays broken.

## Checklist

- [ ] Write tests for validation
- [ ] Investigate whether `create_component` can preserve the source board's name
- [ ] Implement the chosen fix(es)
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: build a Card set and a Button set, confirm `read_design` tells them apart by name
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:` or `:bug:` — arguably a bug)

## Notes

`rename-variant` applies `cpn/clean-path`, so names carrying `/` become paths (Penpot's
grouping convention). That is probably desirable — `"Forms / Input"` groups in the assets
panel — but it means a name with a slash silently restructures the library. Worth a line in
the tool description rather than a surprise.

Watch the interaction with Phase 01's validation: renaming a *member* of a variant set via
`modify_shape` is not the same as renaming the set, and `rename-comp-or-variant-and-main`
exists precisely because the UI hit this. If a `rename_variant` tool lands, check what
`modify_shape name` on a member currently does — it may quietly desync the shape name from
its component name.
