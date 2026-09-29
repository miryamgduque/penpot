# Phase 14 — Radius, shadow, opacity

**Status:** done

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

- [x] Write tests, including the guard case above
- [x] Widen the `modify_shape` spec
- [x] Extend `modify-shape`
- [x] Extend `color-violation` to cover shadow colors — **via a shared `input-colors` collector** (see Notes)
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: the transcript's own hover variant — a real shadow, not a white fill
- [x] **Guard hole verified closed live, under real enforcement**
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Notes — what execution found

**The guard was the phase, and the hole was real.** `token-only-colors` watched `fill` and
`stroke`. A shadow carries a colour, so widening `modify_shape` to shadows would have opened a
second, *unwatched* path for raw hex — and the most-used one, because a shadow is exactly where
a designer reaches for a one-off colour. Verified live under real enforcement:

```
fill   "#ff0000"            → rejected
stroke "#00ff00"            → rejected
shadow {color: "#ff00ff"}   → rejected   ← the new path, watched
radius 20                   → allowed    ← the guard is about colour, not styling
```

**Closed by construction, not by remembering.** Rather than adding a third `color-violation`
call, both colour-setting tools now collect through one `input-colors` helper. `create_shape`
routes through it too even though it only takes `fill` today — so the next param that carries a
colour is guarded by default instead of depending on someone recalling this note. A guard you
have to remember to extend is a guard that will eventually be forgotten.

**A shadow's colour is a map, not a string.** `schema:color` is `{:color "#…" :opacity 0.25}`;
a bare hex fails the schema. The tool takes a flat `color` + `opacity` (what the agent expects)
and builds the map. Pinned by a test.

**`opacity 0` is kept** — `some?`, not truthiness. Fourth outing for this trap (Phase 07's
`absolute false`, Phase 08's `"0"` token, Phase 13's `x: 0`). Every one has a test.

**The transcript's own hover state, done properly.** Its agent set a white fill and narrated
*"White fill (ready for shadow)"* — it knew the design answer and had no tool. Now:
`radius 12` on all four corners, `opacity 0.9`, and a real
`{style: drop-shadow, offset-y: 4, blur: 12, color: {#000000, opacity: 0.25}}`.

The description says a shadow is the answer for depth or a hover state *"instead of faking
elevation with a paler fill"* — naming the exact workaround the transcript reached for, so the
next agent does not.

Blur and blend-mode stay out: near-zero demand in the skills, and this phase already carries
the guard change.

## Notes — from planning

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
