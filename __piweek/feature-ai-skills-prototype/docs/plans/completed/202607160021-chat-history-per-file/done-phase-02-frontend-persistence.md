# Phase 02 — Frontend persistence layer

**Status:** done

## Goal

Conversations save themselves at turn boundaries and come back on panel open —
no UI yet beyond what exists. After this phase a hard refresh restores the most
recent conversation (transcript, canonical history, spend meter) and the agent
demonstrably remembers a pre-refresh exchange.

## Before Start

- [x] Verify plan is still valid; Phase 01 RPC commands exist and respond
- [x] Re-read `frontend/src/app/main/data/workspace/ai_panel.cljs` (`send-message`'s `:done`/cancel branches, `clear-chat`) — it moves often
- [x] Re-read `agent/strip-images` and `agent/trim-history` in `data/workspace/agent.cljs`
- [x] Re-read `data/workspace/skill_state.cljs` as the fetch-event pattern

## Checklist

- [x] New ns `frontend/src/app/main/data/workspace/agent_chats.cljs`:
  - state shape under the existing per-file slot: `[:ai-panel <file-id> :chat-id]` (active conversation) and `[:ai-panel <file-id> :chats]` (metadata list, `updated-at` desc)
  - `fetch-chats` → `::get-agent-chats`, stores the list
  - `load-chat id` → `::get-agent-chat`, installs `:messages`/`:history`/`:usage` + `:chat-id`
  - `persist-chat` → builds `{:messages :history :usage}` from state, strips images from both transcript messages and canonical history, derives the title, allocates `:chat-id` (`uuid/next`) on first save, fire-and-forget `::upsert-agent-chat` (a failed save must not break the turn — log, don't throw)
  - `new-chat` → drops `:messages`/`:history`/`:usage`/`:chat-id` (the old row stays in DB; refresh `:chats` so it appears in the list)
  - `delete-chat id` → RPC + remove from list; if it was active, behave like `new-chat`
- [x] Wire `persist-chat` into `send-message`'s end stage — both the `ended?` branch and the cancelled branch (after `store-history` of the cancel-closed history). Never persists an empty conversation
- [x] Hydration: on panel open (the `ai-panel*` mount effect alongside `fetch-skill-states`), `fetch-chats`; when the file has no in-memory messages and the list is non-empty, auto-`load-chat` the most recent
- [x] `clear-chat` in `ai_panel.cljs` delegates to / is replaced by `new-chat` (UI wording changes in Phase 03)
- [x] Refs: `ai-panel-chats`, `ai-panel-chat-id` in `refs.cljs`
- [x] Lint + typecheck: clj-kondo, cljfmt, shadow compile 0 warnings (static only — live restore check happens post-merge in Phase 04)
- [x] Commit in the worktree: `:sparkles: Persist agent conversations per file`

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_chats.cljs` — new: fetch/load/persist/new/delete events
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — persist wiring in `send-message`, `clear-chat` → `new-chat`
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — hydration on mount
- `frontend/src/app/main/refs.cljs` — chats refs

## Notes

- The transcript `:messages` vector is unbounded today (only `:history` is
  trimmed). Decide at implementation whether to cap persisted messages (e.g.
  last 200 entries) — if capped, note it in the restored UI, never silently.
- Restored `:usage` keeps the spend meter honest across refreshes; the
  "Restored conversation" fallback covers the pre-restore render.

### Execution notes (2026-07-16)

- The rendered transcript is persisted **uncapped** (only history is trimmed):
  tool-chip results are already cut to 2k chars each, so realistic transcripts
  stay far below the history's own worst case. Revisit only if rows get fat.
- `clear-chat` was deleted outright rather than delegated — the UI's on-clear
  now emits `agent-chats/new-chat` (wording still says "Clear"; Phase 03).
- `chats-fetched`/`chat-loaded` guard on `current-file-id` so a slow response
  landing after a file switch can't install another file's conversation.
