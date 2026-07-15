# Phase 08 — Any token type

**Status:** todo

`create_color_token` hardcodes one keyword:

```clojure
;; agent_tools.cljs:401
(let [token (ctob/make-token {:type :color :name name :value value})]
```

`ctob/make-token` takes any of Penpot's twenty token types
([token.cljc:129](../../../../../common/src/app/common/types/token.cljc)) and
`dwtl/create-token` doesn't care which. The color-only ceiling is that one keyword. This phase
removes it.

## Before Start

- [ ] Re-read `create-color-token` (agent_tools.cljs:397) and `dwtl/create-token` ([library_edit.cljs:526](../../../../../frontend/src/app/main/data/workspace/tokens/library_edit.cljs))
- [ ] Read `token-types` ([token.cljc:129](../../../../../common/src/app/common/types/token.cljc)) — enumerate the real list; the README's "twenty" is from a source read, confirm it
- [ ] Check what `ctob/make-token` validates per type — does it reject a bad value shape, or accept anything and fail later?
- [ ] Check how composite types (`typography`, `shadow`) express their value — they are not plain strings, and may not fit a flat `value` param

## Checklist

- [ ] Write tests for type validation + value shapes
- [ ] Add the `create_token` spec to `tool-specs`
- [ ] Implement `create-token` (generalizing `create-color-token`)
- [ ] Decide the fate of `create_color_token` (see below) and act on it
- [ ] Wire into the `execute-tool` dispatch `case`
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: author a spacing token and a radius token, confirm they appear in the tokens panel
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Implementation

```clojure
{:name "create_token"
 :description
 (str "Creates a design token of any type in the file's token library — color, "
      "spacing, borderRadius, sizing, opacity, fontSize and more. This is how a "
      "value becomes reusable: author it here, then bind it to shapes with "
      "apply_tokens. Prefer a token over a literal for any value that repeats.")
 :input-schema
 {:type "object"
  :properties {:type {:type "string" :enum [… from token-types …]}
               :name {:type "string" :description "e.g. spacing.md, color.brand.primary"}
               :value {:type "string" :description "e.g. #6366f1, 16, {spacing.md}"}}
  :required ["type" "name" "value"]}}
```

Three decisions to make deliberately:

**Keep or replace `create_color_token`?** Recommend **replace**, and let `create_token` with
`type: "color"` cover it. Two tools doing one job costs context on every request and invites
the agent to wonder whether they differ. The prototype has no external callers to break — but
grep `aikit_bodies.cljs` and the skill catalog for the name before deleting, since a skill body
naming a tool that no longer exists is worse than a redundant tool.

**Aliases are a feature, not an edge case.** `value: "{color.blue.500}"` (a reference to
another token) appears in `penpot-foundations`. Confirm `make-token` accepts the `{…}` syntax
and don't validate it away — alias support is a large part of what makes a token system a
system rather than a list.

**Composite types may not fit.** `typography` and `shadow` values are structured, not scalar
([tokens.cljs:99,134](../../../../../frontend/src/app/plugins/tokens.cljs) documents the JS
shapes). If a flat string `value` cannot express them, ship the scalar types and reject the
composites with a message saying so — an honest `create_token: typography tokens need a
structured value, which this tool does not accept yet` beats a token that silently authors
malformed.

Validation:

| Condition | Message |
|---|---|
| unknown type | `create_token: "padding" is not a token type — use one of: color, spacing, borderRadius, sizing… (did you mean spacing?)` |
| value invalid for type | `create_token: "16px" is not a valid spacing value — use a number, e.g. 16` |
| composite type (if deferred) | `create_token: shadow tokens need a structured value, which this tool does not accept yet` |

## Tests

- [ ] every type in `token-types` is accepted by the spec enum (guards against the enum drifting from the source list)
- [ ] unknown type rejected, message lists valid types
- [ ] a spacing token authors with a numeric value
- [ ] an alias value `{color.blue.500}` survives to `make-token`
- [ ] composite type behaves as decided (accepted, or rejected with the honest message)

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec, `create-token`, dispatch entry; `create_color_token` removed or kept per the decision above
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — type and value tests

## Notes

Together with Phase 09 this makes `penpot-component-factory`'s One Rule — *"every value is a
token"* — satisfiable for the first time. Worth demoing as a pair; neither half is convincing
alone.

The enum should be **derived** from `token-types`, not retyped. A hand-copied list is a
divergence waiting for the next token type Penpot adds, and the test above only catches it if
the list is generated from the same source.
