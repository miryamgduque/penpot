# Phase 10 — Show every token

**Status:** done

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

- [x] Phases 08 and 09 merged
- [x] Re-read `read-design`
- [x] Check `ctob/get-tokens-in-active-sets` — carries `:type`, so `group-by` is enough
- [x] Read what `tokenOverview()` returns in the skills' examples

## Checklist

- [x] Write tests for the grouped payload
- [x] Replace `:colorTokens` with a type-grouped `:tokens`
- [x] Decide the `:colorTokens` compatibility question — **dropped**; grep found nothing referencing it
- [x] Measure the payload against the 20k truncation budget — **10,654 chars, 53%**
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: color + spacing + borderRadius all read back, grouped
- [x] **Found and fixed a data-loss bug while verifying** (see Notes — this is the phase's real find)
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

## Notes — the data-loss bug this phase found

**Making the agent *see* tokens is how we discovered it could not reliably *keep* them.**
Reading back what Phase 08 authored, the tokens were missing — and each reload-then-author
cycle left only the newest ones. Three consistent observations, then the code confirmed why.

`dwtl/create-token`'s 1-arity resolves its target through `lookup-token-set` (`library_edit.cljs:44`):

```clojure
(when-let [selected (dm/get-in state [:workspace-tokens :selected-token-set-id])]
  (lookup-token-set state selected))
```

That is the **UI selection**, not the library. Nobody has opened the Tokens panel in an agent
session, and it resets on every page load — so it is nil, and the nil branch runs
`create-token-with-set` (`:494`), which is documented as *"a special case when a first token is
created and no set exists"*. It builds a fresh `"Global"` set and commits it over the existing
one, **taking every token in it**.

So: **after any page load, the agent's first `create_token` call wiped the file's entire token
library.** On a real design system that is silent, unprompted data loss — the agent asked to add
one spacing token, and the file loses its colors.

**This was inherited, not introduced.** `create_color_token` had the identical bug since it was
written; its comment — *"1-arg create-token targets the current set, creating one if none
exists"* — is what a reader would reasonably believe. The truth is "none *selected*", and the
distinction is invisible until you reload and look. Phase 08 carried the comment forward
unchanged. It took Phase 10 (reading tokens back) to expose it, which is a decent argument for
the read tool being part of the same wave as the write tools.

**The fix**: `existing-token-set-id` resolves a set from the **library** and passes its id
explicitly; the set-creating branch is left for the case it is actually named for. Verified
live under the exact trigger — `selected-token-set-id` nil after a reload:

```
before: [radius.pill, spacing.lg]
create_token spacing.xl
after:  [radius.pill, spacing.lg, spacing.xl]   ← survivors kept
```

Before the fix, that identical call left `[spacing.xl]` alone.

## Notes — the payload

**Grouped under DTCG type names**, the same ones `create_token` accepts, so a type read here can
be passed straight back:

```
borderRadius (1): radius.pill=999
color (2): color.demo.primary=#6366f1 | color.demo.alias={color.demo.primary}
spacing (2): spacing.lg=24 | spacing.xl=32
```

**Aliases keep their reference.** `color.demo.alias` shows `{color.demo.primary}`, not just a
resolved hex — an agent that sees only `#6366f1` cannot tell a literal from a reference, and
that distinction is most of what `penpot-audit-tokens` looks for. `resolvedValue` is added only
when it differs from `value`, so literals stay one line.

**`colorTokens` dropped**, not kept alongside: nothing referenced it (grepped `aikit_bodies` and
`agent_skills`), and keeping both would double every color in every payload.

**Budget: 10,654 chars, 53% of the 20k truncation limit** — up from 29% at Phase 02, but the
file has roughly doubled in shapes since. Types with no tokens are absent rather than empty, so
a file pays only for what it has. Phase 15 adds depth and is the change likely to hit the wall;
re-measure there.

## Notes — from planning

Phases 02, 10 and 15 all edit `read_design`'s payload. **This is now two of three.** If Phase 15
lands, it should stop and reconsider the payload as a whole rather than bolt on a third section
— `read_design` is the agent's single orientation call and the most-read thing we produce.
