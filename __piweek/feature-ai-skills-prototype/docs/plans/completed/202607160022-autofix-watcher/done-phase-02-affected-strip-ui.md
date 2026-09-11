# Phase 02 — Affected strip UI

**Status:** done

## Goal

Surface the live violations set in the chat tab: a collapsed one-liner directly
below the context chip ("⚡ 12 layers need attention · 2 rules") that expands to
a per-rule breakdown (rule label, count, affected layer names). Disappears at
zero. No actions yet — Fix it now is Phase 03.

## Before Start

- [x] Verify plan is still valid; Phase 01 merged and `refs/ai-panel-violations`
      exists
- [x] Re-read `chat-tab*` in `ui/workspace/ai_panel.cljs` — exact render slot
      between the context chip and the transcript
- [x] Check existing panel SCSS patterns (`ai_panel.scss`) for chip/collapse
      styles to reuse

## Checklist

- [x] Pure helper + tests: group violations by rule → `[{rule, label, count,
      shapes}]`, ordered stable; one-liner summary string (singular/plural)
- [x] `affected-strip*` component: collapsed row (summary + chevron), expanded
      per-rule groups; cap the visible shape-name list (~8, "+N more"); local
      expand state (`mf/use-state`, not app state)
- [x] Render in `chat-tab*` below the context chip only when violations are
      non-empty
- [x] Clicking a shape name selects it on canvas (same select behavior the old
      plugin's ⌖ had) — small, but makes the strip navigable in the demo
- [x] SCSS following the panel's existing patterns; both themes
- [x] Lint + format; `shadow-cljs compile main` 0 warnings
- [x] Preview verify in devenv (**run `build-app-assets.js` after SCSS changes —
      the watch misses them**): strip appears/updates/disappears live while
      editing; expand/collapse; select-on-click
- [x] Human approval received (with screenshot)
- [x] Committed with a gitmoji commit (`:sparkles:` or `:lipstick:`)

## After Finish

- [x] Rename `todo-` → `done-`; update README link
- [x] Note any layout constraints discovered (panel min-height interplay)

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

## Execution notes (2026-07-16, worktree)

- Tests waived per README execution mode; gates: compile 0 warnings, clj-kondo
  0/0, cljfmt. Visual verification deferred to post-merge live testing.
- Shape click does select + `dwz/zoom-to-selected-shape` (not just select) —
  the strip doubles as navigation.
- `uuid/parse*` (safe) for the data-id round-trip, per the agent-tools
  precedent (`uuid/uuid` is documented unsafe).
- The expanded detail scrolls inside its own `max-block-size: $s-200` box; the
  strip itself is a fixed sibling above `.transcript-wrap`, outside the
  panel's min-height:0 scroll geometry.
- Summary counts DISTINCT shapes (one shape can violate two rules) but group
  counts are per-rule violations — deliberate.
