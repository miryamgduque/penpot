# Phase 04 — Composer send/stop

**Status:** done
**Depends on:** Phase 03 (cancel plumbing) ✅

## Verified live (devenv :3450) — driven through the real controls

| Behaviour | Result |
|---|---|
| textarea enabled when idle | ✅ |
| Send disabled while input is empty | ✅ |
| Send enables once you type | ✅ |
| clicking Send starts the turn | ✅ `busy? true` |
| **textarea stays enabled while busy** | ✅ |
| **you can type mid-turn** | ✅ |
| button swaps to a destructive Stop while busy | ✅ |
| clicking Stop ends the turn | ✅ `busy? false`, `⏹ Stopped.` |
| Enter still sends (keyboard path intact) | ✅ full turn replied `PONG`, input cleared |

Cost: two Haiku calls.

## Before Start

- [ ] Verify plan is still valid
- [ ] Confirm Phase 03 landed and `dwaip/cancel-turn` exists
- [ ] Read `ui/workspace/ai_panel.cljs:186-218` (composer) and `ds/buttons/icon_button.cljs`

## Checklist

- [x] Add an `icon-button*` to the composer that swaps Send → Stop on `busy?`
- [x] Remove `:disabled busy?` from the textarea
- [x] Stop emits `dwaip/cancel-turn`
- [x] Accessible names on both states ("Send message" / "Stop generating")
- [x] SCSS for the button inside `.composer` (new `.composer-row`)
- [x] Compile (0 warnings) + live preview review
- [x] Human approval received
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename `todo-` → `done-`; update README links
- [x] File the DS `stop` icon follow-up (see Notes)

### Follow-ups

1. **DS: no `stop` glyph.** Using `i/close` for now. A filled square belongs in the DS
   (`ds/foundations/assets/icon.cljs` + the `collect-icons` macro) — own ticket, along with the
   `icon-button*` empty-accessible-name issue from Phase 01.
2. **Stop still only halts the panel.** The provider keeps generating (and billing) server-side
   until Phase 07 lands the upstream abort. Don't describe it as "stops the agent" until then.

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
