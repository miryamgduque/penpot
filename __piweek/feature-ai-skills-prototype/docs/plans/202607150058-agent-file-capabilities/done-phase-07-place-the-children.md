# Phase 07 — Place the children

**Status:** done

`set_layout` (Phase 06) arranges a board's children as a group. `layoutChild` is how an
individual child says "I grow", "I hug", "I ignore the layout". 14 mentions with `growType` at
7 and `absolute` at 7 — the skills use it constantly, because a flex board with no per-child
control produces uniformly-sized children and nothing else.

## Before Start

- [x] Phase 06 merged
- [x] Re-read `update-layout-child` (:558) — `(ids attrs)`; it also fixes sizing on children and parents of `ids`
- [x] Confirm the `:layout-item-*` keys and allowed values in `layout.cljc:157`
- [x] Check `:layout-item-h-sizing` / `:layout-item-v-sizing` — `#{:fill :fix :auto}`, and `ctl/any-layout?` is the parent guard

## Checklist

- [x] Write tests for param mapping + validation
- [x] Add the `set_layout_child` spec to `tool-specs`
- [x] Implement `set-layout-child`
- [x] Wire into the `execute-tool` dispatch `case`
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: `fill` grew a child 60px → 380px live; the parent guard fires and names the board
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

## Notes — what execution found

**The plan's central prediction held, and it is the reason this is its own tool.**
`update-layout-child` writes `:layout-item-*` onto any shape without checking its parent. The
attrs persist, do nothing, and spring to life the moment someone adds a layout later — a silent
no-op wearing a success message. Verified live:

```
set_layout_child {horizontalSizing: "fill"} on a child of a plain board
  → "the parent of "A" (…) has no layout, so these settings would be stored and
     do nothing — call set_layout on board 8c6a6638-… first"
```

Naming *the board* is what makes it actionable — the agent has the fix and the id in one line.

**`fill` genuinely fills, measured.** After `set_layout` on the parent, the same call grew the
child from **60px to 380px** (400 board − padding) and set `:layout-item-h-sizing :fill`. The
CSS guess `"grow"` comes back `not valid — use one of: auto, fill, fix`.

**`absolute false` was a live trap avoided.** Every other param can use truthiness, but `false`
here is a real instruction — "rejoin the flow" — not an absent param. A truthiness check would
have silently dropped it, and the agent would have had no way to undo `absolute: true`. Tested
explicitly (`absolute-false-is-kept-not-dropped`); the same shape of bug is worth watching for
in Wave 3, where token values can legitimately be `0`.

**Vocabulary, as predicted.** The description now spells out the translation — "fill is CSS
flex-grow, auto is hug-contents" — because the agent's instincts are CSS (`flex: 1`, `grow`) and
Figma ("hug", which the skills use). The enum error carries the rest.

## Notes — from planning

The `fill`/`auto`/`fix` vocabulary is Penpot's, and the agent's instinct will be CSS
(`flex: 1`, `grow`, `hug`). "Hug" is Figma's word and appears in the skills.
