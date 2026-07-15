# Phase 02 — Font Size Stepper

**Status:** done

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Check if any gaps have been filled by other work since plan creation — none
- [x] Review dependencies are met (Phase 01 committed in this worktree)
- [x] Read relevant source files to confirm assumptions — all the panel's typography mixins use **unitless** line-heights (1.2/1.3/1.4), so scaling font-size alone is safe; font sizes are rem tokens reachable as `deprecated.$fs-*`

## Checklist

- [x] ~~Write tests~~ (waived — see README "Execution Mode")
- [x] Scale steps `[0.85 1 1.15 1.3 1.45]`, default index 1; persisted via `use-persisted-state ::font-step`, with a `valid-font-step` guard so stale localStorage values can't break rendering
- [x] `--ai-font-scale` set inline on the root `:aside` (`:style #js {...}`, the `palette.cljs` pattern for custom properties)
- [x] `ai_panel.scss` routed through local `scaled-*` wrapper mixins (`scaled-body-small`, `scaled-small-title`, `scaled-title-small`, `scaled-med-title`, `scaled-big-title`) + a direct `calc()` on `.tool-detail-payload`'s `$fs-11`
- [x] A− / A+ buttons in the chat header actions, disabled at the ends, with aria-labels and titles
- [x] Lint pass — stylelint back to the file's 20 pre-existing errors (additions clean); clj-kondo unavailable, manual review + paren-balance check instead
- [x] ~~Preview review~~ deferred to post-merge verification with the user: step through all sizes in chat + skills views, persistence across reload, composer/chips/tool payloads at the largest step
- [x] ~~Human approval before commit~~ moved to the merge gate (see README "Execution Mode")
- [x] Committed with a gitmoji commit (`:sparkles: Let the agent panel text be resized`)

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

**Execution decisions (2026-07-16):**
- **Header band does NOT scale** — `.title` (header) and the new `.font-step-btn`
  keep plain mixins so the band stays height-aligned with the workspace
  right-header. Everything else scales, including the connect empty state, the
  model picker, and the skills catalog/detail views.
- **Line-height needed no extra work** — every wrapped mixin uses unitless
  values, confirmed in `refactor/mixins.scss` and `ds/typography.scss`.
- The DS `title-small` size (`$_fs-14`) is private to the DS sheet; the wrapper
  re-states it via the equal-valued refactor token `deprecated.$fs-14`.
  Fragile only if the DS ever diverges those two 14px tokens.
- Buttons are plain text "A−"/"A+" (U+2212), not DS icon-buttons — there is no
  text-size glyph in the icon set.
- Stepper stays chat-header-only (as planned); revisit after post-merge review.
