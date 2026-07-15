# Phase 02 — See the set

**Status:** done

`create_variant` (Phase 01) is write-only until the agent can read the result back.
`read_design` currently reports `id/name/type/x/y/width/height` per shape — a variant
container looks like an ordinary board, and a member looks like an ordinary frame. Closing
that loop is what makes the agent's self-check honest instead of hopeful.

## Before Start

- [x] Phase 01 merged
- [x] Re-read `read-design` and `summarize-shape` in `agent_tools.cljs`
- [x] Re-read `cfv/find-variant-components` — **it takes file `data`, not just `objects`** (see Notes)

## Checklist

- [x] Write tests for the summarizer (see Tests below)
- [x] Extend `summarize-shape` (or add a variant-aware branch) to mark containers and members
- [x] Add a `variants` section to the `read_design` payload
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: `read_design` describes both sets built in Phase 01, live
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Implementation

Two levels, because they answer different questions.

**1. Shape-level flags** — so a container is never mistaken for a board in `:shapes` /
`:selection`. Add only when truthy, to keep the common payload lean:

```clojure
(cond-> {:id … :name … :type … :x … :y … :width … :height …}
  (ctc/is-variant-container? shape) (assoc :isVariantContainer true)
  (ctc/is-variant? shape)           (assoc :variantId (dm/str (:variant-id shape))
                                           :variantName (:variant-name shape))
  (:variant-error shape)            (assoc :variantError (:variant-error shape)))
```

**2. A `variants` section** — the structural view, which is what the agent actually needs to
decide its next call:

```clojure
:variants
(->> top-ids
     (filter #(ctc/is-variant-container? (get objects %)))
     (mapv (fn [cid]
             {:variantId (dm/str cid)
              :name (:name (get objects cid))
              :members (->> (cfv/find-variant-components objects components cid)
                            (mapv (fn [c]
                                    {:componentId (dm/str (:id c))
                                     :name (:name c)
                                     :properties (mapv #(select-keys % [:name :value])
                                                       (:variant-properties c))})))})))
```

Two decisions worth making deliberately:

- **Reach nested containers, not just `top-ids`.** A variant container relocated into a
  board disappears from a top-level scan, and the agent would then rebuild a set that
  already exists. Walk the objects map filtering `is-variant-container?` rather than
  filtering `top-ids`.
- **Respect `find-variant-components` ordering.** It reverses child order on purpose; the
  last element is the primary variant (`get-primary-variant`). Do not re-sort. If the
  distinction proves useful, flag the primary rather than reordering.

Watch the payload budget: `read_design` is called constantly and the result is truncated at
20k chars ([agent.cljs:350](../../../../../frontend/src/app/main/data/workspace/agent.cljs)).
A file with many sets could crowd out the rest. If it gets close, emit member **count** plus
property names at top level and let the agent drill in — but measure before optimizing.

## Tests

- [ ] a plain board summarizes without any variant keys (no payload growth for non-variant files)
- [ ] a container summarizes with `isVariantContainer true`
- [ ] a member summarizes with its `variantId` and `variantName`
- [ ] the `variants` section lists members with property name/value pairs
- [ ] a nested (non-top-level) container is still found

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `summarize-shape`, `read-design`, requires (`ctc`, `cfv`)
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — summarizer tests

## Notes — what execution found

**The plan's snippet passed the wrong argument.** `cfv/find-variant-components` is
`(data variant-id)` or `(data objects variant-id)` — the first arg is the **file data**, not
the components list, because it resolves each child's `:component-id` through
`ctcl/get-component`. `read-design` now takes `data` from `dsh/lookup-file-data` once and
threads it to both `variant-sets` and the token section (which was re-looking it up).

**Payload measured, as the plan asked.** The whole `read_design` result is **5,701 chars =
29% of the 20k truncation budget** on a 55-shape file with 2 variant sets. Not close to the
wall, so no counts-plus-drill-in fallback was needed. Worth re-measuring in Phase 15, which
adds depth — that's the change likely to blow it, not this one.

**Payload economy holds, verified live**: a non-variant shape comes back with exactly
`id, name, type, x, y, width, height` and no variant keys. Files without variants pay nothing.

**The "Component" naming problem is worse than it looked from Phase 01.** `read_design` now
reports, verbatim:

```json
{"variantId":"…","name":"Component",
 "members":[{"name":"Component","properties":[{"name":"Property 1","value":"Tag"}]},
            {"name":"Component","properties":[{"name":"Property 1","value":"Tag Solid"}]}]}
```

Every set *and* every member is called "Component", because `create_component` renames the
source board. Only the property values distinguish them. The agent can just about cope — the
values disambiguate — but a library of a dozen agent-built sets would be unreadable, and this
is the payload it reasons from. This is now the strongest argument for the rename follow-up
noted in Phase 01; `dwv/rename-variant` (`variants.cljs:554`) is the lever and **Phase 03 is
the right home**.

**Primary-variant ordering left alone, deliberately.** The plan claimed "the last element of
that reversed seq is the primary variant". Reading the source, `is-secondary-variant?`
compares against `(last (:shapes container))` on the **raw** child vector, and
`find-variant-components` returns `(reverse …)` — so the primary is the **first** element of
what it returns, not the last. Rather than encode a claim I hadn't verified at runtime, the
tool preserves `find-variant-components`' order and flags nothing as primary. Phase 04 needs
this settled (it duplicates a member); verify it there against a real set.
