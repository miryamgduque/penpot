# Phase 01 — Row overflow menu + legible disabled state

**Status:** todo

## Goal

Replace the per-row `switch*` in `skills-tab*` with a discreet, always-visible **⋯ overflow menu**
(Enable/Disable wired to the US #8 state; Fork + Promote to team visible-but-disabled), make the
**disabled state legible from the list** (dimmed row + "Off" pill), and **remove the per-row mode
badge**. Detail view is untouched.

## Before Start

- [x] Read `ai_panel.cljs` `skills-tab*` + `mode-badge*` + the SCSS (`.catalog-card`,
      `.catalog-card-head`, `.catalog-toggle`, `.catalog-desc`, `.mode-badge`)
- [x] Read `app.main.ui.components.dropdown` (`dropdown`/`dropdown-content*`): `:show` + `:on-close`
      (click-outside + esc), renders children when open, caller positions. Reused it (not the
      hand-rolled model-picker) — matches the `versions.cljs` options-menu precedent.
- [x] Confirm the US #8 API to call: `refs/resolved-skills-enabled` (read) + `skst/set-skill-enabled`
      (write, optimistic/instant)
- [x] Icon: `i/menu` is the standard ⋯ overflow glyph (milestone/layers/typography use it);
      `icon-button* :variant "ghost"` for the muted trigger

## Checklist

- [x] **Menu button:** muted `i/menu` `icon-button*` in `.catalog-card-head` (always visible, not
      hover-gated; `.catalog-menu` opacity 0.6 → 1 on row/menu hover/focus). Wrapper stops
      click/keydown propagation so it doesn't open the detail view.
- [x] **Menu contents:** Enable **or** Disable (from resolved state) → `skst/set-skill-enabled`
      (instant, no confirmation); **Fork** + **Promote to team** rendered **disabled**
      (`.menu-option-disabled`, `aria-disabled`, no handler) as US #10 / US #12 entry points
- [x] **Removed** the `switch*` from the list rows (extracted a `skill-row*` component with its own
      menu state; detail keeps its `switch*` — untouched)
- [x] **Disabled state:** row dim via `.disabled` (resolved enabled) + a small **"Off" pill**
      (`.catalog-off`) in the row head, kept legible (not dimmed)
- [x] **Removed the per-row `mode-badge*`**; row shows name + description only (`mode-badge*` stays
      defined — detail still uses it)
- [x] **SCSS:** `.catalog-menu` (muted→full), `.skill-menu` (positioned dropdown), `.menu-option` +
      `.menu-option-disabled`, `.catalog-off` pill; row focus-visible retained, keyboard open/esc
- [x] Router intact: Disable still calls the US #8 path (`set-skill-enabled` → resolved state →
      `enabled-skills`), unchanged
- [x] `make lint` (clj-kondo 0 errors) + frontend build (live `:main` watch recompiled 0 warnings;
      SCSS compiled clean)
- [~] Verify in a logged-in browser: **live in the running frontend now** — menu/pill/no-badge are
      best eyeballed there (the render-wasm workspace hangs the preview tool's screenshots, a known
      devenv quirk). Compile + the proven `dropdown` pattern cover the mechanics.
- [x] Human approval received (verified live after cache-clear; polish: tighter title↔description
      gap, darker-gray ⋯ hover background)
- [x] Committed with a gitmoji commit (`:sparkles:` / `:lipstick:`)

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
