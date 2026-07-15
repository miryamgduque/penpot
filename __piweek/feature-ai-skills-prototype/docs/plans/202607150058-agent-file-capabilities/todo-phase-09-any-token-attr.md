# Phase 09 — Any token attr

**Status:** todo

The other half of the color ceiling. `apply_tokens` can bind a token to exactly two things:

```clojure
;; agent_tools.cljs:406
(defn- attr-set
  "Maps the requested properties to shape color attributes (default fill)."
  [properties]
  (into #{} (map #(if (= % "stroke") :stroke-color :fill))
        (if (seq properties) properties ["fill"])))
```

`dwta/toggle-token` takes `{:keys [token attrs shape-ids expand-with-children]}` with `attrs`
as an arbitrary set, and the valid universe is `cto/all-keys`
([token.cljc:373](../../../../../common/src/app/common/types/token.cljc)) — axis, border
radius, color, dimensions, number, opacity, rotation, shadow, sizing, spacing (gap/padding/
margin), stroke width, typography. That helper is the whole restriction.

## Before Start

- [ ] Phase 08 merged (authoring non-color tokens is what makes non-color attrs useful)
- [ ] Re-read `attr-set` and `apply-tokens` (agent_tools.cljs:406-430)
- [ ] Read `cto/all-keys` (token.cljc:373) and the sets it unions — this is the enum source
- [ ] Read the plugin's attr aliasing ([tokens.cljs:29](../../../../../frontend/src/app/plugins/tokens.cljs)): `:r1..:r4` ↔ `borderRadiusTopLeft…`, `:p1..:p4` ↔ `paddingTop…`, `:m1..:m4` ↔ `marginTop…`
- [ ] Check whether `toggle-token` validates attr-vs-token-type compatibility, or whether the tool must

## Checklist

- [ ] Write tests for attr mapping + compatibility validation
- [ ] Replace `attr-set` with a full alias map derived from `all-keys`
- [ ] Widen the `apply_tokens` spec's `properties` enum
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: bind a spacing token to a flex board's `columnGap`, confirm the gap follows the token
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Implementation

Mostly deletion. The work is the alias map and the compatibility check.

**Adopt the plugin's public attr names** (`borderRadiusTopLeft`, `paddingTop`, `marginLeft`,
`columnGap`) rather than the internal `:r1`/`:p1`/`:m1`. Three reasons: the skills already use
them (`applyToken(tok, ["columnGap"])`, `["paddingTop"]`), the agent will guess them from CSS,
and `:p1` is meaningless without the table. Copy the alias map from `tokens.cljs:29` — this is
the one place copying the plugin's naming is right, because we are copying its translation
layer with it.

**Compatibility is the real trap.** Binding a color token to `paddingTop` is a category error.
If `toggle-token` doesn't reject it, the tool must:

| Condition | Message |
|---|---|
| unknown attr | `apply_tokens: "gap" is not an attr — did you mean columnGap or rowGap?` |
| type/attr mismatch | `apply_tokens: token "color.brand.primary" is a color token and cannot bind to paddingTop — use a spacing token, or bind it to fill` |
| token not found | `apply_tokens: no token named "spacing.md" — create it with create_token` |
| spacing attr on a shape whose parent has no layout | `apply_tokens: columnGap on board X does nothing until it has a layout — call set_layout first` |

That last row is Phase 07's lesson repeating: `columnGap` on a board with no layout writes an
attr that silently does nothing. Check it here too.

## Tests

- [ ] every key in `all-keys` maps from a public name (guards the alias map against drift)
- [ ] `columnGap` → the right internal key
- [ ] `borderRadiusTopLeft` → `:r1`
- [ ] color token → `paddingTop` is rejected with the category-error message
- [ ] unknown attr rejected, message suggests near matches
- [ ] existing behaviour preserved: `["fill"]` and `["stroke"]` still work, default is still fill

That last test is the regression guard — `apply_tokens` is the safe coloring path and the one
tool `token-only-colors` can never reject. Do not break it while widening it.

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `attr-set` → alias map, widened spec, compatibility validation
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — mapping and compatibility tests

## Notes

With Phase 06 and Phase 08, this completes the skills' core sentence: `createBoard` →
`addFlexLayout()` → `applyToken(tok, ["columnGap"])`. That sentence is the method of
`build-screen`, `build-from-code`, `migrate` and `component-factory` — four of the six review-mode
skills. It's the demo.

Worth revisiting after this lands: `token-only-colors` is named for the ceiling it was written
under. Once spacing and radius are tokenizable, the same enforcement idea extends to them —
`audit_file` currently flags only raw fills/strokes (`audit-violations`, agent_tools.cljs:480).
A raw `16` in a gap is the same class of problem as a raw `#6366f1` in a fill. Out of scope
here; a candidate for its own plan once we see whether the agent tokenizes spacing unprompted.
