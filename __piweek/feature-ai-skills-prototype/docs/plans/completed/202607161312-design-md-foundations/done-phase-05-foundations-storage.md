# Phase 05 — foundations storage + tools

**Status:** done

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Phases 01–04 merged; Santi green-lit the Foundations half ("let's do it", 2026-07-16)
- [x] Re-read `design_doc.cljs` and the `set_design_doc` impl

## Checklist

- [x] Tests first: 8 new design-doc tests (slugify, display-name, legacy key = Vibes, vibes-first-then-sorted listing, blank docs excluded, prompt section per-foundation, nil when none) + 1 tool-boundary test (unusable name rejected naming the fix); registered in runner require + vector; 809 total green
- [x] `design_doc.cljs` extended in place —
  - [x] storage: Vibes KEEPS `"design-md"` forever (simpler than the planned read-both/write-new: no dual-key ambiguity, old clients just work); other foundations under `"foundation/<slug>"`
  - [x] model: foundation = DESIGN.md-format doc; `slugify`/`display-name` for the name↔slug round trip
  - [x] `foundations-ref` + `get-foundation`/`list-foundations`/`set-foundation`/`clear-foundation`; `get-doc`/`set-doc`/`clear-doc`/`doc-ref` kept as the vibes aliases; `doc-problem` already doc-agnostic
  - [x] `system-prompt-section` → "## Foundations (standing design context for this file)" + one `###` per foundation, vibes first; honor-and-flag-conflicts + token-source-of-truth wording kept
- [x] `set_foundation` tool (name + doc, empty removes, slug validated first so the error is testable and actionable); `set_design_doc` kept as the vibes alias; both descriptions + the vibes skill body now point at 'Foundations'
- [x] Lint + typecheck + tests pass — kondo 0 warnings on all touched files, cljfmt clean, main + test compile green
- [x] Live sanity (Chrome, :3450 New File 2): `set_foundation` saved "Tone of voice" → slugs `[vibes tone-of-voice]`, prompt assembles both subsections, vibes still reads from the legacy key. The Tone of voice foundation is LEFT in the file for phase 06 UI verification.
- [x] Human approval received
- [x] Committed: 9aee08561a `:recycle: Generalize the vibes doc into per-file foundations`

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — phase 06 (compass icon + list) proceeds as planned; Tone of voice test data is waiting in New File 2

## Files

- `frontend/src/app/main/data/workspace/design_doc.cljs` — multi-entry model
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — set_foundation tool
- `frontend/test/frontend_tests/data/design_doc_test.cljs` (or foundations_test) — CRUD/prompt tests

## Notes

- Total prompt weight: N foundations × cap is a standing tax on every turn.
  Keep the per-doc cap, and surface the combined size in the panel later if it
  becomes real. For now users will have 1–3 foundations.
- The refs-cycle gotcha stands: reactive refs for this data live in
  design_doc/foundations ns, NOT app.main.refs.
- Scope guard: per US #38, no per-skill foundation selection, no sharing beyond
  the file.
