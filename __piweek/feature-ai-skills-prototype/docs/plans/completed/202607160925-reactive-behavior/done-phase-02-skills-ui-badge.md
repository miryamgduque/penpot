# Phase 02 — Skills UI badge

**Status:** done (2026-07-16) — `reactive-badge*` (On-call neutral / Observer
accent) on the card **and** detail; empty when reactive is blank. Verified live
in the Mars file: Observer accent on Tokens governance audit + Rename layers,
On-call on the rest.

## Goal

Surface reactive behavior as a badge on the skill **card** and the **detail**
view, next to the category — and retire the old mode badge.

## Before Start

- [ ] Re-read in [ui/workspace/ai_panel.cljs](../../../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs):
      `mode-badge*` (:895), `skill-detail*` (:905), `skill-row*` (:927) — the card,
      which per US #30 currently shows only label + blurb + an "Off" pill.
- [ ] Re-read the badge SCSS (`mode-badge`, `mode-suggest/review/autofix`) in
      `ai_panel.scss`.

## Checklist

- [ ] Rename `mode-badge*` → `reactive-badge*`; prop `:reactive`; classes
      `reactive-badge` + `reactive-oncall` / `reactive-observer`; label via
      `ask/reactive-label`.
- [ ] SCSS: replace the three `mode-*` badge styles with two `reactive-*` ones —
      Observer visually distinct (it is the ambient/watching one); keep it subtle
      per house style. Rebuild sprite/CSS with `node scripts/build-app-assets.js`
      if the SCSS watch is stale.
- [ ] `skill-detail*`: swap `mode-badge*` → `reactive-badge*` (category already
      renders at :914 — the badge sits alongside it in `detail-tags`).
- [ ] `skill-row*` (card): add the `reactive-badge*` to the card head (US #14
      wants it on the card too). Keep the "Off" pill behavior; don't reintroduce a
      hover-gated control — the badge is always visible.
- [ ] Thread `:reactive` through wherever `skill-row*` / `skill-detail*` get their
      data (the resolved catalog entry already carries it after Phase 01).
- [ ] Compile 0 warnings; `clj-kondo` clean.

## Notes

- Exact visual treatment is "not yet defined" in the story — pick a restrained
  On-call (neutral) vs Observer (accent) treatment; easy to retune later.
- Verify in preview both themes (light/dark) via `resize_window` colorScheme.
