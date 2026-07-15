# Phase 04 — Skills-view guided creation flow

**Status:** todo

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

- [ ] **Entry:** a "Create skill" action in the Skills view (a button at the top of the list, near
      the All/Enabled filter). Opens the create flow (`:create`).
- [ ] **Guided capture UI:** collect **what** (freeform), **trigger / example phrase** (freeform),
      **mode** (🔍 suggest / ✏️ review / ⚡ auto-fix) — with a **proposed default** derived from the
      "what" (heuristic: report-ish → suggest), user confirms or overrides. Keep it a light stepped
      form, not a rigid all-at-once form; validate non-empty "what".
- [ ] **Header:** in `:create` the header shows `←` + a title ("New skill"); back returns to the
      list (US #35 header owns nav). Seeded description (from Chat, Phase 05) prefills "what".
- [ ] **Generate + create:** on submit → `generate-skill` (loading state; disable submit) →
      `create-skill` → refetch → land on the **new skill's card / detail**, filed under its category,
      **default on**. Surface generation/creation errors with a retry.
- [ ] **No-provider guard:** if no model is connected, the flow explains it needs one (link to
      Integrations) rather than failing at submit.
- [ ] SCSS for the create form + loading state; reuse existing tokens/controls.
- [ ] `make lint` + frontend build, 0 warnings
- [ ] Verify live: create the "Tone of voice checker" end-to-end → card appears under Audits, on,
      and `get_design_skills` returns it with a body
- [ ] Human approval received
- [ ] Committed (`:sparkles:`)

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
