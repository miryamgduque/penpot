# Phase 01 — Slash-command menu

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Review dependencies are met
- [ ] Read relevant source files to confirm assumptions (`chat-tab*` composer, `ask/enabled-skills`)

## Checklist

- [ ] Write/update tests for the pure menu logic (registry merge + filtering)
- [ ] New `slash-commands` fn/ns: build the menu model from app state — special
      commands first, then enabled skills (built-in + user) with `:label`,
      `:description` (blurb) and an `:insert` (the skill's `:example` phrase) —
      and a pure prefix/fuzzy filter over what follows the `/`
- [ ] Composer: when `input` starts with `/`, render a popover above the input
      listing the filtered entries; ↑/↓ move the highlight, Enter picks,
      Esc closes, click picks; typing keeps filtering
- [ ] Picking a **skill** replaces the input with its trigger phrase (user can
      edit, then send as normal); picking a **command** dispatches its action
      (this phase ships the registry with `/vibes` present but stubbed to
      insert its trigger phrase — the real action lands in Phase 05)
- [ ] Enter with the menu open must pick, not send — guard `on-key-down`
- [ ] SCSS in `ai_panel.scss` following the model-picker popover's patterns
      (incl. the outside-click/Escape close behavior)
- [ ] Lint + typecheck pass (`pnpm run lint:clj` / cljfmt in devenv)
- [ ] Preview review with MCP tools (menu open/filter/keyboard/pick)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/slash_commands.cljs` — NEW: command
  registry + menu model + pure filter (testable without UI)
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — composer: slash
  detection, popover render, keyboard handling
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — popover styles
- `frontend/test/frontend_tests/data/slash_commands_test.cljs` — NEW: filter +
  registry tests (remember the runner's separate `test-namespaces` vector!)

## Notes

- The menu reads `ask/enabled-skills` (already resolves built-in defaults →
  account → per-file overrides and merges user skills), so disabled skills
  never show — consistent with the agent's own routing index.
- A `/` mid-sentence must NOT open the menu — only when the input starts
  with `/` (matches every chat product's convention).
- The frontend test runner gotcha (memory): adding the ns to `:require` alone
  silently skips it; it must also be in the `test-namespaces` vector, and the
  test names must appear in `node target/tests/test.js` output.
