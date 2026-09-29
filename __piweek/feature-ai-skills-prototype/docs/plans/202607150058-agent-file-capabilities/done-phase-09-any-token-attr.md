# Phase 09 — Any token attr

**Status:** done

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

- [x] Phase 08 merged
- [x] Re-read `attr-set` and `apply-tokens`
- [x] Read `cto/all-keys` (token.cljc:373) and the sets it unions
- [x] Read the plugin's attr aliasing (`tokens.cljs:29`) — kebab keywords, camelised at the JS boundary
- [x] Check whether `toggle-token` validates attr-vs-token-type compatibility — **no, but `dwta/token-properties` is the source it uses** (see Notes)

## Checklist

- [x] Write tests for attr mapping + compatibility validation
- [x] Replace `attr-set` with a full alias map derived from `all-keys`
- [x] ~~Widen the spec's `properties` enum~~ — **deliberately no enum** (see Notes)
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: `spacing.md` bound to a flex board's `columnGap` — the gap went 4 → 16, live
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

## Notes — what execution found

**The compatibility check did not need building.** The plan assumed a hand-written type→attr
table. `toggle-token` already reads `dwta/token-properties` (`application.cljs:922`) for each
type's `:attributes` / `:all-attributes` — so `type-attrs` consults *the same source the event
consults*, which means anything the tool accepts, the event accepts. A hand-written table would
have been a second source of truth, drifting from the first.

**No enum, deliberately — this deviates from the plan.** `cto/all-keys` is ~40 attrs. An enum
that size sits in context on *every* request, and `apply_tokens`' value is that it is cheap and
always available. Instead the schema takes a plain string, the description lists the common
names per type, and **the rejection names the valid attrs for that token's type** — which is
both shorter and more useful than the universe:

```
"gap" is not a property a token can bind to — "spacing.md" can bind to:
  columnGap, marginBottom, marginLeft, marginRight, marginTop,
  paddingBottom, paddingLeft, paddingRight, paddingTop, rowGap
```

`gap` is exactly what a CSS-trained agent guesses, and it gets `columnGap` back in the same
breath. The full list only appears where it is needed.

**The category error works** — the plan's headline trap:

```
token "color.test.brand" is a color token and cannot bind to paddingTop
  — it can bind to: fill, strokeColor
```

and the same token on `fill` succeeds in the same batch, since applications are per-item.

**The regression guard held.** `apply_tokens` is the safe colouring path and the one tool
`token-only-colors` can never reject. `["fill"]`, `["stroke"]` and the fill default all still
work — `"stroke"` is kept as an explicit legacy alias because it is in the shipped spec and the
agent may already use it; the derived map alone would only have produced `strokeColor`.

**This is the wave's payoff, verified live.** The skills' core sentence, executable for the
first time:

```
create_shape (board) → set_layout {dir: row, columnGap: 4}
                     → apply_tokens {tokenName: "spacing.md", properties: ["columnGap"]}
  → layout-gap {:column-gap 16}      ← the token's value now drives the layout
```

Also verified: `radius.card` → `borderRadiusTopLeft` binds. That sentence is the method of
`build-screen`, `build-from-code`, `migrate` and `component-factory` — four of the six
review-mode skills.

**Follow-up now worth taking seriously.** `token-only-colors` is named for the ceiling it was
written under, and `audit_file` still only flags raw fills/strokes (`audit-violations`). A raw
`16` in a gap is now exactly the same class of problem as a raw `#6366f1` in a fill, and for the
first time the agent has the tools to fix it. Its own plan, once we see whether the agent
tokenises spacing unprompted.

## Notes — from planning

`apply_tokens` is the safe coloring path and the one tool `token-only-colors` can never reject.
Do not break it while widening it.

Worth revisiting after this lands: `token-only-colors` is named for the ceiling it was written
under. Once spacing and radius are tokenizable, the same enforcement idea extends to them —
`audit_file` currently flags only raw fills/strokes (`audit-violations`, agent_tools.cljs:480).
A raw `16` in a gap is the same class of problem as a raw `#6366f1` in a fill. Out of scope
here; a candidate for its own plan once we see whether the agent tokenizes spacing unprompted.
