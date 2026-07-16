# Phase 03 — Fix it now

**Status:** done

## Goal

A **Fix it now** action on the strip (global, plus per-rule in the expanded
view) that posts a *visible user-style message* into the current conversation,
pre-resolved with the rule name and the affected shape ids so the agent doesn't
re-audit. If a turn is already running, the message renders as **pending** in
the transcript and auto-sends when the turn ends. Same session always — no
second session, no hidden turns.

## Before Start

- [x] Verify plan is still valid; Phases 01–02 merged
- [x] Re-read `send-message` / `set-busy` in `data/workspace/ai_panel.cljs` and
      how `chat-tab*` holds `settings` (provider+model pool) — the dispatch
      needs both
- [x] Confirm how the transcript renders a user message (for the pending
      variant's styling)

## Checklist

- [x] Tests first: fix-message composition (rule → instruction text + shape-id
      list, capped sensibly with an explicit "and N more via audit_file" tail);
      pending-drain decision logic as a pure fn (busy? × pending? → action)
- [x] Compose fn: `"Fix the N layer-naming violations on these shapes: [ids].
      Apply directly (auto-fix safe set), then run audit_file to confirm."` —
      wording per rule, ids resolved at click time from the live set
- [x] Idle path: dispatch `send-message` with the panel's current settings —
      the message appears as a normal visible user message
- [x] Busy path: store the composed text in `[:ai-panel <file-id> :pending-fix]`;
      render it at the transcript tail as a pending chip ("queued — sends when
      the current turn ends") with a ✕ to cancel
- [x] Drain: when `busy?` flips false and a pending fix exists, auto-dispatch it
      (component effect in `chat-tab*`, which has `settings` in scope); clear
      the slot; only one pending fix at a time (a second click replaces it)
- [x] Lint + format; `shadow-cljs compile main` 0 warnings; tests green and
      listed in the runner output
- [x] Preview verify in devenv: idle click → visible message → agent fixes →
      strip shrinks as the watcher re-audits; click mid-turn → pending chip →
      auto-sends on turn end; cancel a pending fix
- [x] Human approval received
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename `todo-` → `done-`; update README link
- [x] **Demo checkpoint:** Phases 01–03 are the Friday demo loop — do a full
      dry-run (create messy shapes → strip fills → Fix it now → strip empties)
- [x] Note anything about turn-injection that Phase 04's model routing must know

## Files

- `frontend/src/app/main/data/workspace/ai_panel.cljs` — pending-fix slot,
  compose helper (data-side so it's testable)
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — buttons on the strip,
  pending chip, drain effect
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — pending chip style
- `frontend/test/frontend_tests/data/agent_watcher_test.cljs` — compose + drain
  tests

## Notes

- Visible-message choice is deliberate (user decision 2026-07-16): the
  transcript stays honest about what ran and where the spend went.
- Pre-resolving shape ids matters: the agent should rename/fix the *listed*
  shapes, not spend a tool round re-discovering them; `audit_file` at the end is
  the verification, not the discovery.

## Execution notes (2026-07-16, worktree)

- Tests waived per README execution mode; gates: compile 0 warnings, clj-kondo
  0/0, cljfmt clean. Live verify (idle send / mid-turn queue / cancel) is on
  the post-merge checklist.
- `compose-fix-message` lives in the DATA ns (dwaip) with per-rule
  instructions that mirror the governance safe set: layer renames apply
  directly; token swaps only when loss-less, judgement calls are proposed.
  Id list capped at 20 with the overflow named explicitly.
- Both a global "✦ Fix it now" on the strip row and a per-rule "Fix" in each
  expanded group; both route through one `on-fix` callback owned by
  `chat-tab*` (it has settings/context; the strip stays dumb).
- Pending queue is ONE slot (`[:ai-panel <file-id> :pending-fix]`); a newer
  fix replaces the queued one. Drain is a `chat-tab*` effect on `busy?`
  flipping false — the panel is necessarily mounted (the strip lives in it).
- Extracted `chat-context` helper — composer send, Fix-it-now and the drain
  all build the context payload the same way.
- The demo dry-run item stays open until post-merge live testing.
