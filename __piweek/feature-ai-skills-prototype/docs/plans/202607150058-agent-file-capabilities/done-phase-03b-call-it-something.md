# Phase 03b — Call it something

**Status:** done

Added during Wave 1 execution. **Its premise was wrong, and finding that out was the phase.**

## What the phase thought

Phase 02's live payload showed every set and every member named "Component", and both Phase 01
and Phase 02 concluded: *"`create_component` renames the source board (`dwl/add-component`)"*.
The plan proposed either teaching `create_component` to keep the name, or adding a
`rename_variant` tool.

## What is actually true

`create_component` **never renames anything.** Measured directly, one board through the chain:

| after | name |
|---|---|
| `create_shape {name: "Widget"}` | Widget |
| `create_component` | **Widget** — survives |
| `create_variant` | **Component** |

`generate-add-component` (`logic/libraries.cljc:2547`) uses a single frame as the component
root and keeps its name; the rename happens in **`transform-in-variant`**
(`variants.cljs:413`):

```clojure
name (if add-wrapper?
       (str "Component/" (:name main))   ; ← the fallback
       (:name main))
…
name (first cpath)                        ; ← "Component" becomes the set's name
```

`add-wrapper?` is `(empty? prefix)` from `combine-as-variants:667`, where `prefix` is the
**common path prefix** of the member names. So "Component" is not a bug and not a discarded
name — it is a deliberate placeholder for *"these components share no path, so I have nothing
to call this set."*

## The real mechanism — naming *is* the matrix

Penpot derives the whole structure from the member names. Verified live, three times:

| component names | set name | axes |
|---|---|---|
| `Widget`, `Widget Wide` | **Component** | `Property 1` = Widget \| Widget Wide |
| `Badge / Compact`, `Badge / Large` | **Badge** | `Property 1` = Compact \| Large |
| `Chip / Small / Hover`, `Chip / Large / Default` | **Chip** | `Property 1` = Small \| Large<br>`Property 2` = Hover \| Default |

The shared leading path segment becomes the set name; every further segment becomes another
property (`num-props` is `(max 1 (dec (count cpath)))`, `variants.cljs:426`).

**This is the biggest find of Wave 1: multi-axis variant matrices already work, today, with no
new tool.** The transcript's original ask — a Card with Size × State axes — needs only
correctly-named components plus `create_variant`. Phase 03's `set_variant_property` renames the
axes afterwards; nothing else is required. `add-new-property` stays unbuilt, and should stay
that way.

## What shipped

No rename tool, and no change to `create_component` — there was nothing to fix in either.
Instead:

1. **`create_variant`'s description now teaches the convention**, with both worked examples.
   This is the actual fix: the agent designs the matrix by naming components before combining.
2. **A `namingHint` on the result** when the members share no path. Not a rejection — the
   operation is valid — but the agent is told what it just got and how to ask for better.
   Verified live: fires on `Pill`/`Pill Wide` (set really is named "Component"), silent on
   `Tile / Small`/`Tile / Big` (set named "Tile").

## Checklist

- [x] Write tests for validation
- [x] Investigate whether `create_component` can preserve the source board's name — **it already does; the premise was wrong**
- [x] Implement the chosen fix — description + `namingHint`, no new tool
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: `Tile / Small` + `Tile / Big` → set named "Tile", no hint; `Pill` + `Pill Wide` → "Component" + hint
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## Notes

**The lesson is about the diagnosis, not the code.** "Everything is named Component" was
observed correctly in Phase 02 and attributed to the wrong cause — twice, in two phase files,
without anyone checking. The check took one console call. A `rename_variant` tool would have
shipped, worked, and papered over a convention the agent should have been taught instead;
worse, it would have left the multi-axis capability undiscovered, and Phase 04 or a later
`add_variant_property` phase would have been built to fill a gap that does not exist.

**Follow-ups this opens:**

- `set_variant_property` may be less necessary than Phase 03 assumed. Well-named components
  give meaningful values *and* the right axis count from the start — only the axis *names*
  (`Property 1` → `Size`) still need it. That is a smaller job than the phase implies, and the
  tool is already built either way.
- A `rename_variant` tool is still the only fix for sets that already exist (`dwv/rename-variant`,
  `variants.cljs:554`). Not needed for the create path; worth having if a "rename this set" ask
  appears. Note it applies `cpn/clean-path`, so a name with `/` restructures the library path —
  which, given everything above, is a feature.
- Phase 05 should check whether `penpot-component-factory` teaches this convention. It is
  Penpot-specific and not obvious; if the skill says to name variants some other way, the
  preamble's "translate onto your own tools" will not save it.
