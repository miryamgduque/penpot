# Phase 03 — Promote flow UI

**Status:** done (2026-07-16) — ⋯ "Promote to team" live for personal,
not-yet-promoted skills (+ a Promote button in the detail view); a `skill-promote*`
confirmation view (editable Name/Description prefilled, "<reactive> · reads file
foundations" note, Cancel/Publish) wired as a new `:promote` leaf in the panel
view state with header back nav. Verified live end-to-end on "Passive voice
flagger": menu → WF2 confirmation → Publish → team_skill created (reactive
observer traveled), profile_skill.promoted_to set, personal card dimmed +
"Promoted to team", new team card active with the "Team" marker. Cross-account
"every member sees it" relies on the P2 merge (proven with a probe row); a
second-account check is deferred to the final review.

## Goal

The ⋯ "Promote to team" entry opens a confirmation view; publishing promotes the
skill and shows the promoter's copy lightly linked.

## Before Start

- [ ] Re-read the ⋯ menu in `skill-row*` (the disabled Fork/Promote `:li`s,
      ai_panel.cljs:1510-1515) and `skill-detail*`, plus how `skill-create*` +
      the Skills view/header manage a full-panel sub-view + back nav (US #35).

## Checklist

- [ ] Enable **"Promote to team"** in the ⋯ menu — only for **personal**
      (`:user?`), **not-yet-promoted** skills; absent/disabled for built-ins and
      team skills. Add the same entry to `skill-detail*`.
- [ ] Panel view state: add a `:promote` sub-view carrying the source skill
      (mirror the `:create` view plumbing + header back nav; detail title style).
- [ ] `skill-promote*` component (like `skill-create*`):
  - Intro "Teammates will see this in Agent Skills. Review before publishing."
  - **Name** input + **Description** textarea, prefilled from the skill
    (label / description).
  - A muted note line: the skill's reactive behavior + "· reads file
    foundations" (reactive travels, foundations don't).
  - **Cancel** (back) + **Publish** (solid accent) → `dwts/promote-skill`
    with `{source-id name description team-id}`; on success close the view.
- [ ] Post-publish: refetch drives it — the team card appears (default on) and
      the personal card shows disabled + "Promoted to team" (Phase 02 marking).
- [ ] Guard: no connected-model requirement (promotion doesn't call a model);
      guard only on a present team + team edit permission (server enforces too).
- [ ] Compile 0 warnings; `clj-kondo` clean.
- [ ] Preview review: promote "Passive voice flagger" (or a fresh personal
      skill); confirm the confirmation view, the disabled+linked personal card,
      and — on a **second team-member account** — the new team card default on.

## Notes

- Editing name/description here only sets the **team** skill's presentation; the
  source personal skill keeps its own name (now promoted/inactive).
