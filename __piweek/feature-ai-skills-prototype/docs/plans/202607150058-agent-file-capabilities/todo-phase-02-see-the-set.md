# Phase 02 — See the set

**Status:** todo

`create_variant` (Phase 01) is write-only until the agent can read the result back.
`read_design` currently reports `id/name/type/x/y/width/height` per shape — a variant
container looks like an ordinary board, and a member looks like an ordinary frame. Closing
that loop is what makes the agent's self-check honest instead of hopeful.

## Before Start

- [ ] Phase 01 merged
- [ ] Re-read `read-design` and `summarize-shape` in `agent_tools.cljs`
- [ ] Re-read `cfv/find-variant-components` in `common/src/app/common/files/variant.cljc` — note it **reverses** `(:shapes container)`, and does not filter `:components`, to preserve child order

## Checklist

- [ ] Write tests for the summarizer (see Tests below)
- [ ] Extend `summarize-shape` (or add a variant-aware branch) to mark containers and members
- [ ] Add a `variants` section to the `read_design` payload
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: build a set with `create_variant`, confirm `read_design` describes it
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

## Notes

`find-variant-components` needs the file's components list as well as `objects` — check its
arity and source the components via `dsh/lookup-file-data` rather than assuming `read-design`
already has them in scope.
