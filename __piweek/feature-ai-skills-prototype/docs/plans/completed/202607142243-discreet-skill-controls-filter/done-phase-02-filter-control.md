# Phase 02 — Filter control (All / Enabled)

**Status:** done

## Goal

Add a filter at the top of the Skills tab with two options — **All** (enabled + disabled) and
**Enabled** (hides disabled skills). It **defaults to `Enabled` on every new session**, persists
**within** the session (across tab switches + panel close/reopen), and **resets to `Enabled` on a
new session / hard refresh** (in-memory, per the discovery decision — *not* `localStorage`).

## Before Start

- [ ] Read how `skills-tab*` renders groups/rows after Phase 01, and where a header/filter bar fits
- [ ] Read the in-memory state precedents: `[:ai-panel file-id …]` in `data/workspace/ai_panel.cljs`
      + `refs/ai-panel-*` (survives navigation, resets on hard refresh) — the model for the filter
- [ ] Confirm this is **not** `use-persisted-state` (that hook uses localStorage → persists across
      sessions, which we explicitly do **not** want)
- [ ] Pick the control: a small segmented/tab control or two buttons (reuse an existing DS control if
      one fits; otherwise a minimal local control consistent with the panel)

## Checklist

- [x] **State:** `dwaip/set-skills-filter` (ptk UpdateEvent) writes app-db `:skills-filter`;
      `refs/skills-filter` reads it, defaulting to `:enabled`. In-memory → resets on reload.
- [x] **Control:** segmented **All / Enabled** control at the top of the Skills tab; reflects +
      sets state; `.selected` styling on the active option
- [x] **Filtering:** under `:enabled`, `visible?` hides skills whose resolved-enabled is false;
      `:all` shows everything. Groups with no visible rows are dropped (`keep`) — no empty headers
- [x] **Empty state:** `.skills-empty` hint ("No enabled skills. Switch to All to see everything.")
      when the filter leaves nothing to show
- [x] Filter persists within the session (app-db, not component-local) → survives tab switch +
      panel close/reopen; resets on reload
- [x] `make lint` (clj-kondo 0 errors) + frontend build (live `:main` + SCSS recompiled, 0 warnings)
- [~] Verify in a logged-in browser (hard-refresh — HMR socket is erroring on this session): defaults
      to Enabled; disabled skills hidden under Enabled; All shows them; survives tab switch/reopen;
      reload → back to Enabled
- [x] Human approval received
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Full-story acceptance pass (README) + completion summary; move plan to `docs/plans/completed/`

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — filter control + list filtering in
  `skills-tab*`
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — filter state event (in-memory)
- `frontend/src/app/main/refs.cljs` — filter selector
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — filter control styling

## Notes

- **Deliberate deviation from the story text:** the story says default = All and "persists across
  sessions." The product owner overrode this to **default Enabled, session-only persistence**. Build
  to the override; the README records why.
- **Final phase of the story:** on completion run the plan-completion protocol (docs update +
  completion summary + move to `docs/plans/completed/`).
