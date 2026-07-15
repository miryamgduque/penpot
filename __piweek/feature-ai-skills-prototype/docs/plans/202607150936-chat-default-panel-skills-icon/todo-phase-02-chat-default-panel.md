# Phase 02 — Chat-default panel + adaptive header + Skills view

**Status:** todo

## Goal

Replace the Chat/Skills `tab-switcher*` with a **chat-default** panel: chat is the whole body (no
tabs, no "Chat" label), a muted `list-checks` icon in the header opens **Skills as a full-panel
view** with an adaptive `← Skills` header, and back returns to chat. The view state is **in-memory**
and defaults to chat, so the panel always reopens on chat (US #2).

## Before Start

- [ ] Re-read `ai-panel*` (the header + `tab-switcher*` block) and its docstring/comments (they
      reference tabs)
- [ ] Confirm `i/arrow-left` (back) and `i/list-checks` (from Phase 01) resolve
- [ ] Re-read `.header` SCSS + `icon-button*` ghost usage (the muted treatment) and `.tabs` class
- [ ] Note: `chat-tab*` and `skills-tab*` are reused **unchanged**; only the shell changes

## Checklist

- [ ] **View state:** replace `tab*` (`use-persisted-state ::ai-panel-tab`) with an **in-memory**
      `mf/use-state` view (`:chat` | `:skills`), default `:chat`. Resets to chat on panel
      close/reopen (component unmount) — the US #2 "reopens on chat" guarantee, now tab-free.
      Remove the `tabs` memo and the `::ai-panel-tab` persistence.
- [ ] **Adaptive header** (single row, close in both):
      - `:chat` → "Agent" title (left) + muted `list-checks` `icon-button*` (ghost) + close
      - `:skills` → `←` back `icon-button*` (ghost, `i/arrow-left`) + "Skills" title + close
- [ ] **Body:** `:chat` → `chat-tab*` fills the panel (no tab chrome). `:skills` → `skills-tab*`
      full-panel over the chat.
- [ ] **Icon actions:** `list-checks` → set view `:skills`; header back → set view `:chat`. Instant.
- [ ] **No-provider case:** `connect-empty*` still shows for the chat view when the pool is empty;
      Skills (static catalog) stays reachable via the icon. Keep the icon visible.
- [ ] **Cleanup:** update `ai-panel*` docstring + the "tabs below line up" header comment; drop the
      `tab-switcher*` require if now unused; remove the `.tabs` style if orphaned.
- [ ] **SCSS:** header lays out title/back on the left, `list-checks`+× (or ×) on the right; muted
      `list-checks` (ghost, low-emphasis). Keep the header sized to the workspace right-header band.
- [ ] `make lint` (clj-kondo) + `:main` + SCSS build, 0 warnings
- [ ] Verify in the devenv / logged-in browser: opens on chat, no tabs; icon → Skills full-panel
      with `← Skills`; back → chat; drill into a skill (inner "← All skills" still works);
      close + reopen → chat
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:` / `:recycle:`)

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
