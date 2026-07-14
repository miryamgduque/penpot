# Phase 01 — Row overflow menu + legible disabled state

**Status:** todo

## Goal

Replace the per-row `switch*` in `skills-tab*` with a discreet, always-visible **⋯ overflow menu**
(Enable/Disable wired to the US #8 state; Fork + Promote to team visible-but-disabled), make the
**disabled state legible from the list** (dimmed row + "Off" pill), and **remove the per-row mode
badge**. Detail view is untouched.

## Before Start

- [ ] Read `ai_panel.cljs` `skills-tab*` + `mode-badge*` + the SCSS (`.catalog-card`,
      `.catalog-card-head`, `.catalog-toggle`, `.catalog-desc`, `.mode-badge`)
- [ ] Read `app.main.ui.components.dropdown` (`dropdown*`/`dropdown-content*`): props, click-outside
      + esc, positioning; decide reuse vs. the hand-rolled model-picker pattern in this same file
- [ ] Confirm the US #8 API to call: `refs/resolved-skills-enabled` (read) + `skst/set-skill-enabled`
      (write, optimistic/instant)
- [ ] Check the DS icon set for a "⋯" / more/menu-horizontal glyph (`i/…`) and a small icon-button

## Checklist

- [ ] **Menu button:** add a muted ⋯ icon-button in `.catalog-card-head` (always visible, not
      hover-gated); it must **stop click/keydown propagation** so opening the menu doesn't open the
      card's detail view (same guard the old toggle wrapper used)
- [ ] **Menu contents:** Enable **or** Disable (whichever applies from resolved state) → calls
      `skst/set-skill-enabled` (instant, no confirmation); **Fork** and **Promote to team** rendered
      **disabled** (no handler) as entry points for US #10 / US #12
- [ ] **Remove** the `switch*` from `skills-tab*` rows (detail keeps its `switch*` — do not touch)
- [ ] **Disabled state:** keep the row dim driven by resolved enabled (already `.disabled`), and add
      a small **"Off" pill** in the row head when disabled (replaces the removed US #7 "off by
      default" concept)
- [ ] **Remove the per-row `mode-badge*`** from `.catalog-desc`; row shows name + description only
      (keep `mode-badge*` the component — still used by detail)
- [ ] **SCSS:** style the ⋯ button (muted → full contrast on hover/focus), the menu, the "Off" pill;
      keep focus-visible + keyboard operability (menu reachable and dismissable via keyboard)
- [ ] Router intact: toggling Disable from the menu still drops the skill from `enabled-skills` /
      `get_design_skills` (US #8 behavior — verify unchanged)
- [ ] `make lint` (clj-kondo) + frontend build (shadow `:main`), 0 warnings
- [ ] Verify in the devenv / a logged-in browser: menu opens, Enable/Disable flips + persists,
      Fork/Promote are inert, disabled rows dim + show "Off", no mode badge on rows
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:` / `:lipstick:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Note the menu component choice + any shared bits Phase 02 reuses

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — `skills-tab*` rows: ⋯ menu, remove switch +
  badge, "Off" pill
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — menu button/menu, "Off" pill

## Notes

- **Accessibility:** the ⋯ menu must be operable without hover and via keyboard (the story calls out
  touch + discoverability explicitly). Prefer the shared `dropdown*` for correct focus/esc handling.
- Fork/Promote are **disabled entry points only** — no placeholder actions, no toasts (discovery
  decision). They become live in US #10 / US #12.
