# Phase 04 — structured frontmatter editor

**Status:** done

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Phase 03 merged (6b820ca4e6)
- [x] Read the current edit mode in `vibes-view*` (textarea + counter + problem line)

## Checklist

- [x] Tests: `edit-model` / `edit-model->frontmatter` / `empty-scaffold` — loss-free round-trip (incl. through serialize→parse), unknown typography props preserved via `:extra`, abandoned blank rows dropped, blank-name-with-value kept for `problems` to flag (new blank-token-name check in `named-map-problems`), all-empty model → nil frontmatter, scaffold demands a name; 7 new tests, 800 total green
- [x] Edit mode split: `vibes-token-form*` (name/description, color rows with live swatch + ×, typography per-role grids, rounded/spacing rows, add/remove) + body textarea; raw YAML never shown
- [x] Cap + validation run against the SERIALIZED candidate — same gate as the agent's tool path; Save disabled with inline problem
- [x] Legacy doc keeps the single textarea + "+ Add design tokens" seeds `empty-scaffold` (form round-trip unit-tested; UI path not exercised live)
- [x] Cancel restores cleanly (edit state is one atom, reset to nil); delete untouched
- [x] SCSS: vibes-form-* classes on the panel's existing patterns
- [x] Lint + typecheck + tests pass — kondo at the 13-warning baseline, cljfmt clean, main+test compile green
- [x] Live verify (Chrome on :3450, New File 2): form populated from the CostPulse doc; typed "42 not a color" into accent → problem "colors.accent — expected a CSS color string", Save disabled, counter 1184/6000; fixed to #e11d48 → Save enabled → saved; read view re-rendered with the crimson swatch; hard reload → `accent: '#e11d48'` persisted in valid YAML
- [x] Human approval received
- [x] Committed: 1a059e956e `:sparkles: Structured frontmatter editing for the vibes doc`

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — phase 05 starts the Foundations half; reconfirm scope before building (per its Before Start)

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — edit mode rework
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — form styles
- `frontend/src/app/main/data/workspace/design_md.cljs` — edit-model helpers
- `frontend/test/frontend_tests/data/design_md_test.cljs` — edit-model tests

## Notes

- This is the "edit frontmatter safely" ask from the story kickoff: the user
  never sees YAML; parse/serialize round-trips guarantee we can't corrupt what
  the interview wrote.
- GOTCHA hit during live verify: `mf/deref` on a rumext `use-state` handle
  passed to a child component throws `No protocol method IWatchable.-add-watch
  defined for type rumext.v2/State` and takes down the whole workspace behind
  the error boundary. Plain `deref` is correct — the parent re-renders on
  every `swap!` and re-renders the child with it.
- `components` tokens (`{path.to.token}` refs) are display-only in this phase —
  editing them well needs a reference picker; punt with a read-only list and a
  note. Record as follow-up if it stings.
- US #38 ultimately wants agent-only editing (user never edits raw text). The
  form editor doesn't conflict: it's the safety hatch, and the agent input
  arrives in phase 07. Revisit whether the body textarea should survive once
  the agent path exists.
