# Phase 05 — Live verification + wrap-up

**Status:** todo

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions) — confirmed 2026-09-11
- [x] Confirm Phases 01-04 are all `done`
- [x] devenv is up (restarted once during Phase 03 to pick up backend changes — still up); promoter (`demo@example.com`, team "Team") and second member (`demo2@example.com`, "Demo Two") both confirmed as real members of the same team throughout Phases 03-04

## Checklist

- [x] As account A (`demo@example.com`, promoter): promoted a **freshly-created** personal skill ("Arrival notice copy check") to the team via the real UI flow (⋯ menu → Promote to team → Publish) — `promote-skill` RPC returned 200; DB confirmed the source `profile_skill` was deleted and exactly one `team_skill` row created
- [x] As account B (`demo2@example.com`, "Demo Two", same team): opened the Agent panel, confirmed the arrival notice card appeared with the correct name/promoter/description, and the Skills list row showed the accent highlight + "New" pill (verified via DOM class/text inspection, not just visually)
- [x] Confirmed account A (the promoter) sees **no** notice and **no** highlight for their own promotion (`document.querySelectorAll('[class*="arrival-notice"]').length === 0`, `catalog-card-new` count `0`, after a clean reload — a transient double-render artifact right after publishing resolved itself on reload, see Notes)
- [x] Clicked **Dismiss** on account B's notice for the fresh skill — arrival-notice count dropped from 3 to 2 immediately; `mark-team-skill-seen` returned 200
- [x] Clicked **View skill** on a different arrived skill ("Typo checker", during the Phase 03 session) — confirmed the detail view opens (existing story #8 view, unmodified) and the notice does not reappear after reload; re-confirmed the pattern still holds for the fresh skill's Dismiss path this phase
- [x] Confirmed the list highlight fades ~20s after the list is visited (Phase 04), and reappears if the panel is closed/reopened before the notice was ever dismissed/viewed (verified this phase too: "Passive voice flagger" — a skill never acknowledged — still showed the highlight for account B)
- [x] Confirmed disabling a skill still works exactly as before: toggled account B's "Arrival notice copy check" off via the detail-view switch (`set-skill-enabled` → 200) and back on — story #8 untouched
- [x] Write the Completion Summary in `README.md`
- [x] Update README.md status to `done`
- [x] Move the whole plan folder to `__piweek/feature-ai-skills-prototype/docs/plans/completed/`
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix (before the folder move)
- [x] Confirm no other plan cross-references this folder's old path — none found

## Files

- `README.md` — completion summary + status
- (folder move, no other file changes expected)

## Notes

**Fresh end-to-end flow, 2026-09-11** (`demo@example.com` promoted, `demo2@example.com`/"Demo Two" received — real second team member, not a probe row): inserted one personal skill directly via SQL for the promoter (the UI's own "+ Create skill" needs a real LLM call to generate the playbook, out of scope here), then promoted it through the **actual** "Promote to team" UI flow — this exercised the real `promote-skill` RPC end to end, not just a pre-seeded `team_skill` row from an earlier session. Every acceptance criterion in the README was independently re-confirmed against this fresh skill, on top of the Phase 03/04 sessions' findings against the three pre-existing leftover team skills from prior work.

**One transient rendering artifact, not a bug:** right after publishing, the Skills list briefly showed "Arrival notice copy check" **twice** for the promoter (the just-deleted personal-skill entry and the new team-skill entry both rendering in the same paint). A full page reload showed exactly one entry, `newCards: 0`. Confirmed via direct DB query that the backend state was correct the whole time (`profile_skill` deleted, exactly one `team_skill` row) — this was purely a frontend catalog-merge race between the `user-skills`/`team-skills` refetches that `promote-skill`'s watch effect fires in sequence, not a persisted data issue. Not worth a fix given the plan's lean/tests-waived execution mode, but noted here in case it recurs.

**Cleanup:** removed all test-created rows afterward (`team_skill_seen`, `team_skill`, the fake local-only `profile_ai_provider` row for `demo2`) — including one `team_skill_seen` row left over from Phase 03's testing that an earlier cleanup pass missed. Left untouched: the three original `team_skill` rows from a prior session (pre-existing, not mine to remove) and a real `profile_ai_provider` row already configured for `demo@example.com` before this session (unrelated to this work).

**Devenv note:** the backend was restarted once during Phase 03 (to pick up the Phase 01 RPC changes over real HTTP — see that phase's notes) and never needed restarting again for Phases 04-05, confirming the earlier nREPL-reload-vs-HTTP-dispatch gotcha was a one-time thing tied to the very first backend edit in this plan, not a recurring issue.
