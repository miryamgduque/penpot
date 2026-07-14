# Phase 03 — Toggle UI (catalog cards + detail view)

**Status:** done

## Goal

Add a toggle to every skill — in the catalog **list** cards and the **detail** view — wired to
Phase 02's state. Instant, no confirmation. A disabled skill reads as muted and drops from the
router immediately.

## Before Start

- [x] Decided: the card toggle writes the **per-file, per-user** override for the current file
      (via Phase 02's `set-skill-enabled`, which reads `:current-file-id` itself)
- [x] Read `ai_panel.cljs` `skills-tab*` (cards) + `skill-detail*` and `ai_panel.scss`
- [x] Read the DS `switch*` component API (`ui/ds/controls/switch.cljs`) — `:default-checked`,
      `:on-change` (gets the new bool), `:aria-label`; manages its own state, re-syncs to prop
- [x] Confirm the Phase 02 selector (`refs/resolved-skills-enabled`) + action (`skst/set-skill-enabled`)

## Checklist

- [x] Card: add a `switch*` toggle in `skills-tab*`; `checked` = resolved enabled; `on-change` →
      `skst/set-skill-enabled`; card is now `div[role=button]` (can't nest the switch in a
      `<button>`), toggle wrapper swallows click/keydown so it doesn't open the detail view
- [x] Detail: add the same toggle in `skill-detail*` (both views, per the story)
- [x] Disabled state: muted styling now driven by the **resolved** enabled flag (per-file), and the
      obsolete "off by default" badge removed (all built-ins default on since Phase 02)
- [x] Instant, no confirmation for any skill (no warning/confirmation flow)
- [x] `ai_panel.scss`: toggle placement/alignment (`.catalog-toggle`, `.detail-head`), focus-visible
- [x] Verify in the devenv: backend reloaded → migration `0155` applied, `profile_skill_state`
      created, RPC ns registered. Live authenticated round-trip proven: `set-skill-enabled` off →
      persisted → `get-skill-states` reads it back → `set` on upserts the same slot → reads on.
      Because `enabled-skills` derives from this state, a disabled skill drops from the router.
- [x] `make lint` (clj-kondo 0 errors) + frontend build (test build: 5 compiled, 0 warnings;
      live `:main` watch: recompiled 0 warnings)
- [~] Preview review — app loads clean on the recompiled frontend (the one `tabindex` console
      warning is pre-existing in top_toolbar.cljs, not this change). The panel/Skills-tab screenshot
      is best seen in a logged-in browser: the live render-wasm workspace hangs the preview tool's
      screenshot (documented devenv quirk).
- [x] Human approval received
- [x] Committed with a gitmoji commit (`:sparkles:`)

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
