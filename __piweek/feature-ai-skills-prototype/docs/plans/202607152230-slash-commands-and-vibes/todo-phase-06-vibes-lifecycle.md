# Phase 06 — Vibes lifecycle UI

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Review dependencies are met (Phases 04–05)
- [ ] Read relevant source files to confirm assumptions (panel view routing:
      `view*` / `on-back` in `ai-panel*`)

## Checklist

- [ ] Write/update tests for any new pure logic
- [ ] A "Project vibes" entry point in the panel (header icon or a pinned
      card at the top of the Skills view — decide in review with the user)
      showing whether vibes are set
- [ ] Vibes view: rendered markdown of the current doc (`md/markdown*`),
      with actions — **Edit** (textarea with the same ~4k cap, save via
      `design-doc/set-doc`), **Re-run interview** (sends `/vibes` through
      the chat and switches to it), **Delete** (confirm, then `clear-doc`)
- [ ] Empty state: no doc yet → explain + a "Set the vibes" button that
      triggers `/vibes`
- [ ] Back navigation consistent with the header-owned back pattern
      (detail → list → chat)
- [ ] SCSS both themes; `build-app-assets.js` after changes
- [ ] Lint + typecheck pass
- [ ] Preview review with MCP tools (view/edit/delete/empty state)
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
