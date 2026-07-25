# Phase 08 — Recording UI

**Status:** todo

Make it usable without a console: a record control with unmistakable active
state, and a list of past sessions on the file.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Confirm Phase 07 landed
- [ ] Re-read the panel header controls (`chat-controls*` in `frontend/src/app/main/ui/workspace/ai_panel.cljs`) and the History popover for the list idiom to copy
- [ ] Check the Penpot DS for available icons — note from past sessions: there is **no `stop` glyph** and no pencil/edit glyph (`pentool` was used as a stand-in)
- [ ] Confirm whether this belongs in the agent panel or the workspace header

## Live verification debt inherited from phases 02, 03 and 04

**This phase owns every outstanding live check for the feature.** Phases 02–04
are code-traced and unit-tested but have never run in a browser: the
Claude-in-Chrome extension was unreachable throughout, and — the harder blocker —
`session-recorder` is not in the `:main` build at all until something requires
it. **This phase creates that first caller**, so it is where all of it finally
becomes observable. Do not close Phase 08 without these:

- [ ] **Console-drive a real recording** (from Phase 04): start, edit shapes by
      hand, run an agent turn, stop, read the timeline.
- [ ] **Two-session attribution** (from Phase 02): open the file in two sessions,
      edit from each, assert each side records the OTHER's profile-id on its
      incoming events. This is the check that proves "record every person working
      on the file" actually works.
- [ ] **Agent-vs-human attribution** (from Phase 03): one real agent turn plus one
      manual edit in the same recording, separated correctly with the right model
      named. Specifically watch a `create_shape` into a **laid-out board**: its
      reflow commits land ~100ms AFTER the tool returns and must read `:agent`,
      not `:user`. That is exactly what `session-actor`'s 400ms grace window
      exists for and it has never been checked against real reflow timing.
- [ ] **Reload mid-recording** (from Phase 07): start a recording, refresh the
      page, confirm it resumes with its timeline and that the row eventually
      closes with a real stop reason rather than staying open forever.
- [ ] **Noise-filter reality check** (from Phase 01): confirm `:fix-obj`,
      `:reg-objects` and `:assign`-only `:mod-obj` commits either do not appear in
      practice or get added to the noise filter. They currently classify as
      `:other` and are deliberately left visible rather than silently dropped.

## Checklist

- [ ] Require `session-recorder` AND `session-persist` from the UI — this is what
      puts them in the `:main` build and makes everything above testable
- [ ] Emit `session-persist/start-persisting` alongside `start-recording` (two
      separate subscriptions on purpose — see Phase 07's notes)
- [ ] Call `session-persist/resume-recording` on mount, so a reload mid-recording
      picks up where it left off
- [ ] **Surface `:local-only?`** — a recording whose flushes kept failing keeps
      recording but is no longer persisting; hiding that would make a
      half-persisted session look complete
- [ ] Record control: start/stop with unambiguous active state (recording must never be ambiguous — see Notes)
- [ ] Live counter while recording (events captured, elapsed)
- [ ] Session list: past sessions on this file, newest first, with participant count and duration
- [ ] Session detail: the timeline, readable, grouped by actor
- [ ] Surface the stop reason when a session ended on a cap
- [ ] Surface local-only degradation if flushes failed
- [ ] SCSS for the above — **run `build-app-assets.js` after any scss change** (the devenv SCSS watch does not pick up edits; symptom is a completely unstyled panel)
- [ ] Full suite green; `compile main` too (the test build does not compile `ai_panel.cljs`, so a paren error there passes tests)
- [ ] Lint + format
- [ ] Live review in the browser, including dark theme
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` (or a new `session_recorder.cljs` UI ns) — control + list + detail
- corresponding `.scss`
- `frontend/translations/en.po` — new i18n keys (append at EOF; this file conflicts constantly across sessions)

## Notes

**Recording state must be impossible to misread.** This captures identifiable
activity by people who did not press the button — a collaborator may be recorded
without having chosen it. The indicator should be visible to anyone on the file,
not only to the person who started it. If that turns out to need presence work
beyond this phase's scope, say so and treat it as a blocker for shipping beyond
the branch, not a nice-to-have.

That is also the moment to resolve the privacy question flagged in the README:
whether participants are told. A quiet recorder is a different product from an
announced one, and the difference is not technical.

DS gaps to expect (from past phases on this branch): `icon-button*` has no
accessible name until hover, and there is no stop glyph. Do not invent a new
icon set for this; pick the closest existing glyph and note the gap.
