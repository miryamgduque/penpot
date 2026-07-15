# Phase 15 — Deeper than top level

**Status:** done

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

- [x] Write tests for depth and budget
- [x] Add bounded depth to `read_design` — **`childCount` + bounded lists, not a depth param** (see Notes)
- [x] Add `find_shapes` — shipped; it is what makes a bound actionable
- [x] Measure payload on a real file — **13.5k chars, 68% of budget**
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: `find_shapes` reaches nested shapes; the oversize backstop returns valid JSON
- [x] **Fixed the silent-truncation bug this phase was really about** (see Notes)
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

## Notes — what execution found

**The phase was about depth. The real find was that the truncation is silently destructive.**

```clojure
(defn- result->content [result]
  (let [s (js/JSON.stringify (clj->js result))]
    (if (> (count s) max-tool-result-chars)
      (subs s 0 max-tool-result-chars)     ; ← a raw substring of JSON
      s)))
```

`(subs s 0 20000)` cuts **mid-token**, handing the model unparseable JSON with **no marker**.
The agent could not tell a truncated result from a complete one — so a partial shape list reads
as the whole file, and a partial token list invites authoring a duplicate of something already
there. That is this plan's own thesis one layer up: not a silent no-op, but a *silently
incomplete answer*. Every tool rides this path (`audit_file`, and Phase 16's codegen, whose
notes already say "truncated CSS is not degraded output — it's *wrong* output").

**Fixed by refusing rather than prefixing.** An oversized result is replaced by a small valid
JSON object naming its size, the limit and the way out. Verified live: 30,011 chars in → **313
chars out**, valid JSON, no fragment of the real payload, and an error the agent can act on.
Refusing beats prefixing because a prefix of real data is indistinguishable from all of it.

**Bounded, and every bound announces itself.** `bounded` caps each list at 60 and returns a note
naming the real total and `find_shapes`; `read_design` collects them under `:omitted`, present
only when something was actually held back. A cut the agent cannot act on is just a cut.

**No `depth` param — deviates from the plan.** The plan offered bounded-depth, `find_shapes`, or
both, and recommended both. In practice a depth knob is the wrong shape: the agent cannot know
what depth it needs before looking, so it would guess, and every guess either wastes budget or
misses. `childCount` on each shape is the honest primitive — it says *"there are 2 children in
here"* without paying to list them — and `find_shapes` is how you look. That is one cheap
constant-cost call plus a targeted query, instead of a dial nobody can set correctly.

**A real bug, caught by a test I nearly didn't write.** `find_shapes` first reused
`create_shape`'s `shape-type`, which has `:rect` as its `case` default — safe there, because
that tool offers exactly three types. But `find_shapes` searches the whole vocabulary, so
`type: "text"` fell through to `:rect` and would have returned **every rectangle on the page
labelled as text**. Now a separate `searchable-types` map with no default: an unmapped type
resolves to nil and matches nothing, and the tool rejects it up front naming the real list —
because "0 found" reads as "none exist", which is the same lie in a different hat. Verified
live: `type: "text"` → 28 hits, all genuinely text.

**Payload: 13.5k, 68% of budget**, up from 66% — `childCount` costs a little. The bounds mean it
can no longer grow without limit, which is what Phases 17 and 18 need: fills and effects *per
shape* are the expensive additions, and they now land on a payload that caps and says so
instead of one that silently shreds.

## Notes — from planning

This was the third phase to edit `read_design`'s payload (after 02 and 10), and Phase 13 added a
fourth section. The "stop and redesign it as a whole" call was the right one — but the redesign
that mattered turned out to be *honesty under pressure*, not structure. **Phases 17 and 18 add
the fifth and sixth sections**; they should reuse `bounded` and keep the per-shape cost small,
since a fill list on every shape is the one addition that could still blow the budget.
