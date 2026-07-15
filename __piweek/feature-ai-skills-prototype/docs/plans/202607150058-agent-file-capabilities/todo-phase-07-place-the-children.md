# Phase 07 — Place the children

**Status:** todo

`set_layout` (Phase 06) arranges a board's children as a group. `layoutChild` is how an
individual child says "I grow", "I hug", "I ignore the layout". 14 mentions with `growType` at
7 and `absolute` at 7 — the skills use it constantly, because a flex board with no per-child
control produces uniformly-sized children and nothing else.

## Before Start

- [ ] Phase 06 merged
- [ ] Re-read `update-layout-child` ([shape_layout.cljs:558](../../../../../frontend/src/app/main/data/workspace/shape_layout.cljs)) — `(ids attrs)`, note it also touches children and parents of `ids`
- [ ] Confirm the `:layout-item-*` keys and allowed values in `common/src/app/common/types/shape/layout.cljc`
- [ ] Check what `:layout-item-h-sizing` / `:layout-item-v-sizing` accept (`:fill`/`:auto`/`:fix`) versus the plugin's `horizontalSizing` naming

## Checklist

- [ ] Write tests for param mapping + validation
- [ ] Add the `set_layout_child` spec to `tool-specs`
- [ ] Implement `set-layout-child`
- [ ] Wire into the `execute-tool` dispatch `case`
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: one child fills, siblings hug; one child absolute, confirm it leaves the flow
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Implementation

```clojure
{:name "set_layout_child"
 :description
 (str "Controls how one child behaves inside its parent's flex layout: whether it "
      "grows to fill, hugs its content, aligns differently from its siblings, or "
      "leaves the flow entirely (absolute). Only meaningful for a child of a board "
      "that has a layout — give the parent one with set_layout first. Margins accept "
      "tokens via apply_tokens. Asynchronous: verify with read_design.")
 :input-schema
 {:type "object"
  :properties {:shapeId {:type "string"}
               :horizontalSizing {:type "string" :enum ["fill" "auto" "fix"]}
               :verticalSizing {:type "string" :enum ["fill" "auto" "fix"]}
               :alignSelf {:type "string" :enum ["start" "center" "end" "stretch"]}
               :margin {:type "object" :description "top/right/bottom/left, px"}
               :absolute {:type "boolean" :description "leave the layout flow"}
               :zIndex {:type "number"}
               :minWidth {:type "number"} :maxWidth {:type "number"}
               :minHeight {:type "number"} :maxHeight {:type "number"}}
  :required ["shapeId"]}}
```

Validation:

| Condition | Message |
|---|---|
| shape not found | `set_layout_child: no shape with id X on this page` |
| parent has no layout | `set_layout_child: the parent of "Title" (X) has no layout, so these settings would do nothing — call set_layout on board Y first` |
| shape is a root/top-level child | `set_layout_child: X is not inside a board — nest it with nest_shape first` |
| unknown enum value | `set_layout_child: horizontalSizing "grow" is not valid — use one of: fill, auto, fix` |

The parent-has-no-layout check is the important one and the reason this is not folded into
`modify_shape`. `update-layout-child` will happily write `:layout-item-*` attrs onto a shape
whose parent has no layout: they persist, do nothing, and silently reappear the moment someone
adds a layout later. That is a silent no-op wearing a success message — precisely what this
plan exists to prevent. Reject it and name the board to fix.

## Tests

- [ ] param map → `:layout-item-*` keys, one assertion per key
- [ ] rejects a child whose parent has no layout, message names `set_layout` and the parent id
- [ ] rejects a top-level shape, message names `nest_shape`
- [ ] rejects an unknown enum value, message lists the valid ones

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec, `set-layout-child`, dispatch entry
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — mapping and validation tests

## Notes

The `fill`/`auto`/`fix` vocabulary is Penpot's, and the agent's instinct will be CSS
(`flex: 1`, `grow`, `hug`). "Hug" is Figma's word and appears in the skills. The enum error
message is doing real work here — consider naming the CSS/Figma equivalents in the tool
description if review shows the agent guessing wrong repeatedly.
