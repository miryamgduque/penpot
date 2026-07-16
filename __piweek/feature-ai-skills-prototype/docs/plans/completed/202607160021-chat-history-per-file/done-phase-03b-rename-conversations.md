# Phase 03b — Rename conversations

**Status:** done

## Goal

A conversation's title still comes from its first user message, but the user
can edit it — inline in the History popover — and the custom name survives
every later save.

Added mid-plan (user direction, 2026-07-16): renaming was originally declared
out of scope; scope change lands as its own phase per the workflow.

## Before Start

- [x] Phases 01–03 committed in the worktree
- [x] Confirm the DS still has no pencil/edit glyph (fallback: `pentool`)

## Checklist

- [x] Backend: `sql:upsert-chat`'s ON CONFLICT arm stops updating `title` —
  the derived title is set on insert only, so a save after a rename can't
  clobber the custom name (the first user message never changes anyway, so
  nothing is lost)
- [x] Backend: new `::rename-agent-chat {id title}` — UPDATE scoped to the
  owning profile, not-found on zero rows (same non-probeable contract as the
  other commands)
- [x] Data: `rename-chat` event — optimistic title swap in `:chats`, RPC
  fire, refetch on error to revert
- [x] UI: per-row rename icon-button (`pentool`) in the History popover; the
  row swaps to an inline input — autofocus/select-all, Enter or blur commits
  (trimmed, non-blank, changed), Escape cancels. Rename stays enabled while
  busy — it only touches metadata, never the live history
- [x] Lint (clj-kondo/cljfmt/stylelint) + shadow compile, no new violations
- [x] Commit in the worktree: `:sparkles: Let agent conversations be renamed`

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Add rename checks to Phase 04's live-verify list
- [ ] Note any follow-up items or discoveries below

## Files

- `backend/src/app/rpc/commands/agent_chats.clj` — upsert arm + rename command
- `frontend/src/app/main/data/workspace/agent_chats.cljs` — rename event
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — inline rename in the popover
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — rename input styles

## Notes

- DS glyph gap: no pencil/edit icon exists (`pentool` is the nearest metaphor)
  — same category as the missing `stop` glyph; worth a DS follow-up.
- Double-click-to-rename (the dashboard idiom) doesn't fit the popover: a
  row's first click already loads the conversation and closes the menu.
