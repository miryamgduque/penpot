# Phase 02 — Frontend data + catalog merge

**Status:** todo

## Goal

Team skills are fetched for the current team and merged into the panel catalog so
every member sees them; the promoter's promoted copy is marked and dropped from
the active set.

## Before Start

- [ ] Re-read `user_skills.cljs` (`fetch-user-skills`, refetch pattern) and
      `agent_skills.cljs` `user-skill->entry` / `user-skills` / `full-catalog`
      (:282–318) — team skills mirror these.
- [ ] Confirm `refs/team` gives the current team (`:id` = team-id) in the
      workspace, and where the panel already fetches user skills on open
      (`fetch-user-skills` call site) so team fetch rides alongside.

## Checklist

- [ ] `data/workspace/team_skills.cljs`:
  - `fetch-team-skills [team-id]` → `(rp/cmd! :get-team-skills {:team-id ...})`
    into `[:team-skills]`.
  - `promote-skill [params {:on-success :on-error}]` →
    `(rp/cmd! :promote-skill params)`, then refetch **both** team-skills and
    user-skills (the personal copy is now `promoted_to`), then `on-success`.
- [ ] `refs.cljs`: `ai-panel-team-skills` (derives `[:team-skills]`) — or reuse
      the existing skills-catalog ref path if cleaner.
- [ ] `agent_skills.cljs`:
  - `team-skill->entry` (like `user-skill->entry`, tag `:team? true`,
    `:enabled true` default).
  - `team-skills [state]` from `[:team-skills]`.
  - `full-catalog`: merge **both** user and team skills into their categories.
  - Personal skills with `:promoted-to` set are excluded from the **active**
    resolution (`enabled-skills` / router) but still returned to the card list
    tagged `:promoted? true` so the UI can show the light link.
- [ ] Fetch team skills on panel open (alongside `fetch-user-skills`), guarded on
      a present team-id.
- [ ] A small **"Team"** marker on team cards (distinct from personal), and the
      promoted personal card renders disabled + "Promoted to team".
- [ ] Compile 0 warnings; `clj-kondo` clean.

## Notes

- Keep the merge name-keyed and category-grouped exactly like user skills, so
  `resolve-enabled`, the router index, and `get_design_skills` treat team skills
  with no special-casing (default on for everyone).
