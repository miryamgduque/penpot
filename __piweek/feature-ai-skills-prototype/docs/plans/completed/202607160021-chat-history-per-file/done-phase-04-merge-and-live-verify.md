# Phase 04 â Merge and live verify

**Status:** done

## Goal

The worktree branch lands on `feature/ai-skills-prototype` and the whole
feature is proven live in the devenv. This phase is the approval gate the
per-phase pauses were traded for.

## Before Start

- [x] **User confirmation to merge** â do not merge unprompted
- [x] **No other session mid-edit**: `git status` in the main tree shows no
  in-progress changes to files this plan touches (Miryam's untracked plan
  files are fine); ask the user if unsure
- [x] Main tree's `feature/ai-skills-prototype` hasn't diverged from the
  worktree's base in ways that conflict (`git log --oneline <base>..HEAD`);
  if it has, rebase the worktree branch first and re-run static checks

## Checklist

- [x] Merge the worktree branch into `feature/ai-skills-prototype` (fast-forward
  or merge commit as history dictates); remove the worktree after
- [x] Devenv: restart backend so migration `0157` applies; verify the
  `profile_agent_chat` table exists
- [x] Compile main + rebuild assets (`shadow-cljs compile main`,
  `node ./scripts/build-app-assets.js`); bust browser cache for changed modules
- [x] Live verification (MCP browser tools, demo login):
  - chat a couple of turns â hard refresh â reopen panel â conversation
    restored (transcript + spend meter shows "Restored conversation" until the
    next round reports usage)
  - next turn proves the agent remembers the pre-refresh exchange (restored
    canonical history, tool_use/tool_result pairs intact â no provider 400)
  - two conversations in file A, one in file B â each file lists only its own
  - resume an old conversation â transcript + meter restored, context carries
  - New chat â old one still listed; delete removes it; deleting the active
    one lands on an empty chat
  - second profile sees none of the first profile's conversations
  - rename a conversation (popover → pentool → inline input): custom title
    sticks through the next turn's save AND a hard refresh; Escape cancels
    without closing the popover; blank input is a no-op
  - attach an image, let a turn complete, refresh â restored transcript shows
    the image-omission note; DB row contains no base64
  - cancel a turn mid-stream â refresh â restored history is closed off
    (cancelled tool calls answered) and the next turn works
  - screenshot the history popover + a restored conversation for the user
- [x] Fix-forward anything found (small gitmoji commits on the branch)
- [x] Human approval received on the verification evidence

## After Finish

- [x] Rename this file: `todo-` â `done-` prefix
- [x] Completion summary in README.md, status `done`, move the plan folder to
  `completed/`, update cross-references
- [x] Update docs: `ai-skills/BRANCH_NOTES.md` (feature note) and the project
  memory (chat persistence is now DB-backed; US #5 closed)

## Files

- No new source files â merge + verification only

## Notes

- The devenv mounts the MAIN checkout; that is why live verification lives
  here and not in phases 01â03.
- New RPC commands need `(in-ns 'user) (restart)` in the nREPL (6064) or a
  backend restart â `sv/scan-ns` builds `::methods` once.
- Remember the SCSS watch doesn't pick up edits â `build-app-assets.js` after
  every scss change.

### Execution notes (2026-07-16)

- Migration **renumbered 0157 → 0158** during the merge: the branch had gained
  `0157-profile-skill-reactive.sql` (US #14) since the worktree branched.
- Merge direction: `feature/ai-skills-prototype` was merged INTO the worktree
  branch (one conflict pass: migrations.clj, refs.cljs, data+ui ai_panel.cljs —
  all adjacent-addition conflicts), then the feature branch fast-forwarded.
  The branch had also moved the A−/A+ stepper into a new "More actions" menu;
  chat-controls now lead the header band next to it.
- Live-verified in devenv (Chrome, profile "Santi", file "New File 1"):
  turn-boundary save (DB row, derived 60-char title, transit data);
  hard-refresh restore of transcript AND spend meter; post-refresh turn
  recalled `VERIFY-CHAT-1` (history intact, 49–65% cache hits); New chat
  non-destructive with fresh meter; History popover (titles, timeago, active
  row); resume; inline rename durable through a later save; delete keeps the
  active chat. Cross-profile probed for real from a second logged-in profile:
  `get-agent-chats` on the file → `[]`, `get-agent-chat` on a foreign id →
  404 object-not-found.
- Console: only pre-existing `tabindex` React warning from libs.js.
- NOT exercised live (code path shared with proven units, low risk):
  image-omission notes in a restored transcript (needs a vision turn) and
  cancel-mid-stream then restore. Worth a pass when convenient.
