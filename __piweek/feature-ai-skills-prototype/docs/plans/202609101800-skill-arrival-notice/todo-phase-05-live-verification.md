# Phase 05 — Live verification + wrap-up

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Confirm Phases 01-04 are all `done`
- [ ] devenv is up and both a promoter account and a second team-member account are available (see `devenv-operations` skill if the container needs starting/restarting)

## Checklist

- [ ] As account A: promote a personal skill to the team (US #12 flow)
- [ ] As account B (same team): open the Agent panel, confirm the arrival notice card appears with correct skill name/promoter/description, and the list row shows the accent highlight + "NEW" pill
- [ ] Confirm account A (the promoter) sees **no** notice and **no** highlight for their own promotion
- [ ] Click **Dismiss** on account B's notice — confirm it disappears and does not return after a full page reload
- [ ] Promote a second skill; this time click **View skill** instead of Dismiss — confirm the detail view opens (existing story #8 view, unmodified) and the notice does not reappear after reload
- [ ] Confirm the list highlight fades ~20s after the list is visited, and reappears if the panel is closed/reopened before the notice was ever dismissed/viewed (expected per the "seen-marking rule")
- [ ] Confirm disabling a skill still works exactly as before (story #8 toggle untouched) from both the list row and the detail view
- [ ] Write the Completion Summary in `README.md` (what shipped, what changed from the original plan, follow-ups)
- [ ] Update README.md status to `done`
- [ ] Move the whole plan folder to `__piweek/feature-ai-skills-prototype/docs/plans/completed/`
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix (before the folder move)
- [ ] Confirm no other plan cross-references this folder's old path

## Files

- `README.md` — completion summary + status
- (folder move, no other file changes expected)

## Notes

Mirror US #12's live-verification write-up style (`completed/202607161238-promote-skill-to-team/README.md`'s "Live verification" section) when documenting results here.
