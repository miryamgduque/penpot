# Phase 08 — Any token type

**Status:** done

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

- [x] Re-read `create-color-token` and `dwtl/create-token`
- [x] Read the type list — **it is `token-type->dtcg-token-type` (`token.cljc:87`), and public names are DTCG, not the internal keywords** (see Notes)
- [x] Check what `ctob/make-token` validates per type — **nothing useful: `:value` is `::sm/any`** (see Notes)
- [x] Check how composite types express their value — structured; rejected honestly

## Checklist

- [x] Write tests for type validation + value shapes
- [x] Add the `create_token` spec to `tool-specs`
- [x] Implement `create-token` (generalizing `create-color-token`)
- [x] Decide the fate of `create_color_token` — **replaced**, and the guard message updated with it
- [x] Wire into the `execute-tool` dispatch `case`
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: `spacing.md`, `radius.card`, `spacing.none` authored live into the file's token library
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

## Notes — what execution found

**The plan's "twenty types" was right but the wrong list.** `token-types` is a set of *internal
keywords* (`:border-radius`, `:font-size`). The names a caller uses are **DTCG**
(`borderRadius`, `fontSizes`), and the mapping in both directions already exists —
`token-type->dtcg-token-type` / `dtcg-token-type->token-type` (`token.cljc:87`). The tool takes
DTCG names and maps in; the enum is derived from that map, so it cannot drift. As a free bonus,
`dtcg-token-type->token-type` already carries back-compat singular aliases (`fontSize`,
`fontWeight`, `boxShadow`), so the agent's likely near-misses resolve rather than fail.

**`make-token` validates nothing about the value: `:value` is `::sm/any`** (`token.cljc:180`).
So the plan's fear was real — a `typography` token with a plain string value would be created
happily and be malformed. Both composites (`typography`, `shadow`) are therefore not offered,
and rejected with an honest message naming the real reason and the workaround ("author it in
the tokens panel") rather than pretending they are invalid.

**18 types offered, up from 1.** Verified live: `spacing.md = 16` and `radius.card = 12` land in
the file's token library as `:spacing` and `:border-radius`.

**Two value traps, both tested, both from earlier phases' lessons:**

- **`"0"` is legitimate** — a zero spacing token is a real thing. The blank check is
  `(and (string? value) (empty? value))`, not `str/blank?`/truthiness. This is Phase 07's
  `absolute false` lesson repeating exactly as predicted in its notes.
- **Aliases survive.** `value: "{color.brand.primary}"` is a reference to another token, and
  `penpot-foundations` uses them. Validating the `{…}` syntax away would have quietly reduced a
  token *system* to a token *list*.

**`create_color_token` is gone, replaced by `create_token` with `type: "color"`.** Two tools for
one job costs context on every request and invites the agent to wonder how they differ. The
grep the plan asked for found the important caller: **the `token-only-colors` guard message
itself** (`agent_tools.cljs:550`) told the agent to recover using `create_color_token`. That
message and its explanatory comment now name `create_token (type: color)` — a rejection that
names a tool which no longer exists would be worse than the tool it replaced. The only other
hits are in the retired `ai-skills/` plugin, which is superseded and not live.

## Notes — from planning

Together with Phase 09 this makes `penpot-component-factory`'s One Rule — *"every value is a
token"* — satisfiable for the first time. Worth demoing as a pair; neither half is convincing
alone.
