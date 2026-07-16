# Phase 04 — Team Dashboard "Agent Skills" entry

**Status:** done (2026-07-16) — new `:dashboard-agent-skills` route
(`/dashboard/agent-skills`) + `go-to-dashboard-agent-skills`, registered in
ui.cljs; a Sources-section nav entry ("Agent Skills", alongside Fonts/Libraries)
in the dashboard sidebar; read-only `ui/dashboard/agent-skills.cljs` page (fetches
team skills, lists name + category + reactive, empty state) + scss; i18n keys.
Verified live: Sources shows Fonts/Libraries/Agent Skills, and the page lists the
promoted "Passive voice flagger" (Audits · observer).

## Goal

Promoted skills have a visible home: a new **Agent Skills** entry in the Team
Dashboard's Sources section (alongside Fonts and Libraries) that lists the team's
promoted skills. Read-only for now — managing them is story #13.

## Before Start

- [ ] Re-read the dashboard sidebar Sources block (Fonts/Libraries) in
      `ui/dashboard/sidebar.cljs` (`go-to-dashboard-fonts` / `-libraries`, the
      section grouping around :974-1051) and the routing in `ui/dashboard.cljs`
      (:153 `:dashboard-skills`) + `data/common.cljs` `go-to-dashboard-*`.
- [ ] Note: an older `:dashboard-skills` page (`dashboard/skills.cljs`, backed by
      `design_skill`) already exists. Decide the entry name/route so the new
      **Agent Skills** (team_skill) page doesn't collide — use a distinct route
      (e.g. `:dashboard-agent-skills`) and label ("Agent Skills").

## Checklist

- [ ] Route + nav: add `:dashboard-agent-skills` (route + `go-to-dashboard-agent-skills`)
      and a Sources-section nav entry labelled "Agent Skills", placed alongside
      Fonts/Libraries.
- [ ] Page `ui/dashboard/agent_skills.cljs`: on mount fetch the team's skills
      (`team_skills/fetch-team-skills` via the dashboard team-id) and render a
      simple list — name, description, category, reactive badge, "promoted by".
      Empty state when the team has none.
- [ ] i18n label(s) for the nav entry + page title.
- [ ] Compile 0 warnings; `clj-kondo` clean.
- [ ] Preview review: after promoting in the panel, the skill shows in Team
      Dashboard → Sources → Agent Skills.

## Notes

- Keep it read-only: no create/edit/delete here (story #13). This phase is just
  the destination surface the promote flow points at.
- Reuse the Phase 01 `::get-team-skills` RPC + Phase 02 `team_skills.cljs` fetch;
  no new backend.
