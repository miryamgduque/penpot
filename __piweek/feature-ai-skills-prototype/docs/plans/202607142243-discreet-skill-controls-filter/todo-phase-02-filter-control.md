# Phase 02 — Filter control (All / Enabled)

**Status:** todo

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

- [ ] **State:** hold the filter in app-db (in-memory), default `:enabled`. A ptk event to set it +
      a `refs`/selector to read it. Resets to `:enabled` on reload because it is never persisted.
- [ ] **Control:** render an **All / Enabled** filter at the top of the Skills tab (above the
      groups); reflects + sets the state; clear active-option styling
- [ ] **Filtering:** when `Enabled`, hide disabled skills (resolved-enabled = false) from the list;
      when `All`, show everything. Category groups with no visible rows under the current filter are
      hidden (no empty headers)
- [ ] **Empty state:** if `Enabled` and everything is disabled (nothing to show), show a small
      "No enabled skills — switch to All" style hint rather than a blank tab
- [ ] Filter persists within the session: switch to Chat and back, or close/reopen the panel → the
      choice is retained (because it lives in app-db, not component-local state)
- [ ] `make lint` + frontend build, 0 warnings
- [ ] Verify in a logged-in browser: defaults to Enabled on load; disabled skills hidden under
      Enabled; All shows them; selection survives tab switch + panel reopen; **reload → back to
      Enabled**
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

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
