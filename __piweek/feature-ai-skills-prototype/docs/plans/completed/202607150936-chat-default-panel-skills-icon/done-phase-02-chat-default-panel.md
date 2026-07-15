# Phase 02 — Chat-default panel + adaptive header + Skills view

**Status:** done

## Goal

Replace the Chat/Skills `tab-switcher*` with a **chat-default** panel: chat is the whole body (no
tabs, no "Chat" label), a muted `list-checks` icon in the header opens **Skills as a full-panel
view** with an adaptive `← Skills` header, and back returns to chat. The view state is **in-memory**
and defaults to chat, so the panel always reopens on chat (US #2).

## Before Start

- [x] Re-read `ai-panel*` (the header + `tab-switcher*` block) and its docstring/comments
- [x] Confirm `i/arrow-left` (back) and `i/list-checks` (from Phase 01) resolve
- [x] Re-read `.header` SCSS + `icon-button*` ghost usage and `.tabs` class; checked how the body
      got its height (it was a grid item of the DS `.tab-panel` — now needs a `flex: 1` wrapper)
- [x] Note: `chat-tab*` and `skills-tab*` are reused **unchanged**; only the shell changes

## Checklist

- [x] **View state:** replaced `tab*` with an **in-memory** `mf/use-state` view (`:chat` |
      `:skills`), default `:chat`; resets to chat on close/reopen (unmount). Removed the `tabs` memo
      and the `::ai-panel-tab` persistence.
- [x] **Adaptive header** (single row, close in both): chat → "Agent" + muted `list-checks` ghost
      `icon-button*` + close; skills → `←` back (`i/arrow-left`, ghost) + "Skills" + close
- [x] **Body:** wrapped in `.body` (`flex: 1; min-height: 0`) so `chat-tab*` / `skills-tab*`
      (`height: 100%`) fill the space the tab-panel used to give them; `:skills` shows `skills-tab*`
      full-panel, `:chat` shows `chat-tab*`
- [x] **Icon actions:** `list-checks` → `:skills`; header back → `:chat` (instant)
- [x] **No-provider case:** `cond` shows `connect-empty*` only for chat when the pool is empty;
      Skills stays reachable (icon shown in chat regardless of pool). `connect-empty*` is an absolute
      overlay so the `.body` wrapper doesn't move it.
- [x] **Cleanup:** rewrote the `ai-panel*` docstring; dropped the `tab-switcher*` require and the
      `.tabs` style (no leftover `::ai-panel-tab` / `tab-switcher` refs)
- [x] **SCSS:** `.header-lead` (back+title), `.header-actions` (icon+close), `.body`; muted
      `list-checks` via the ghost variant; header still sized to the right-header band ($s-48)
- [x] `make lint` (clj-kondo 0 errors) + `:main` + SCSS build, 0 warnings
- [~] Verify in a logged-in browser — **live in the running frontend now** (best eyeballed there;
      the render-wasm workspace hangs the preview tool's screenshots): opens on chat / no tabs;
      icon → Skills full-panel with `← Skills`; back → chat; drill into a skill keeps its inner
      back; close + reopen → chat
- [x] Human approval received
- [x] Committed with a gitmoji commit (`:sparkles:` / `:recycle:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Full-story acceptance pass (README) + completion summary; move plan to `docs/plans/completed/`

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — `ai-panel*` header + view switch
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — header layout + muted icon

## Notes

- **Nested back (decision):** `skills-tab*` keeps its own "← All skills" detail back; the header's
  back is the outer level (Skills → chat). Two independent levels, `skills-tab*` untouched.
- **Final phase of the story:** on completion run the plan-completion protocol (docs update +
  completion summary + move to `docs/plans/completed/`).
