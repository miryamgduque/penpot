# Phase 15 — Deeper than top level

**Status:** todo

`penpotUtils.shapeStructure()` is the hottest symbol in the entire skill corpus — 33 mentions
across 8 skills — and the router's One Rule makes a read-only structure preflight a
precondition for *every* downstream skill. Our equivalent reads one level:

```clojure
;; agent_tools.cljs:183,188
top-ids  (get-in objects [uuid/zero :shapes])
:shapes  (mapv #(summarize-shape objects %) top-ids)
```

Children are invisible. The agent sees boards and nothing inside them. Everything it knows
about nested content it knows because it put it there in the same turn — which is exactly why
the transcript's 18-tool content-placeholder burst was flying blind.

## Before Start

- [ ] Re-read `read-design` and `summarize-shape` (agent_tools.cljs:166-193)
- [ ] Check `cfh/get-children-ids` and friends in `common/src/app/common/files/helpers.cljc`
- [ ] Look at what `shapeStructure()` returns in the skills' own examples — the bodies teach the agent to expect a shape; matching it where cheap saves the model a translation
- [ ] Re-read the 20k truncation ([agent.cljs:350](../../../../../frontend/src/app/main/data/workspace/agent.cljs)) and the 1000-shape audit cap (agent_tools.cljs:458) — both are evidence someone already hit this wall

## Checklist

- [ ] Write tests for depth and budget
- [ ] Add bounded depth to `read_design`
- [ ] Add `find_shapes` (or decide against it — see below)
- [ ] Measure payload on a real file
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: a nested design reads back legibly, without blowing the budget
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## The tension

Depth and budget pull against each other, and this phase is mostly about where to stand.
`read_design` is called constantly, and a full tree of a real file is far past 20k chars — at
which point it truncates mid-structure and the agent reads a **partial tree as complete**. That
is worse than the current shallow-but-honest payload: a wrong belief beats no belief only when
it's flagged as wrong.

Options:

1. **Bounded depth** (`depth: 2` default, param to go deeper). Simple, predictable, and the
   agent can ask for more where it's looking.
2. **`find_shapes(criteria)`** — query instead of dump, mirroring `page.findShapes`. Scales to
   any file size and matches what the skills reach for (`findShapes` ×4, `findShapeById` ×4).
3. **Both**: shallow-by-default `read_design` for orientation, `find_shapes` for drill-in.

Recommend 3. It matches how the skills actually work — `shapeStructure()` for the preflight,
`findShapes` for the specific thing — and keeps the constant-cost call cheap.

Whatever is chosen: **if the payload is truncated or depth-limited, say so in the payload**.
`"truncated": true, "note": "showing depth 2 of 5 — use find_shapes to go deeper"` costs
twenty tokens and prevents the agent concluding a board is empty because it couldn't see in.
Silent truncation is the same failure mode as a silent no-op, one layer up.

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `read-design`, `summarize-shape`, possibly `find-shapes`
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — depth and budget tests

## Notes

This is the third phase to edit `read_design`'s payload (after 02 and 10). By now it has
accreted variants, tokens and depth from three different sittings. **Stop and redesign the
payload as a whole here** rather than adding a fourth section — it is the agent's single
orientation call, the most-read thing we produce, and by this point it will have earned one
deliberate pass. If Phase 13 also added a `components` section, that's four.
