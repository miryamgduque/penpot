# Phase 03 — Elicitation form UI

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Review dependencies are met (Phase 02: `:pending-form` state + `submit-form`)
- [ ] Read relevant source files to confirm assumptions

## Checklist

- [ ] Write/update tests for pure answer-assembly logic (selection toggling,
      other-text merge, required-question gating)
- [ ] `elicitation-form*` component rendered at the tail of the transcript
      when `:pending-form` is set: title, then per question — prompt + hint,
      option chips (single = radio behavior, multi = checkbox chips),
      an "Other…" chip that expands an inline text input, a "Decide for me"
      chip when allowed, and a growing textarea for `text` questions
      (screenshots from the kickoff conversation are the visual reference)
- [ ] Submit button (enabled once required questions have an answer) →
      `dwaip/submit-form`; after submit the form collapses into a compact
      answers summary bubble in the transcript (the form itself is gone —
      the transcript must not lie about what was sent)
- [ ] Keyboard + a11y: chips are real buttons with `aria-pressed`,
      the form is reachable by Tab, Enter in "Other…" confirms rather than
      submitting the whole form
- [ ] Autoscroll: a form appearing counts as new content (the existing
      pin-to-bottom logic should carry it — verify)
- [ ] SCSS: match the panel's design language (muted borders, pill chips,
      Penpot DS tokens); both themes
- [ ] Lint + typecheck pass; `build-app-assets.js` after SCSS changes
- [ ] Preview review with MCP tools: drive `ask_user` from the console via
      `at.execute_tool` (no LLM needed) and exercise every control
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — `elicitation-form*` +
  transcript integration + answers-summary rendering
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — form styles
- `frontend/test/frontend_tests/data/…` — pure answer-assembly tests (put the
  logic in a data ns, not the component, so it's testable)

## Notes

- Devenv gotcha (memory): the SCSS watch does not pick up edits — run
  `node ./scripts/build-app-assets.js` in the container and reload after
  every SCSS change.
- Console driving (memory): `at.execute_tool(name, m)` with
  `cljs.core.assoc`/`cljs.core.keyword`-built maps, subscribe with
  `{next,error}` — much better than an LLM turn for exercising the form.
- Keep all answer-assembly logic in CLJS data fns; the component only
  renders and dispatches.
