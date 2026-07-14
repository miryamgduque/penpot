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

- [ ] `SkillsCatalog.tsx` (new): render entries grouped by category in fixed order
      (Audits → Build → Auto-fix), category section headers, one card per entry
- [ ] Card shows: name, description, mode label (`suggest` / `review` / `auto-fix`);
      reflect `enabled === false` as a muted/inactive visual (no control)
- [ ] `App.tsx`: Skills tab renders `SkillsCatalog` by default; expose the existing
      `SkillsPanel` (scope/enforcement) behind a small "Advanced" affordance so it stays reachable
- [ ] `styles.css`: category headers, catalog card, mode label styling (light/dark themes)
- [ ] Cards are clickable containers (wire the click handler; detail view lands in Phase 04)
- [ ] Lint + typecheck pass (`make lint`, panel build)
- [ ] Preview review: screenshot the grouped list; confirm router/shared docs absent,
      rename-layers present but muted (off)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Note the "Advanced" affordance chosen (subtab / link / toggle)
- [ ] Confirm Phase 04 can slot the detail view into the same tab

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
