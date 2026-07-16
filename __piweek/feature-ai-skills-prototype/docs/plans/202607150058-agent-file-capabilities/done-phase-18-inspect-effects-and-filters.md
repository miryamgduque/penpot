# Phase 18 — Inspect effects and filters

**Status:** done

The same *"replicate the selected element as accurately as possible"* transcript names the
second wall:

> 2. **Effects/filters** — any shadows, glows, or other visual effects applied

Accurate again. `summarize-shape` ([agent_tools.cljs:186](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs)) reports no `:shadow`, `:blur`, `:opacity`, `:blend-mode`, or corner radius, so the agent cannot tell whether the original *has* a shadow — let alone read its offset/blur/colour to reproduce it. It can *see* a glow in the rendered image (agent-vision) but has no structured handle on it, so any replica is a guess.

This is the read/inspect counterpart to **Phase 14**, which adds the *write* side of exactly these
attributes (`radius`, `shadow`, `opacity`, `blur`, `blend-mode`) to `modify_shape`. Phase 14 lets
the agent *apply* a shadow; this phase lets it *read the one already there*. Land them together
and the agent can inspect an effect and copy it; land only 14 and it can invent effects but not
match existing ones.

## Before Start

- [x] Re-read `summarize-shape` + `read-design`
- [x] Reuse Phase 14's attr survey — `:shadow` vector, `:blur` `{:type :value :hidden}`, `:opacity`, `:blend-mode`, radius `:r1`–`:r4`
- [x] Decide the read summary — present, non-default only: every shape carries `r1..r4 = 0` and `opacity 1`
- [x] Sequence with Phase 14 — **the descriptor is Phase 14's write params verbatim** (see Notes)

## Checklist

- [x] Write tests: a shadowed / reduced-opacity / rounded shape reports each; a plain shape reports none
- [x] `read_design` surfaces present, non-default effect attrs — on the selection and find_shapes hits, per Phase 17's payload resolution
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: **read → copy → write reproduces a look byte-identically**, live
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## Notes — what execution found

**The 1:1 mirror is the whole design, and it is literal.** The read emits `radius`, `opacity`,
and `shadow {style offsetX offsetY blur spread color opacity}` — Phase 14's write params
verbatim, not Penpot's `:r1`/`:offset-x`/`:blend-mode`. So the loop needs no translation:

```
find_shapes  → {"radius":16,"opacity":0.9,"shadow":{"offsetY":6,"blur":16,"spread":2,
                "color":"#000000","opacity":0.3},"fills":[{"type":"solid","color":"#6366f1"}]}
modify_shape ← the same values, straight back
→ the replica reads back byte-identical                        ← verified live
```

That is the phase's own goal met: *"replicate accurately"* becomes read → copy → write rather
than render → guess.

**Blur and blendMode are reported although Phase 14 cannot write them.** This deviates from a
strict 1:1 mirror, deliberately. The transcript's complaint was a **glow** it could see but not
inspect — a blur *is* that glow, so a read that omitted it would leave the original wall
standing. It is the same honesty Phase 17 gives an image fill: name what cannot be reproduced,
so the agent says *"the original also has an 8px blur"* instead of shipping a replica that
quietly lacks one. The `read_design` description says which two are read-only.

**A mixed radius is not reported as a single number.** Phase 14 writes one radius for all four
corners, so `r1=4 r2=8 r3=4 r4=8` reported as `radius: 4` would be a plausible lie — the agent
would copy it and get a different shape. It reports `{topLeft, topRight, bottomRight,
bottomLeft}` instead: not copyable in one call, and honest about it.

**Hidden effects are omitted.** A `:hidden true` shadow or blur contributes nothing to the look
and copying it would be wrong.

**Payload held.** Effects ride the same `:look?` flag as fills (renamed from `:fills?` — it now
means "paint *and* effects", which is Wave 9's own framing), so they cost nothing on the broad
list. `read_design` stayed at ~68% of the 20k budget with both phases landed — the thing Phase
17's payload fix was making room for.

**Phase 15 dogfooded itself during the verification**, unprompted: the test file had grown past
the cap, so `read_design` came back with `omitted: {shapes: "showing 60 of 65 top-level shapes —
use find_shapes to query the rest by name or type"}`, and `find_shapes` was how the source shape
was found. The bound announced itself and the escape hatch worked, on a real file, without being
aimed at.

## Notes — from planning

- Pairs with Phase 17 (fills/image) as the "read a shape's look" inspection side, and closes the
  loop with Phase 14 (write) so replicate-accurately becomes read → copy → write rather than
  render → guess.
