# Phase 06 — Foundations list view (compass icon, list over chat)

**Status:** done

## Before Start

- [x] Verify plan is still valid — NOTE: 10 concurrent commits landed on the branch mid-phase (agent capabilities 23–32, another session); they touch only `agent_tools.cljs` + its tests, zero overlap with this phase's files
- [x] Phase 05 merged (9aee08561a)
- [x] Re-read the `ai-panel*` nav state and the header band

## Checklist

- [x] Lucide `compass` icon added (16×16 house format: no width/stroke attrs, scaled from the 24px original), registered in icon.cljs, sprite rebuilt — renders in the header
- [x] Header: compass button before `list-checks`; Foundations view swaps to back-arrow + "Foundations" / the open foundation's display name
- [x] Nav: `view*` gains `:foundations` + `foundation*` slug state; back pops detail → list → chat (`on-back` now branches on `view` so Skills and Foundations levels can't cross)
- [x] Foundations list per wireframe: "Applies to this file" marker; cards with glyph (`swatches` vibes / `comments` tone-of-voice / `document` default — tiny hardcoded map), display-name title, summary from frontmatter `description` else first non-heading body line; "+ Add a foundation" rendered DISABLED with a title naming phase 07 (chose visible-but-inert over hidden); empty state seeds the vibes interview
- [x] Vibes relocated: pinned card removed from Skills (`on-open-vibes`/`vibes?*` plumbing gone, dead `.vibes-card*` scss replaced); read-mode doc rendering extracted to `design-doc-view*`, reused by `vibes-view*` AND a minimal read-only `foundation-detail*` for non-vibes slugs (edit/remove/agent input are phase 07)
- [x] Lint + typecheck pass — kondo baseline only, cljfmt clean, main+test green, 824 tests 0 failures (count grew via the concurrent commits)
- [x] Live verify (Chrome, :3450 New File 2): compass renders; list shows both cards with icons + summaries; Vibes card → full vibes view titled "Vibes"; Tone of voice card → read-only detail titled "Tone of voice"; back chain detail → list → chat correct; Skills tab starts at the toolbar (no pinned card); no new console errors. Both-themes check NOT done (devenv profile is dark; the styles use the same var pairs as the rest of the panel).
- [x] Human approval received
- [x] Committed: 7ecf645f7b `:sparkles: Foundations list behind a compass header icon`

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — phase 07 proceeds as planned

## Files

- `frontend/resources/images/icons/compass.svg` — new
- `frontend/src/app/main/ui/ds/foundations/assets/icon.cljs` — register icon
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — nav + list view
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — list styles

## Notes

- Story: "Chat remains the unlabeled home; Foundations and Skills are both
  visited destinations reached by their header icons."
- Card icons: wireframe shows distinct glyphs per foundation (sparkle for
  Vibes, speech for Tone of voice). Frontmatter could carry an `icon` field
  (Lucide name, from the DS set) with a default; keep the mapping tiny.
- DS `icon-button*` aria-label→tooltip bug (empty node until hover) is known
  app-wide; don't fight it here.
