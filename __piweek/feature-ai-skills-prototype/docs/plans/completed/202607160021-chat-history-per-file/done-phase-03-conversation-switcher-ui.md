# Phase 03 — Conversation switcher UI

**Status:** done

## Goal

The panel exposes the file's conversation list: a history control in the chat
header opens a popover of past conversations (resume / delete), a New-chat
control starts a fresh one, and the status row's "Clear" is re-worded to match
the new non-destructive semantics.

## Before Start

- [x] Verify plan is still valid; Phase 02 events exist
- [x] Re-read `ai-panel*`'s header (`header-actions`, currently one Skills icon-button) and the model-picker popover in `chat-tab*` — the outside-click + Escape pattern to reuse
- [x] Check `frontend/src/app/main/ui/ds` icons for suitable history/plus glyphs (known gap: the DS had no `stop` glyph; history may be missing too — pick the closest, don't invent SVG)

## Checklist

- [x] Header (chat view only): add two ghost icon-buttons beside the Skills one — **New chat** (emits `new-chat`; disabled while busy or when the current chat is already empty) and **History** (opens the popover; shows only when the file has ≥1 saved conversation)
- [x] History popover (model-picker pattern: outside-click + Escape close, focus return): one row per conversation — title + relative "updated" time, active row marked; click loads it (`load-chat`), per-row ✕ deletes (`delete-chat`). Disabled while a turn is running — switching mid-turn would rip the history out from under `run-turn`
- [x] Status row: "Clear" → "New chat", title updated to say the conversation is kept in history; keep it disabled while busy
- [x] SCSS for the popover + rows in `ai_panel.scss` (remember: **any** scss change needs `node ./scripts/build-app-assets.js` in the devenv before it renders)
- [x] Lint + typecheck: clj-kondo, cljfmt, stylelint, shadow compile 0 warnings (live preview review happens post-merge in Phase 04)
- [x] Commit in the worktree: `:sparkles: Browse and resume per-file agent conversations`

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Hand off to Phase 04 (merge gate + live verification)

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — header controls, history popover, status-row wording
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — popover styles

## Notes

- Keep the popover dumb: it renders `refs/ai-panel-chats` and emits events;
  all list mutation lives in Phase 02's data ns.
- Known DS follow-up (pre-existing, app-wide): `icon-button*` has no accessible
  name until hover — don't try to fix it here.

### Execution notes (2026-07-16)

- The DS had everything needed: `add`, `history`, `delete` icons and
  `ct/timeago` for the relative timestamps — no glyph improvisation.
- Controls live in a self-contained `chat-controls*` component leading
  `header-actions` (before the A−/A+ stepper the branch gained meanwhile).
- The header band deliberately doesn't scale with `--ai-font-scale`, so the
  popover doesn't either — consistent with the stepper's own rule.
- Pre-existing lint debt found (NOT touched): `ai_panel.scss` has 19 baseline
  stylelint errors and `ai_panel.cljs` one cljfmt hunk (skills-filter `for`
  indentation, line ~854) that predate this plan. This phase adds zero to
  either count.
