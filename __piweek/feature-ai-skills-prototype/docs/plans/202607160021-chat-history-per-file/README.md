# Chat History Per File

**Status:** doing
**Created:** 2026-07-16
**Apps:** `backend`, `frontend`
**Dependencies:** None (builds on the shipped agent panel; closes out US #5)

## Context

The agent panel's chat is per-file but **in-memory only**: `[:ai-panel <file-id>]`
holds `:messages` (rendered transcript), `:history` (canonical wire history) and
`:usage` (spend meter). That survives in-app navigation by design
(all-in-penpot-panel-toggle phase 05), but a hard refresh drops everything —
the deferred "US #5: durable chat". The status row even already renders
"Restored conversation" for the messages-without-usage state this plan creates.

Decisions from the discovery interview (2026-07-16):

- **Multiple conversations per file** — a browsable list (like Cursor/Claude):
  resume any past conversation, start a new one. Not just single-chat persistence.
- **Backend DB storage** — survives browsers/devices, consistent with the
  DB-backed skills direction. Not localStorage/IndexedDB.
- **Private per user** — keyed `(profile_id, file_id)`; each collaborator has
  their own conversations with the agent in a file. Never shared.
- **No demo pressure** — this lands after the 2026-07-17 demo; plan for the
  right architecture at normal pace.

### Design decisions

- **Table `profile_agent_chat`** (follows the `profile_*` per-profile naming:
  `profile_ai_provider`, `profile_skill_state`, `profile_skill`): client-generated
  `id` PK, `profile_id`/`file_id` FKs with `ON DELETE CASCADE`, `title`,
  `data` jsonb, timestamps. One row = one conversation.
- **Transit-in-jsonb for `data`** via the existing `db/tjson` /
  `db/decode-transit-pgobject` helpers — the canonical history is keyword-heavy
  CLJS data (`:role :user`, `:tool-calls`, …) and transit round-trips it
  losslessly, where plain JSON would mangle keywords. `data` holds
  `{:messages … :history … :usage …}`.
- **Persist only at turn boundaries** — the same moments `store-history` fires
  today (`:done`, and the cancel-close path). Never mid-turn, no debouncing
  needed. Empty conversations are never persisted.
- **Images are stripped before saving** — both the user's attachments and
  `render_board` outputs, using the existing `agent/strip-images` (which leaves
  an "[N images omitted]" note). This keeps `send-message`'s documented promise
  that images are never *stored* anywhere (they only transit the proxy), and
  bounds row size: history is already trimmed (40 messages, ≤20k chars per tool
  result ≈ ≤800k worst case), and images were the only unbounded payload.
- **"Clear" becomes "New chat"** — starting fresh no longer destroys the
  conversation; it stays in the file's history list. Explicit delete lives in
  the list.
- **Auto-title from the first user message** (truncated ~60 chars), editable
  afterwards (phase 03b, added by user direction 2026-07-16): the derived
  title is written on insert only and never on later saves, so a manual
  rename is durable.
- **Last-write-wins** on concurrent tabs — acceptable for the prototype; noted,
  not solved.

### Execution mode (user direction, 2026-07-16)

- **Work happens in a git worktree** branched off `feature/ai-skills-prototype`
  — Miryam works in the main tree concurrently, and the devenv mounts the main
  checkout anyway, so live verification is only practical post-merge.
- **No tests** — test-writing is skipped for all phases. Per-phase verification
  is static only: clj-kondo, cljfmt, stylelint, shadow compile.
- **Phases run back-to-back** in the worktree (one gitmoji commit each, no
  per-phase approval pause). The **merge is the approval gate**: when all
  phases are committed, confirm with the user and verify no other session is
  mid-edit in the main tree, then merge into `feature/ai-skills-prototype` and
  live-verify everything in the devenv (Phase 04).

### Out of scope

- Sharing/team-visible conversations.
- Persisting image bytes (stripped, see above).
- Any retention/quota policy beyond the existing history trim.
- Syncing conversations to the MCP/external-agent path.

## Phases

1. [Phase 01 — Backend table + RPC](./done-phase-01-backend-table-and-rpc.md) — migration `0157` + `agent_chats.clj` (list/get/upsert/delete), registered
2. [Phase 02 — Frontend persistence layer](./done-phase-02-frontend-persistence.md) — save at turn boundaries, hydrate on panel open, strip-for-save + auto-title helpers
3. [Phase 03 — Conversation switcher UI](./done-phase-03-conversation-switcher-ui.md) — history popover + New chat in the panel header, load/delete
4. [Phase 03b — Rename conversations](./done-phase-03b-rename-conversations.md) — inline rename in the popover; renames survive later saves
5. [Phase 04 — Merge and live verify](./doing-phase-04-merge-and-live-verify.md) — merge the worktree into `feature/ai-skills-prototype` (user gate), then verify everything live in the devenv

## Acceptance Criteria

- A conversation survives a hard refresh: reopen the panel and the most recent
  conversation for that file is restored (transcript, canonical history so the
  agent remembers context, and spend meter).
- Each file lists its own conversations; each user sees only their own.
- "New chat" starts a fresh conversation without destroying the previous one;
  any listed conversation can be resumed, renamed or deleted.
- A renamed conversation keeps its custom title through later turns, saves
  and refreshes.
- Images never reach the database — restored transcripts show the omission notes.
- A resumed conversation continues correctly (the next turn carries the restored
  canonical history, including intact tool_use/tool_result pairs).
- `clj-kondo`, `cljfmt`, `stylelint` clean; shadow compile 0 warnings.
- All of the above verified **live in the devenv after the merge** (Phase 04) —
  no automated tests, per the execution mode.
