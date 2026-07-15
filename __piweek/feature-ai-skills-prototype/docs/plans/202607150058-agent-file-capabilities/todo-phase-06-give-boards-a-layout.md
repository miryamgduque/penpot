# Phase 06 — Give boards a layout

**Status:** todo

The single biggest gap: 76 mentions across 8 skills, zero tools. Grepping `agent_tools.cljs`
for flex/layout returns three hits, all the word "group" in prose. This phase adds
`set_layout` — flex on a board.

## Before Start

- [ ] Re-read `create-layout-from-id` ([shape_layout.cljs:149](../../../../../frontend/src/app/main/data/workspace/shape_layout.cljs)) — signature `(id type & {:keys [from-frame? calculate-params?]})`, `type` ∈ `:flex | :grid`
- [ ] Re-read `update-layout` (:290) — `(ids changes)`; `changes` is a map of `:layout-*` attrs
- [ ] Re-read `remove-layout` (:234)
- [ ] Check `get-layout-initializer` (used at :161) for what `calculate-params?` infers from existing children — it may already do the sensible thing, which would let the tool pass fewer params
- [ ] Confirm the layout attr keys and their allowed values in `common/src/app/common/types/shape/layout.cljc`

## Checklist

- [ ] Write tests for param mapping + validation
- [ ] Add the `set_layout` spec to `tool-specs`
- [ ] Implement `set-layout`
- [ ] Wire into the `execute-tool` dispatch `case`
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: build a board with 3 children, apply a flex row with a gap, confirm children reflow
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

## Notes

`create-layout-from-id` dissocs `:constraints-h`/`:constraints-v` from children (:166) —
constraints and layout are mutually exclusive in Penpot. Worth knowing when a later phase
touches constraints.

The real prize is Phase 09: once spacing tokens can be applied to `columnGap`/`paddingTop`, the
`createBoard → addFlexLayout → applyToken(gap)` method the skills prescribe becomes possible
end to end. This phase is half of that sentence; without Wave 3 the gaps are hardcoded numbers
and `token-only-colors` has nothing to say about them.
