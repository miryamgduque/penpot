# Phase 06 — Vibes lifecycle UI

**Status:** done

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Check if any gaps have been filled by other work since plan creation
- [x] Review dependencies are met (Phases 04–05)
- [x] Read relevant source files to confirm assumptions (panel view routing:
      `view*` / `on-back` in `ai-panel*`)

## Checklist

- [x] ~~Tests~~ dropped (no-tests mode)
- [x] A "Project vibes" entry point — pinned card at the top of the Skills list showing set/not-set in the panel (header icon or a pinned
      card at the top of the Skills view — decide in review with the user)
      showing whether vibes are set
- [x] Vibes view: rendered markdown of the current doc (`md/markdown*`),
      with actions — **Edit** (textarea with the same ~4k cap, save via
      `design-doc/set-doc`), **Re-run interview** (sends `/vibes` through
      the chat and switches to it), **Delete** (confirm, then `clear-doc`)
- [x] Empty state: no doc yet → explain + a "Set the vibes" button that
      triggers `/vibes`
- [x] Back navigation consistent (vibes is a third leaf: vibes → list → chat) with the header-owned back pattern
      (detail → list → chat)
- [x] SCSS both themes (DS variables only); `build-app-assets.js` after changes
- [ ] Lint + typecheck pass
- [ ] Preview review — DEFERRED to post-merge live-test
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] **Plan completion:** README completion summary, status `done`, move
      folder to `completed/`, update memory + `ai-skills/BRANCH_NOTES.md`
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — vibes view + entry
  point + routing
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — styles
- `frontend/src/app/main/data/workspace/design_doc.cljs` — anything the edit
  path still needs

## Notes

- Deleting is undoable (changes pipeline) — say so in the confirm copy
  rather than over-warning.
- Collaborator behavior comes free (plugin-data syncs) but verify the view
  re-renders when another session edits the doc.
