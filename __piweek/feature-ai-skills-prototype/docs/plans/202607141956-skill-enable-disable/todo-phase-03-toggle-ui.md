# Phase 03 — Toggle UI (catalog cards + detail view)

**Status:** todo

## Goal

Add a toggle to every skill — in the catalog **list** cards and the **detail** view — wired to
Phase 02's state. Instant, no confirmation. A disabled skill reads as muted and drops from the
router immediately.

## Before Start

- [ ] Decided: the card toggle writes the **per-file, per-user** override for the current file
      (via Phase 02's `set-skill-enabled` with the current file-id)
- [ ] Read `ai_panel.cljs` `skills-tab*` (cards) + `skill-detail*` and `ai_panel.scss`
- [ ] Read the DS `switch*` component API (`ui/ds/controls/switch.cljs`)
- [ ] Confirm the Phase 02 selector (`skill-enabled?`) + action to call

## Checklist

- [ ] Card: add a `switch*` toggle in `skills-tab*`; `checked` = resolved enabled; `on-change` →
      Phase 02 action; stop the click from opening the detail view (toggle is not a card open)
- [ ] Detail: add the same toggle in `skill-detail*` (per the story: both views)
- [ ] Disabled state: keep the existing muted styling driven by the resolved enabled flag
      (not just the static default)
- [ ] Instant, no confirmation for any skill (the story: no warning/confirmation flow anywhere)
- [ ] `ai_panel.scss`: toggle placement/alignment in card + detail
- [ ] Verify in the devenv: toggling a skill off removes it from `get_design_skills` / the
      system-prompt index for the next turn
- [ ] `make lint` + typecheck; frontend build
- [ ] Preview review (screenshot list + detail with toggles)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Full-story acceptance pass (README) + completion summary; move plan to `completed/`

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — toggles in `skills-tab*` + `skill-detail*`
- `frontend/src/app/main/ui/workspace/ai_panel.scss`

## Notes

- **Final phase of the story:** after this, toggling + persistence + router integration are
  complete — that's the whole of US #8. On completion run the plan-completion protocol (docs
  update + completion summary + move to `docs/plans/completed/`).
