# Phase 02 — Affected strip UI

**Status:** todo

## Goal

Surface the live violations set in the chat tab: a collapsed one-liner directly
below the context chip ("⚡ 12 layers need attention · 2 rules") that expands to
a per-rule breakdown (rule label, count, affected layer names). Disappears at
zero. No actions yet — Fix it now is Phase 03.

## Before Start

- [ ] Verify plan is still valid; Phase 01 merged and `refs/ai-panel-violations`
      exists
- [ ] Re-read `chat-tab*` in `ui/workspace/ai_panel.cljs` — exact render slot
      between the context chip and the transcript
- [ ] Check existing panel SCSS patterns (`ai_panel.scss`) for chip/collapse
      styles to reuse

## Checklist

- [ ] Pure helper + tests: group violations by rule → `[{rule, label, count,
      shapes}]`, ordered stable; one-liner summary string (singular/plural)
- [ ] `affected-strip*` component: collapsed row (summary + chevron), expanded
      per-rule groups; cap the visible shape-name list (~8, "+N more"); local
      expand state (`mf/use-state`, not app state)
- [ ] Render in `chat-tab*` below the context chip only when violations are
      non-empty
- [ ] Clicking a shape name selects it on canvas (same select behavior the old
      plugin's ⌖ had) — small, but makes the strip navigable in the demo
- [ ] SCSS following the panel's existing patterns; both themes
- [ ] Lint + format; `shadow-cljs compile main` 0 warnings
- [ ] Preview verify in devenv (**run `build-app-assets.js` after SCSS changes —
      the watch misses them**): strip appears/updates/disappears live while
      editing; expand/collapse; select-on-click
- [ ] Human approval received (with screenshot)
- [ ] Committed with a gitmoji commit (`:sparkles:` or `:lipstick:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README link
- [ ] Note any layout constraints discovered (panel min-height interplay)

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — `affected-strip*`,
  grouping helper, render slot in `chat-tab*`
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — strip styles
- `frontend/test/frontend_tests/data/agent_watcher_test.cljs` — grouping/summary
  helper tests (keep pure helpers in a data ns if the UI ns can't be tested)

## Notes

- The strip must not fight the transcript's `min-height:0` scroll fix (the
  agent-chat-ux plan's biggest find) — it's a fixed-height sibling above
  `transcript-wrap`, never part of the scroll area.
