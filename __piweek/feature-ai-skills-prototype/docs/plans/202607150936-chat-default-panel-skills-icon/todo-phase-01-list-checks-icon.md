# Phase 01 — `list-checks` icon asset

**Status:** todo

## Goal

Add the Lucide **`list-checks`** icon so the header can use `i/list-checks`. Mirrors how
`forward` / `unplug` / `bot-message-square` were added: an SVG scaled to the repo's 16-viewBox
stroke convention + a `def` in `icon.cljs` + a sprite rebuild.

## Before Start

- [ ] Read an existing added Lucide icon for the exact format (`resources/images/icons/forward.svg`
      or `unplug.svg`): `viewBox="0 0 16 16"`, `stroke-linecap/linejoin="round"`, `<path>` only,
      no stroke-width (the icon CSS applies `stroke: currentColor`)
- [ ] Note the sprite gotcha (learned on `forward`): touching an icon to nudge the watch trips a
      **sprite-wipe race** that empties the whole sprite. Regenerate with a full
      `node scripts/build-app-assets.js` (copyAssets → compileSvgSprites → templates, in order).

## Checklist

- [ ] `resources/images/icons/list-checks.svg` — Lucide `list-checks` scaled 24→16 viewBox
      (×0.6667): the three check-marks + the two list lines, `<path>` elements, stroke convention
- [ ] `icon.cljs` — `(def ^:icon-id list-checks "list-checks")` in alphabetical position
- [ ] Regenerate the sprite: `docker exec … node scripts/build-app-assets.js`; confirm
      `id="icon-list-checks"` is in `resources/public/images/sprites/symbol/icons.svg`
- [ ] `:main` recompiles (so `i/list-checks` resolves); `clj-kondo` clean
- [ ] (Optional) sanity-render the icon somewhere to confirm the path looks right

## After Finish

- [ ] Rename `todo-` → `done-`; update README links

## Files

- `frontend/resources/images/icons/list-checks.svg` — **new**
- `frontend/src/app/main/ui/ds/foundations/assets/icon.cljs` — `def`

## Notes

- The generated sprite (`resources/public/images/sprites/…`) is gitignored — only the SVG source +
  the `icon.cljs` def are committed; the sprite regenerates on build.
- Lucide `list-checks` source (24-viewBox) for reference: three checks
  `m3 17 2 2 4-4` / `m3 7 2 2 4-4` and two lines `M13 6h8` / `M13 12h8` / `M13 18h8` — scale each
  coordinate by 16/24 when authoring the 16-viewBox paths.
