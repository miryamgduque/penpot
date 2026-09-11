# Phase 03 — Arrival notice card UI

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Confirm Phase 02 is `done` — skill entries expose `:arrived?` and `mark-team-skill-seen!` exists
- [ ] Re-read `ai_panel.cljs:1227-1244` (handoff-notice — shape to pattern-match) and `ai_panel.cljs:697-868` (Observer stack — precedent for showing >1 card) to confirm current structure
- [ ] Re-read `ai_panel.cljs:2018-2073` / `1479-1522` (list row + detail toggle) to confirm the `on-select` hook the "View skill" button should call

## Checklist

- [ ] New `arrival-notice*` component (or small stack wrapper if >1 arrived skill), styled per this session's Penpot design reference: icon in a tinted square, "New team skill: \<name\>", "Promoted by \<promoter\>. \<one-line description\>", footer with Dismiss (text link) + View skill (secondary button) — pattern the card shell/dismiss mechanics on `handoff-notice` (single card, header + action button), not Observer's expand/collapse
- [ ] Render the notice(s) in the panel body above the transcript/chat input, gated on skill entries with `:arrived? true`, one card per arrived skill (stack if multiple, following Observer's stacking approach for the *layout* only, not its expand/collapse behavior)
- [ ] Wire **Dismiss** → `mark-team-skill-seen!` for that skill (hide the card immediately, optimistic)
- [ ] Wire **View skill** → `mark-team-skill-seen!` **and** the existing `on-select` used by list rows to open the detail view (`ai_panel.cljs:2039-2040`'s pattern) — no new detail view
- [ ] No Disable action anywhere on the card (per acceptance criteria — verify by design review, not just omission)
- [ ] Lint + typecheck pass; `shadow-cljs compile main` 0 warnings
- [ ] Preview review with MCP browser/devenv tools — promote a skill as user A, confirm the card renders correctly as user B, screenshot for the PR/plan record
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — new `arrival-notice*` component + render call
- corresponding `.scss` (wherever `handoff-notice`'s styles live) — new card styles per the Penpot design reference (teal accent `#0DB39E`, not the wireframe's violet)

## Notes

Visual reference lives in a Penpot MCP file from this session (not in the repo) — two components, `NoticeCard` and `SkillListItem`. Translate the *spec* (layout, copy, accent color, spacing rhythm) into this project's SCSS/cljs conventions; do not attempt to import Penpot shapes directly.
