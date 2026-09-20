# Phase 08 — Fresh-chat handoff (follow-up, user-requested 2026-07-16)

**Status:** done (unit-verified; live verification in the phase-07 pass)

Added after phases 01–07: once per-file chat management merged, the right UX
for a long conversation is not only the silent in-place compaction backstop
but a **user-facing suggestion** — "this conversation is getting long" — with
an action that summarizes ALL of it (one Haiku round), starts a fresh chat
seeded with the summary (`agent/handoff-seed`), and leaves the old
conversation whole in the saved History list.

## What shipped

- `agent.cljs`: `handoff-notice-chars` (60k — deliberately below the 100k
  compaction backstop, so the user gets the choice first), public
  `history-chars`, `conversation-transcript` (whole history; the old
  `compaction-transcript` is now its head-only slice), `handoff-seed`,
  `summarize-history` (whole-conversation summarizer; blank summary throws —
  no sensible degrade when about to seed a new chat).
- `data/ai_panel.cljs`: `summarize-into-new-chat` (persist current → summarize
  → new-chat → seed history + "✦ Fresh chat…" note + meter the round; failure
  = a note, conversation untouched) and conversation-scoped
  `dismiss-handoff-notice`.
- `agent_chats.cljs`: `:handoff-dismissed` cleared on new-chat/load-chat like
  `:checkpoint`.
- UI: notice banner above the composer (text + "✦ Summarize into a new chat" +
  ✕), hidden while busy / at a checkpoint / when dismissed; size memoized on
  the history ref's identity (changes only at turn boundaries).
- Tests: handoff-seed shape/framing, conversation-vs-compaction transcript
  coverage, threshold ordering (notice < compaction), history-chars.

## Checklist

- [x] Tests first; suite green (765 tests / 0 failures in the worktree)
- [x] shadow compile test + main — 0 warnings
- [x] cljfmt clean
- [x] Live verification in Claude in Chrome (banner → action → fresh chat) —
      results recorded in phase 07's notes
- [x] Committed with a gitmoji commit (`:sparkles:`)
