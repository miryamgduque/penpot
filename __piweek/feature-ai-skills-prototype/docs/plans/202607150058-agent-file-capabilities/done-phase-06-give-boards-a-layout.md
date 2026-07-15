# Phase 06 — Give boards a layout

**Status:** done

The single biggest gap: 76 mentions across 8 skills, zero tools. Grepping `agent_tools.cljs`
for flex/layout returns three hits, all the word "group" in prose. This phase adds
`set_layout` — flex on a board.

## Before Start

- [x] Re-read `create-layout-from-id` — `(id type & {:keys [from-frame? calculate-params?]})`, `type` ∈ `:flex | :grid`
- [x] Re-read `update-layout` (:290) — `(ids changes)`, patches via `d/patch-object`
- [x] Re-read `remove-layout` (:234)
- [x] Check `get-layout-initializer` — `calculate-params?` defaults true and seeds sensible params, so the tool can pass only what the caller asked for
- [x] Confirm the layout attr keys and allowed values in `common/src/app/common/types/shape/layout.cljc` — **the plan's spec was wrong about one** (see Notes)

## Checklist

- [x] Write tests for param mapping + validation
- [x] Add the `set_layout` spec to `tool-specs`
- [x] Implement `set-layout`
- [x] Wire into the `execute-tool` dispatch `case`
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: three children at messy absolute coords reflowed to one row, live
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Implementation

Two operations behind one tool, because "add a layout" and "change its params" are the same
intent from the agent's side:

- board has no layout → `create-layout-from-id id :flex` then `update-layout [id] changes`
- board already has one → `update-layout [id] changes` only
- `remove: true` → `remove-layout`

```clojure
{:name "set_layout"
 :description
 (str "Gives a board a flex layout, or updates the one it has — the normal way to "
      "arrange children in Penpot. Children reflow automatically, so you stop "
      "positioning them by hand: prefer this over setting x/y on each child. "
      "Gaps and padding accept tokens via apply_tokens (columnGap, rowGap, "
      "paddingTop…). Asynchronous: verify with read_design.")
 :input-schema
 {:type "object"
  :properties {:shapeId {:type "string" :description "board id"}
               :dir {:type "string" :enum ["row" "column" "row-reverse" "column-reverse"]}
               :alignItems {:type "string" :enum ["start" "end" "center" "stretch"]}
               :justifyContent {:type "string" :enum ["start" "center" "end" "space-between" "space-around" "space-evenly"]}
               :rowGap {:type "number"}
               :columnGap {:type "number"}
               :padding {:type "object" :description "top/right/bottom/left, px"}
               :wrap {:type "boolean"}
               :remove {:type "boolean" :description "strip the layout instead"}}
  :required ["shapeId"]}}
```

The mapping from JS-ish names to internal `:layout-*` keys is the substance of this phase.
Derive it from `layout.cljc` rather than from the plugin API's `flex.cljs` — the plugin proxy
does its own aliasing, and copying its names without its translation layer is a silent
mismatch. Verify each key against the schema before wiring it.

Validation, per the plan's standing rule:

| Condition | Message |
|---|---|
| shape not found | `set_layout: no shape with id X on this page` |
| not a board/frame | `set_layout: shape "Card" (X) is a rect — only boards can have a layout. Create it with create_shape type=board, or nest these shapes into one.` |
| `remove` on a board with no layout | `set_layout: board X has no layout to remove` |
| unknown enum value | `set_layout: dir "horizontal" is not valid — use one of: row, column, row-reverse, column-reverse` |

That last row matters more than it looks. The agent has been trained on CSS; `horizontal` and
`flex-start` will be its instincts. Rejecting with the valid list turns a wrong guess into a
correct retry.

## Grid is deliberately out of scope

`addGridLayout` is 2 mentions to flex's 16. Grid also drags in track management
(`addRow`/`setColumn`/`TrackProxy`) and cell assignment — a phase of its own, and one the
skills barely ask for. `create-layout-from-id` takes `:grid` as its `type`, so the door stays
open. Note it, don't build it.

## Tests

- [ ] param map → `:layout-*` keys, one assertion per key (this is where the bugs will be)
- [ ] rejects a non-board, message names `create_shape type=board`
- [ ] rejects an unknown enum value, message lists the valid ones
- [ ] `remove: true` on a board with no layout is rejected, not a silent no-op
- [ ] no-layout board takes the create-then-update path; existing-layout board takes update only

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec, `set-layout`, param mapping, dispatch entry, `[app.main.data.workspace.shape-layout :as dwsl]`
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — mapping and validation tests

## Notes — what execution found

**Reading the schema instead of the plugin paid off immediately.** The plan's draft spec gave
`justifyContent` five values; `justify-content-types` (`layout.cljc:63`) has **seven** — it also
accepts `:stretch`, and `align-content` differs from `justify-content` in its set. Had the enum
been copied from the plan, `justifyContent: "stretch"` would have been rejected by our own
validation as invalid while Penpot accepts it perfectly well. The enums are now taken from the
schema.

**`wrap` is not a boolean internally.** `:layout-wrap-type` is `:wrap`/`:nowrap`. The tool takes
a boolean (what the agent expects) and maps it; passing the raw boolean through would have
failed the schema. Pinned by a test.

**Only-what-was-asked, deliberately.** `update-layout` patches with `d/patch-object`, so any key
present is applied. `layout-changes` emits only keys actually supplied — a nil default would
silently clobber a value the caller never mentioned. `absent-params-produce-no-keys` guards it.

**Verified live — this is the phase's whole point.** A board with three children hand-placed at
y = 210 / 241 / 272 (deliberately messy, exactly what the agent does today):

```
set_layout {dir: "row", columnGap: 12, padding: {…16}, alignItems: "center"}
  → children y = 280, 280, 280        (reflowed — they no longer hold their own positions)
  → children x spaced exactly 72 apart (60 width + 12 gap)
  → board: :flex, :row, {:column-gap 12 :row-gap 0}, {:p1..:p4 16}, :center
```

Every rejection fires, and the two that matter most are the agent's CSS instincts:

```
dir: "horizontal"       → not valid — use one of: column, column-reverse, row, row-reverse
alignItems: "flex-start" → not valid — use one of: center, end, start, stretch
on a rect                → "Rectangle" (…) is a rect, and only boards can have a layout —
                           create one with create_shape type=board and nest these shapes into it
remove twice             → "Toolbar" (…) has no layout to remove
nothing to change        → pass dir, alignItems, … (or remove: true)
```

`horizontal` and `flex-start` are what a model trained on CSS reaches for first. Listing the
real vocabulary turns each into a correct retry rather than a silent nothing.

**Child order note, not a bug:** children lay out in `(:shapes board)` order, which is reverse
z-order, so the first-created child ends up rightmost in a `:row`. Penpot's own behaviour; worth
knowing for Phase 07, which addresses individual children.

## Notes — from planning

`create-layout-from-id` dissocs `:constraints-h`/`:constraints-v` from children (:166) —
constraints and layout are mutually exclusive in Penpot. Worth knowing when a later phase
touches constraints.

The real prize is Phase 09: once spacing tokens can be applied to `columnGap`/`paddingTop`, the
`createBoard → addFlexLayout → applyToken(gap)` method the skills prescribe becomes possible
end to end. This phase is half of that sentence; without Wave 3 the gaps are hardcoded numbers
and `token-only-colors` has nothing to say about them.
