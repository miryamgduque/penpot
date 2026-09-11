# Phase 04 — Read-only detail view

**Status:** done

> Built in the **native CLJS workspace panel** (decision: the native panel is US #7's real
> home — see Phase 05), not the React prototype the original draft assumed.

## Goal

Clicking a catalog card opens a **read-only detail view** that **replaces the list within the
same tab** (not a modal, not a new tab), showing category, name, mode, an example trigger
phrase, and what-it-does. A back control returns to the list.

## Before Start

- [x] Verify Phase 05 catalog renders (native panel, `ask/catalog`)
- [x] Confirm the catalog lacked `example`/description — added `:example` + `:what`
- [x] Re-read the story's detail-view fields (name, category, mode, example, what-it-does; read-only)

## Checklist

- [x] `agent_skills.cljs`: add `:example` + `:what` to each catalog entry; `find-skill` helper
- [x] `ai_panel.cljs`: `skill-detail*` (read-only) + `skills-tab*` holds `selected` state, swapping
      list ↔ detail in place; `mode-badge*` extracted as a component (shared by card + detail)
- [x] Strictly read-only: no toggle, edit, or run/trigger control
- [x] Back control ("← All skills") returns to the list
- [x] `ai_panel.scss`: detail layout, category overline, section labels, example block
- [x] Compiles clean (shadow-cljs `:main` 0 warnings; scss compiled; `main.css` includes styles)
- [x] Preview review: verified live in the devenv workspace — list → detail → back, multiple
      skills; correct fields, colored badge, in-place swap. Screenshotted the detail view.
- [x] Human approval received (2026-07-14)
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename `todo-`/`doing-` → `done-`; update README links
- [ ] Full-story acceptance pass + plan completion summary (README)
- [ ] Follow-up decision: retire the React `ai-skills` catalog (Phase 03) now that CLJS is the home

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — `:example` + `:what`, `find-skill`
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — `mode-badge*`, `skill-detail*`, `skills-tab*`
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — detail-view styles

## Notes

- Field order (per the user's polish): **← All skills** → **category** (plain overline, Title S,
  no pill) → **title** (med-title) → **mode badge** → EXAMPLE TRIGGER PHRASE → WHAT IT DOES.
- **Bug fixed:** `mode-badge` was first written as a plain `defn` returning hiccup, which crashed
  the panel ("Objects are not valid as a React child") — rumext only compiles *literal* hiccup, so
  a vector returned from a function reaches React raw. Making it an `mf/defc` component fixed it.
- Same-tab swap is local component state (`mf/use-state`), not a route change — Chat tab and panel
  chrome untouched. Panel open-state still resets on hard refresh (by design).
