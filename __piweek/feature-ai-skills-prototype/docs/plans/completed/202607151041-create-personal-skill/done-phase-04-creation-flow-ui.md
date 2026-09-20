# Phase 04 — Skills-view guided creation flow

**Status:** done

## Goal

The visible feature: a **"Create skill"** action in the Skills view that opens a **guided flow**
(what → trigger → mode, with a proposed default mode), then **generates** (Phase 03) and **creates**
(Phase 01) the skill so a **card appears in the list, default on**.

## Before Start

- [ ] Re-read the Skills view in `ai_panel.cljs`: `skills-tab*` (list/detail), the `ai-panel*`
      header (adaptive: back + title) and the `:skills` view + `skill*`/`on-select` state (US #35)
- [ ] Confirm the Phase 03 `generate-skill` API and the Phase 01/02 `create-skill` action
- [ ] Decide where the flow lives: a third Skills sub-state alongside list/detail — e.g. lift a
      `skill-view` (`:list` | `:detail` | `:create`) so the header back pops **create → list** too

## Checklist

- [x] **Entry:** "+ Create skill" button in a `.skills-toolbar` beside the All/Enabled filter;
      `skills-tab*` gets `on-create`, `ai-panel*` owns `creating*` and opens the flow.
- [x] **Guided capture UI (`skill-create*`):** **what** (textarea), **trigger** (input), **mode**
      (three buttons) with a **proposed default** from `propose-mode` (report-ish → suggest;
      build-ish → review; fix-ish → autofix) marked "· suggested"; user overrides. Submit disabled
      until "what" is non-empty and a model is connected.
- [x] **Header:** `creating?` shows `←` + "New skill"; back pops create → list (the header's
      `on-back` now branches create/detail/list, in deps). `seed` prop prefills "what" (Phase 05).
- [x] **Generate + create:** `dusk/create-from-answers` = `generate-skill` → `:create-skill` →
      refetch; "Generating…" busy state; on success closes the flow so the **new card shows in the
      list** (story: "appears as a card in the list"), filed under its category, default on; on error
      a retry message.
- [x] **No-provider guard:** `skill-create*` shows "Connect an AI model to create skills" + the
      Integrations link when `settings` is nil (submit also guarded).
- [x] SCSS: `.skills-toolbar`, `.create-skill-btn`, `.skill-create` + form fields / mode buttons /
      submit, reusing tokens.
- [x] `clj-kondo` 0 errors; `:main` + SCSS build 0 warnings
- [~] Verify live (user, in a logged-in browser): create the "Tone of voice checker" end-to-end → card appears under Audits, on,
      and `get_design_skills` returns it with a body — **needs a connected provider (best in the
      logged-in browser; the render-wasm workspace hangs the preview tool)**
- [x] Human approval received
- [x] Committed (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — create action + guided flow + header state
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — create form styling

## Notes

- The `:list | :detail | :create` sub-state generalizes US #35's list↔detail nav; the single header
  back keeps popping one level.
- Interview stays **guided capture** (discovery decision) — no open-ended follow-up loop; the model's
  work is in generation, not conducting the interview.
