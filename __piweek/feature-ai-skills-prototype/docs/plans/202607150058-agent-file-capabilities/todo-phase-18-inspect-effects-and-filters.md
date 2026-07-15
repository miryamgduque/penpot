# Phase 18 — Inspect effects and filters

**Status:** todo

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

- [ ] Re-read `summarize-shape` + `read-design` (agent_tools.cljs:166–193)
- [ ] Reuse Phase 14's attr survey: `:shadow` vector (`{:style :offset-x :offset-y :blur :spread :color :hidden}`), `:blur` (`{:type :value}`), `:opacity`, `:blend-mode`, radius (`:rx`/`:ry` vs `:r1`–`:r4`) — `common/src/app/common/types/shape/attrs.cljc`
- [ ] Decide the read summary: only emit attrs that are *present and non-default* (a shape with no shadow shouldn't add a `:shadow` key), to keep read_design compact — same 20k-truncation constraint as Phase 17
- [ ] Sequence with Phase 14: define the read descriptor to mirror Phase 14's write params 1:1, so "read then write" is a straight copy for the model (no attr-name translation)

## Checklist

- [ ] Write tests: a shape with a shadow + reduced opacity + corner radius reports each; a plain shape reports none of them
- [ ] `read_design` surfaces present, non-default effect/style attrs per shape
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv)
- [ ] Preview review: give the agent a shadowed, rounded shape and confirm it reads the effect, then (with Phase 14) reproduces it on a replica

## Notes

- Pairs with Phase 17 (fills/image) as the "read a shape's look" inspection side, and closes the
  loop with Phase 14 (write) so replicate-accurately becomes read → copy → write rather than
  render → guess.
