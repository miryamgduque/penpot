# Phase 04 — Read-only detail view

**Status:** todo

## Goal

Clicking a catalog card opens a **read-only detail view** for that skill that **replaces the
list within the same tab** (not a modal, not a new tab), showing name, category, mode, an
example trigger phrase, and what-it-does. A back control returns to the list.

## Before Start

- [ ] Verify Phase 03 landed and cards navigate (click handler wired, list renders)
- [ ] Confirm `CatalogEntry` carries `example` and `description` (from Phase 01)
- [ ] Re-read the story's detail-view mockup for field order/labels

## Checklist

- [ ] Detail view (in `SkillsCatalog.tsx` via local `selected` state, or `SkillDetail.tsx`):
      back control ("← Back to skills"), skill name, category tag, mode tag,
      "EXAMPLE TRIGGER PHRASE" block, "WHAT IT DOES" description
- [ ] List ↔ detail is same-tab view swap (local state in the catalog component); no modal/route
- [ ] Strictly read-only: no toggle, no edit, no run/trigger control anywhere in the view
- [ ] Back control returns to the exact list (state preserved)
- [ ] `styles.css`: detail layout, tags, section labels (light/dark)
- [ ] Lint + typecheck pass (`make lint`, panel build)
- [ ] Preview review: screenshot list → detail → back for an audit, a build, and rename-layers
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Full-story acceptance-criteria pass (README); note any deferred polish
- [ ] Complete the plan: update docs, write completion summary, move folder to `completed/`

## Files

- `ai-skills/src/ui/SkillsCatalog.tsx` (and/or new `SkillDetail.tsx`) — detail view + swap
- `ai-skills/src/ui/styles.css` — detail view styles

## Notes

- Mockup field order: name → `Category` tag + `mode` tag → EXAMPLE TRIGGER PHRASE → WHAT IT DOES.
- Same-tab swap is explicit in the story: keep it as local component state, not a router change,
  so the Chat tab and panel chrome are untouched.
- This is the last phase — on completion, run the plan-completion protocol (docs update +
  completion summary + move to `docs/plans/completed/`).
