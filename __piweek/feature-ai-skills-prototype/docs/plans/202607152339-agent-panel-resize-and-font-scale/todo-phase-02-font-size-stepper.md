# Phase 02 — Font Size Stepper

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Review dependencies are met (Phase 01 merged — the stepper shares the panel header/persistence patterns it establishes)
- [ ] Read relevant source files to confirm assumptions (typography mixin usage in `ai_panel.scss`; check what `body-small-typography` sets for `line-height` — px values would need scaling too)

## Checklist

- [ ] Write tests for the step model (pure fns: step → scale, next/prev step with clamping at both ends)
- [ ] Define the scale steps (proposed: `[0.85 1 1.15 1.3 1.45]`, default index 1 = today's sizes) and persist the index via `use-persisted-state ::font-step`
- [ ] Set the scale as a CSS custom property (e.g. `--ai-font-scale`) via inline style on the root `:aside`
- [ ] In `ai_panel.scss`, route text sizing through the variable: introduce local mixins (e.g. `@mixin body-text` = DS mixin + `font-size: calc(<base> * var(--ai-font-scale, 1))`) and swap the existing `@include`s to them — one mechanical single-file pass
- [ ] Add the A− / A+ stepper to the panel header actions (ghost icon-buttons beside the Skills icon); disable at min/max step; aria-labels ("Decrease text size" / "Increase text size")
- [ ] Lint + typecheck pass (frontend lint; run frontend tests)
- [ ] Preview review with MCP tools: step through all sizes in chat + skills views, verify persistence across reload, check composer/chips/tool payloads at the largest step
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles: Let the agent panel text be resized`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Plan complete → completion summary in README, move folder to `completed/`

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — step state, inline CSS var, stepper buttons in the header
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — local text mixins wrapping the DS typography mixins; `calc()` against `--ai-font-scale`
- `frontend/test/frontend_tests/...` — step model tests

## Notes

**Why a CSS variable + local mixins, not inheritance or `zoom`:** every text
element in the panel gets an explicit `font-size` from a DS typography mixin, so
setting `font-size` on the root inherits into nothing. CSS `zoom` scales the
whole box model (paddings, buttons, icons) and breaks the absolutely-positioned
composer controls. Overriding `font-size` *after* each mixin include — factored
into two or three local mixins so it stays one mechanical pass — keeps the change
contained to `ai_panel.scss` and scales only text.

**Scope check during execution:** a few sizes live outside the body mixin —
`.tool-detail-payload` (`$fs-11` monospace), `.catalog-name` (`title-small`),
`.detail-name`, `.connect-title`, header `.title`. Decide per-class: transcript
and reading surfaces scale; the header title band (which must stay aligned with
the workspace right-header) arguably shouldn't. Record the final call here.

**Line-height:** if the DS mixins set px line-heights, the local mixins must also
scale them (`calc()` likewise) or text overlaps at the top step. Verify first.

**Placement:** header-actions only render in the chat view; the scale variable
sits on the panel root so it applies to Skills views regardless. If that feels
undiscoverable during preview review, consider also showing the stepper in the
Skills header — decide on sight, note the outcome.
