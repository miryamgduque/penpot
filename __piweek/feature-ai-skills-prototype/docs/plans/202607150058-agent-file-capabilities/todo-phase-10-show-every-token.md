# Phase 10 — Show every token

**Status:** todo

`read_design` reports one token type:

```clojure
;; agent_tools.cljs:189
:colorTokens (->> … (filter #(= :color (:type %))) …)
```

After Phases 08 and 09 the agent can author and apply twenty types but can only *see* one — so
it will author duplicates of tokens that already exist. `penpotUtils.tokenOverview()` is the
second-hottest symbol in the whole skill corpus (36 mentions, 6 skills) and is a mandatory
preflight per the router's One Rule. This is our version of it, and it's currently
color-tinted.

## Before Start

- [ ] Phases 08 and 09 merged
- [ ] Re-read `read-design` (agent_tools.cljs:177-193)
- [ ] Check `ctob/get-tokens-in-active-sets` — does it already carry `:type` and set membership?
- [ ] Read what `tokenOverview()` returns in the skills' own examples — match its *shape* where cheap, since the skill bodies teach the agent to expect it

## Checklist

- [ ] Write tests for the grouped payload
- [ ] Replace `:colorTokens` with a type-grouped `:tokens`
- [ ] Decide the `:colorTokens` compatibility question (below)
- [ ] Measure the payload against the 20k truncation budget
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: a file with color + spacing + radius tokens reads back all three
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Implementation

Group by type — it's how designers think about a token library, and it lets the agent scan for
"is there a spacing scale?" in one look:

```clojure
:tokens {:color   [{:name "color.brand.primary" :value "#6366f1"} …]
         :spacing [{:name "spacing.md" :value "16"} …]
         :borderRadius […]}
```

**Drop `:colorTokens` or keep it?** Recommend **drop**. Nothing external consumes
`read_design`'s payload — it goes to the model, which reads whatever it's given. Keeping both
doubles the color tokens in every response for no gain. But grep the skill bodies for
`colorTokens` first: if a body teaches the agent to look for that key, dropping it silently
makes the body wrong.

**Watch the budget.** `read_design` is called constantly and results truncate at 20k chars
([agent.cljs:350](../../../../../frontend/src/app/main/data/workspace/agent.cljs)). A real
design system is hundreds of tokens; listing every one on every call could crowd out the
shapes. Measure against a realistic file before optimizing, but if it's tight, the fallback is
counts plus name prefixes per type (`spacing.*: 6 tokens`) with a `find_tokens` tool for
drill-in. Do not silently truncate the list — a partial list the agent reads as complete is how
you get a duplicate `spacing.md`.

Include the **resolved** value as today (`(or (:resolved-value t) (:value t))`), but for
aliases consider surfacing both: an agent that sees only `#6366f1` cannot tell a literal from
a `{color.blue.500}` reference, and that distinction is most of what `penpot-audit-tokens`
is looking for.

## Tests

- [ ] a file with three token types returns all three groups
- [ ] a type with no tokens is absent, not an empty array (payload economy)
- [ ] alias tokens surface their reference, not only the resolved value
- [ ] payload for a realistic token library stays inside budget

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `read-design` token section
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — payload tests

## Notes

Phases 02, 10 and 15 all edit `read_design`'s payload, each adding a section (variants, tokens,
depth). If they land far apart, the third one should stop and reconsider the payload as a
whole rather than bolting on a third section — `read_design` is the agent's single orientation
call and the most-read thing we produce. Its shape deserves one deliberate design pass, not
three accretions.
