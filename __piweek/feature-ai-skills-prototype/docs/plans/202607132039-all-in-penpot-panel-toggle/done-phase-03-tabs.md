# Phase 03 — Two-tab shell (Chat / Skills)

**Status:** done

## Goal

Inside the panel, a two-tab switcher (**Chat** / **Skills**) mirroring the left sidebar's Layers/Assets pattern, with the active tab persisted. Tab bodies are stubs in this phase (Chat gets its real transcript in Phase 05; Skills stays a placeholder for its own story).

## Before Start

- [ ] Re-read `history-content*` (sidebar.cljs:239-281) — the closest precedent: `use-persisted-state` + `tab-switcher*` + `(case selected ...)`
- [ ] Re-read the `tab-switcher*` API (ds/layout/tab_switcher.cljs:102-113): `:tabs` (vector of `{:id :label :icon? :aria-label?}`), `:selected` (string id), `:on-change` (fn of id), optional `:action-button`
- [ ] Confirm `use-persisted-state` signature (hooks.cljs:328-343)

## Checklist

- [x] In `ai_panel.cljs`, added persisted tab state `(hooks/use-persisted-state ::ai-panel-tab "chat")`. ✓
- [x] Rendered `[:> tab-switcher* {:tabs [{:id "chat" :label "Chat"} {:id "skills" :label "Skills"}] :selected tab :on-change ... :scrollable-panel true :action-button close-btn :action-button-position "end"}]` with a `(case tab "chat" … "skills" …)` body. The **close button moved into the tab-switcher's action-button** (end), replacing the Phase 01 title header — matching the `history-content*` precedent and the Layers/Assets look. Labels are literals (prototype, no i18n). ✓
- [x] Chat stub: empty transcript message + disabled `Ask the agent…` composer. Skills stub: "coming in its own story" placeholder. ✓
- [x] Styled `.tabs`/`.chat-tab`/`.composer`/`.skills-tab` in `ai_panel.scss` to fill panel height. ✓
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, `stylelint` clean, `shadow-cljs compile main` → **0 warnings**. ✓
- [x] Preview review (live devenv): CHAT/SKILLS tabs render (Chat default); switching to Skills shows its stub; **active tab persists across close/reopen**; close **×** works. ✓
- [ ] Human approval received
- [ ] Commit: `feat(workspace): all-in-penpot chat/skills tab shell`

## After Finish

- [ ] Rename `todo-` → `done-`, update README link
- [ ] Note tab ids + i18n keys in Notes

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — tab switcher + stub bodies
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — tab + body layout
- `frontend/translations/en.po` — tab labels

## Notes

- Active-tab *preference* persisting across reload is fine and matches the Layers/Assets behaviour; it is separate from the open/closed + chat-content persistence handled in Phases 04–05.
- Keep the two tab ids stable (`"chat"`, `"skills"`) — Phase 05 and the future skills story key off them.
- **Design refinement (user request, 2026-07-13):** added an **"Agents" title** header band (height `$s-48`, matching the workspace right-header) with the close **×** moved into it; the CHAT/SKILLS `tab-switcher` now sits below the title and **lines up exactly with the sidebar's Design/Prototype/Inspect tabs** (both at `top: 59px`, verified live). Added a `border-inline-start: $s-1 solid var(--panel-border-color)` **separator** from the options sidebar, and matched panel padding via `--sp-m` (inline + `--tabs-nav-padding-inline-*`, same as `.options-tab-switcher`).
