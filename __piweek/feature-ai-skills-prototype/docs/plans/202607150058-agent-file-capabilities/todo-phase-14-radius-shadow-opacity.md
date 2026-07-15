# Phase 14 — Radius, shadow, opacity

**Status:** todo

`modify_shape` covers name, x, y, width, height, fill, stroke. Styling is 40 mentions across
11 skills, and the rest of it is unreachable: `borderRadius` (6), `shadows` (2), `opacity` (2),
blend mode, per-corner radius, blur.

The transcript shows the cost. Asked for a hover variant, the agent set a white fill and wrote
*"White fill (ready for shadow)"* — it knew the design answer was a shadow and had no way to
apply one, so it staged the fill and narrated the intent. Its `Hierarchy=Outlined` variant is
the same story: "light gray bg + border" because a real outlined card wants a radius and a
subtle shadow it couldn't reach.

## Before Start

- [ ] Re-read `modify-shape` (agent_tools.cljs:300) and how it splits work between `dwsh/update-shapes`, `dwt/update-position`, `dwt/update-dimensions`
- [ ] Confirm the shape attr keys: `:rx`/`:ry` vs `:r1`-`:r4` for radius, `:shadow` vector shape, `:opacity`, `:blend-mode` — see `common/src/app/common/types/shape/attrs.cljc` and the shadow schema
- [ ] Check whether shadows can be set through `update-shapes` directly or need a dedicated event (the plugin has `ShadowProxy` with `style`/`offsetX`/`offsetY`/`blur`/`spread`/`color`)
- [ ] **Check the guard.** `color-violation` (agent_tools.cljs:255) rejects raw hex on `modify_shape` when `token-only-colors` is enforced. A shadow carries a color. Widening `modify_shape` without widening the guard opens a hole straight through the enforcement layer

## Checklist

- [ ] Write tests, including the guard case above
- [ ] Widen the `modify_shape` spec
- [ ] Extend `modify-shape`
- [ ] Extend `color-violation` to cover shadow colors
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: the transcript's own hover variant — a real shadow, not a white fill
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Notes

**The guard is the point of this phase, not an afterthought.** `token-only-colors` exists to
stop raw hex reaching shapes. Today it watches `create_shape` and `modify_shape` fills and
strokes. Add a shadow color to `modify_shape` and there is a second, unwatched path for raw hex
— and it will be the *most* used one, because shadows are where designers reach for one-off
colors. Widen `color-violation` in the same commit, and add the test that proves a raw shadow
color is rejected under enforcement.

**Radius wants a token.** `borderRadius` is a real token type (Phase 08) and `:r1`-`:r4` are in
`all-keys` (Phase 09). If Wave 3 has landed, this phase's radius param should carry a note
pointing at `apply_tokens` for the tokenized path — same relationship `fill` already has.

Blur and blend mode are in the plugin surface but have near-zero demand in the skills. Skip
unless something asks.
