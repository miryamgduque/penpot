# Phase 08 — Edit & delete user-created skills

**Status:** done

Added 2026-07-15 on user direction, before the merge gate: “what about adding
possibility to edit the vibes/agents generated?” The vibes doc was already
editable (Phase 06); the GENERATED SKILLS were not — after creation, a user
skill's playbook was frozen and invisible. This phase makes a user-created
skill's label, trigger, mode and body editable from its detail view, and
deletable. Built-ins stay read-only (they are shared and regenerated from
the aikit).

## Checklist

- [x] Backend: `::update-skill` (patch label/mode/trigger/description/body,
      `updated_at`, ownership-scoped `{:id ? :profile-id ?}`) and
      `::delete-skill` in `profile_skills.clj` — same ns, so rpc scan-ns
      registration comes free; clj-kondo lint in the container
- [x] `user_skills.cljs`: `update-skill` / `delete-skill` data fns
      (refetch after, like create)
- [x] `agent-skills/user-skill->entry` carries `:id` (update/delete key)
- [x] Skill detail view: for `:user?` skills — Edit (label, trigger, mode
      chips, body textarea) with Save/Cancel; Delete with the vibes-style
      two-click confirm, then back to the list
- [x] Compile green (frontend shadow-cljs 0 warnings; backend clj-kondo 0/0 with project config); commit `:sparkles:`

## After Finish

- [x] Rename to `done-`, update README links + completion summary

## Files

- `backend/src/app/rpc/commands/profile_skills.clj`
- `frontend/src/app/main/data/workspace/user_skills.cljs`
- `frontend/src/app/main/data/workspace/agent_skills.cljs`
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` (+ `.scss`)

## Notes

- The name/slug stays immutable on edit — it is the routing + toggle key
  (`profile_skill_state` rows point at it); renaming would orphan state.
  The human-facing `label` is what edits.
- Body cap mirrors create (100k) — the backend schema is the guard.
- Backend changes can't be compile-checked from the worktree (Clojure, no
  host CLI) — clj-kondo lint only; the live-test after merge exercises it.
