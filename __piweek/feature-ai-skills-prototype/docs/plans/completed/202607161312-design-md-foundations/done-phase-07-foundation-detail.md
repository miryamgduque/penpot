# Phase 07 — foundation detail + agent-guided create/edit

**Status:** done

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Phase 06 merged (7ecf645f7b)
- [x] Re-read the composer-seed flow (`dwaip/seed-composer`) — the agent-edit path rides it

## Checklist

- [x] `vibes-view*` generalized → `foundation-view*` (any slug): render + structured editor + two-click delete all reused; save/delete go through `set-foundation`/`clear-foundation` by slug; Re-run interview and the interview empty state stay vibes-only; deleting pops back to the list via `on-deleted`; phase-06's read-only `foundation-detail*` removed
- [x] Agent input on the detail (input + Ask, Enter submits): seeds the chat with "Update the '<display name>' foundation: <input>" and lands there — same pattern as the interview trigger; all seed paths unified behind one `on-seed-chat`
- [x] "+ Add a foundation" enabled: seeds "Add a new foundation to this file: " for the user to complete
- [x] `penpot-project-vibes` step 4 now saves with `set_foundation` (name "Vibes"); the other-foundations guidance (name/description frontmatter, tokens only where they earn prompt weight) lives in the set_foundation tool description — the merged description also absorbed the vibes token-schema/sections guidance from the retired tool
- [x] `set_design_doc` RETIRED: spec, impl, and dispatch removed; a test pins that calling it now fails loudly ("Unknown tool")
- [x] Tests: retirement pin + existing set_foundation boundary test; 825 tests / 2643 assertions, 0 failures; kondo baseline; cljfmt clean; main+test compile green
- [x] Live verify (Chrome, :3450 New File 2): tone detail shows ask row + Edit/Delete (no interview button); Ask → composer seeded with the scoped update prompt; + Add → composer seeded with the create trigger; Edit on tone opened the form (name/description populated, empty token sections with add buttons, 178/6000 counter), description save landed on `foundation/tone-of-voice` with vibes untouched. NOT live-run: a real model turn creating a foundation via chat (needs user key).
- [x] Human approval received
- [x] Committed: 9c2dcd6369 `:sparkles: Foundation detail with agent-guided editing` (first attempt 7952527a6e accidentally swept the other session's STAGED ai-skills/mcp deletions from the shared index — split via reset --soft + pathspec commit; the deletions are back to staged-uncommitted for their owner)

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Plan completion: README summary written, folder moved to completed/

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — detail view + agent input
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — detail styles
- `frontend/src/app/main/data/workspace/agent_skills.cljs` — vibes skill body update
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — alias retirement

## Notes

- The story's end state is agent-first editing with readable text; the phase-04
  form stays as the direct-manipulation hatch for tokens. If Miryam prefers
  strictly agent-only, hiding the form is a one-line change — ask at review.
- Out of scope (story-explicit): behavior of skills that READ foundations
  (e.g. Tone of voice checker) and promoting foundations beyond the file.
