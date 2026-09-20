# Phase 02 — Inner-knowledge doctrine (foundations sync, minimalism, concurrent-work hygiene)

**Status:** done

Three always-on doctrine additions, all prompt-layer, all targeting logged
incidents:

- **Self-maintaining foundations** (Kimi's AGENTS.md rule): if an applied change
  contradicts a foundation/DESIGN.md (new accent, different type scale), update
  the foundation via `set_foundation` or flag the conflict — never let the doc rot.
- **Scope minimalism** (Kimi "MINIMAL changes / never give more than they want"):
  targets the $4 unrequested-landing-page incident.
- **Concurrent-work hygiene** (codex dirty-worktree rules): shapes the agent
  didn't create are the user's/teammates' work in progress — never delete or
  restyle outside task scope, even to "clean up".

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Re-read `governance` / `native-tool-notes` in `agent_skills.cljs`
      (:222/:276) — confirm none of the three is already partially covered
      (as of 2026-07-17: governance covers ambiguity/irreversibility, NOT
      overdelivery or untouched-work framing; foundations sync absent entirely)
- [ ] Confirm `set_foundation` is still the tool name (US #38 folded
      `set_design_doc` into it)

## Checklist

- [x] Write/update tests: `inner-knowledge` string contains the three new
      doctrine markers (cheap pin, same style as existing prompt pins — the
      retired `set_design_doc` was "pinned by test", follow that precedent)
- [x] Foundations-sync lines → most natural home is a short new block or an
      extension of `governance`: "Foundations describe intent. When a change you
      apply contradicts one, update that foundation (set_foundation) in the same
      breath, or tell the user about the conflict — a stale DESIGN.md is worse
      than none."
- [x] Scope-minimalism line → `governance`: "Make the smallest change that
      satisfies the ask. Never build what wasn't requested — an unrequested
      screen is not initiative, it is scope the user now has to review and pay
      for."
- [x] Concurrent-work lines → `governance` (beside the never-without-approval
      list): "Existing content you did not create is someone's work in progress.
      Never delete, restyle or 'clean up' shapes outside the task's scope."
- [x] Budget check: three additions ≤ ~12 lines total; re-measure prefix tokens
      if it feels bloated (was ~11k)
- [x] Lint + typecheck pass (kondo; `compile test` + run; `compile main`)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (e.g. `:memo: Inner-knowledge: foundations
      sync, scope minimalism, concurrent-work hygiene`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — `governance`
  (possibly one new small def joined into `inner-knowledge`)
- `frontend/test/frontend_tests/data/agent_skills_test.cljs` (or wherever the
  existing prompt pins live) — extend

## Notes

- The file's own comment (:210-213) sets the bar: this is the only content the
  user cannot toggle off — every line must shape *every* response. All three
  qualify (they're governance, not procedure), but keep each to 1–3 lines.
- Foundations sync is the highest-value item in the whole plan — it protects
  the prototype's flagship concept from silent rot.
