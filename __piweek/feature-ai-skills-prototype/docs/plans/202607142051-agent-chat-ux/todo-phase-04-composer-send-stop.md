# Phase 04 — Composer send/stop

**Status:** todo
**Depends on:** Phase 03 (cancel plumbing)

## Before Start

- [ ] Verify plan is still valid
- [ ] Confirm Phase 03 landed and `dwaip/cancel-turn` exists
- [ ] Read `ui/workspace/ai_panel.cljs:186-218` (composer) and `ds/buttons/icon_button.cljs`

## Checklist

- [ ] Add an `icon-button*` to the composer that swaps Send → Stop on `busy?`
- [ ] Remove `:disabled busy?` from the textarea
- [ ] Stop emits `dwaip/cancel-turn`
- [ ] Accessible names on both states
- [ ] SCSS for the button inside `.composer`
- [ ] Compile + preview review with browser tools
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] File the DS `stop` icon follow-up (see Notes)

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — composer controls
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — button layout

## Notes

**There is no send button today.** The composer (`:213-218`) is a bare `<textarea>`; Enter sends
(`on-key-down`, `:119-125`). So the story's "the Send button becomes Stop" has nothing to attach to
— we're adding the button, not repurposing one.

**Removing `:disabled busy?` from the textarea (`:216`) is the bigger UX win of the two.** Being
unable to even *type* while a 32-round turn runs is the harshest part of the current experience, and
it's unnecessary — only *submission* needs gating. Keep Enter-to-send (discoverability +
keyboard a11y: a keyboard-only affordance with no visible control is a real gap today) and gate it
on `busy?` in the handler.

One button, role swaps on `busy?`:
- idle: `{:icon i/arrow-up :aria-label "Send message"}`, disabled when input is blank
- busy: `{:icon i/close :variant "destructive" :aria-label "Stop generating"
          :on-click #(st/emit! (dwaip/cancel-turn))}`

`icon-button*` **requires** `:aria-label` — it's non-optional in the malli schema
(`icon_button.cljs`, `[:aria-label :string]`) and it sets `:aria-hidden true` on the inner icon. The
existing close button (`ai_panel.cljs:321-324`) is the model to copy.

**Icon gap.** There is **no `stop` glyph** in the DS — I enumerated all 293 `^:icon-id` defs in
`ds/foundations/assets/icon.cljs`; the closest are `close`, `close-small`, `play`, `tick`, `expand`,
`arrow-up`, `arrow-down`. Use `i/close` here and file adding a filled-square `stop` as a follow-up:
that's a DS change (new SVG + the `collect-icons` macro at `icon.clj:12`), correctly out of scope.

**Honesty check for the demo.** Until Phase 07 lands, Stop halts the *panel* but the provider keeps
generating server-side. Don't describe it as "stops the agent" in review notes until 07 is proven.
