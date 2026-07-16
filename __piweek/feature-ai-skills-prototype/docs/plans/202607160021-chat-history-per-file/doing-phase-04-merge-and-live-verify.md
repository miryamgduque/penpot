# Phase 04 â Merge and live verify

**Status:** todo

## Goal

The worktree branch lands on `feature/ai-skills-prototype` and the whole
feature is proven live in the devenv. This phase is the approval gate the
per-phase pauses were traded for.

## Before Start

- [ ] **User confirmation to merge** â do not merge unprompted
- [ ] **No other session mid-edit**: `git status` in the main tree shows no
  in-progress changes to files this plan touches (Miryam's untracked plan
  files are fine); ask the user if unsure
- [ ] Main tree's `feature/ai-skills-prototype` hasn't diverged from the
  worktree's base in ways that conflict (`git log --oneline <base>..HEAD`);
  if it has, rebase the worktree branch first and re-run static checks

## Checklist

- [ ] Merge the worktree branch into `feature/ai-skills-prototype` (fast-forward
  or merge commit as history dictates); remove the worktree after
- [ ] Devenv: restart backend so migration `0157` applies; verify the
  `profile_agent_chat` table exists
- [ ] Compile main + rebuild assets (`shadow-cljs compile main`,
  `node ./scripts/build-app-assets.js`); bust browser cache for changed modules
- [ ] Live verification (MCP browser tools, demo login):
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
- [ ] Fix-forward anything found (small gitmoji commits on the branch)
- [ ] Human approval received on the verification evidence

## After Finish

- [ ] Rename this file: `todo-` â `done-` prefix
- [ ] Completion summary in README.md, status `done`, move the plan folder to
  `completed/`, update cross-references
- [ ] Update docs: `ai-skills/BRANCH_NOTES.md` (feature note) and the project
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
