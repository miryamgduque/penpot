# Phase 03 — Catalog list UI

**Status:** todo

## Goal

Render the built-in catalog as the **default Skills-tab view**: cards grouped under
**Audits / Build / Auto-fix** headers, each showing name, short description, and a fixed
mode label. Keep the existing scope/enforcement view reachable (build alongside).

## Before Start

- [ ] Verify Phase 02 landed and `payload.catalog` is populated in the panel
- [ ] Re-read `App.tsx` Skills-tab wiring and `styles.css` card/badge classes to reuse
- [ ] Confirm ai-skills preview flow (`npm run build` in `ai-skills/`, vite preview on :4500)

## Checklist

- [x] `SkillsCatalog.tsx` (new): render entries grouped by category in fixed order
      (Audits → Build → Auto-fix), category section headers, one card per entry
- [x] Card shows: name (humanized from skill name), blurb (derived), mode label
      (`suggest`/`review`/`auto-fix`); `defaultEnabled === false` → muted card + "off by default"
- [x] `App.tsx`: `SkillsTab` renders `SkillsCatalog` by default; existing `SkillsPanel`
      behind an "Advanced — scopes & enforcement" link (shown only when a host payload is present)
- [x] `styles.css`: category headers, catalog card, colored mode badges (design-system `--app-*`)
- [x] Cards are clickable containers (`onOpen` handler wired; detail view lands in Phase 04)
- [x] Typecheck pass (`ai-skills` tsc clean). `make lint` not run (see Phase 01 note)
- [x] Preview review: verified in the preview tool. Live dev server on :4600 hung
      `preview_screenshot` (Vite HMR socket keeps the page busy); screenshotted the static
      production `dist/` build on :8793 instead. Confirmed: 10 entries, router + shared docs
      absent, rename-layers muted/off, curated crisp blurbs, badges = real `--app-*` tokens, bg `#18181a`
- [x] Curated label/blurb map added (matches the approved mockup), with derived fallback for unlisted skills
- [x] Human approval received (2026-07-14)
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename `todo-` → `done-`; update README links
- [x] Advanced affordance: a bottom "Advanced — scopes & enforcement" link, shown only when a host payload is present
- [x] Phase 04 slots in via `onOpen` (already wired) + local `selected` state in SkillsCatalog

## Files

- `ai-skills/src/ui/SkillsCatalog.tsx` — **new** grouped list
- `ai-skills/src/ui/App.tsx` — default Skills tab → catalog; keep scope view reachable
- `ai-skills/src/ui/styles.css` — catalog card / category header / mode label styles

## Notes

- "Build alongside": the scope/enforcement `SkillsPanel` is not deleted; make it reachable
  (e.g. an "Advanced" link at the bottom of the catalog, or a secondary subtab). Match the
  panel's existing header/button idiom rather than inventing new chrome.
- Match the mockup: category headers in caps (AUDITS / BUILD / AUTO-FIX), cards with name +
  one-line description + mode. Reuse existing `.skill-card` styling where sensible.
- **Decision (2026-07-14): mode badges are colored** (not monochrome), reusing the design-system
  `--app-*` palette via `color-mix`, per the approved mockup: `suggest → --app-blue`,
  `review → --app-purple`, `auto-fix → --app-gold`. Keep the same mapping in the detail view.
- No toggles, no editing, no run buttons on cards — the whole card is a navigation target.
