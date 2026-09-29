# Phase 01 — Scroll + a11y

**Status:** doing — implemented + verified live; awaiting approval to commit

## Verified live (devenv :3450, 2026-07-14)

Injected 24 transcript messages via the store (no API spend), then measured:

| # | Behaviour | Result |
|---|---|---|
| 1 | transcript scrolls at all | ✅ `scrollHeight 1849 > clientHeight 1007` |
| 2 | mounts pinned to newest | ✅ `distFromBottom 0` |
| 3 | no pill flash while pinned | ✅ |
| 4 | sticks on append while pinned | ✅ `distFromBottom 0` |
| 5 | pill appears on scroll-up | ✅ visible in scrollport |
| 6 | **append while scrolled up does not steal the view** | ✅ `scrollTop` stayed exactly 200 |
| 7 | pill returns to latest | ✅ `distFromBottom 0` |
| 8 | pill hides at bottom | ✅ |

a11y: `role=log` + `aria-live=polite`; picker `aria-expanded` false→true→false, `aria-haspopup=listbox`,
menu `role=listbox`, options `role=option` + `aria-selected`; **Escape closes the picker and returns
focus to the trigger** (was a real keyboard trap). Console clean apart from a pre-existing
`tabindex`/`tabIndex` React warning from elsewhere in the DS (not this file).

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check the metaprompt plan (US #26) hasn't reshaped `chat-tab*`
- [ ] Read `ui/workspace/ai_panel.cljs:74-218` and `ai_panel.scss:86-120` to confirm assumptions
- [ ] Confirm `hooks/use-visible` (`hooks.cljs:352-381`) and `use-update-ref` (`:244`) still have these signatures

## Checklist

- [ ] Extract a `transcript*` component owning the scroll + sentinel refs
- [ ] Stick-to-bottom via `hooks/use-visible` on an end sentinel
- [ ] "Jump to latest" pill when detached
- [ ] `role="log"` + `aria-live="polite"` on the transcript
- [ ] Fix `✕ Clear` accessible name, and the model picker's missing `aria-expanded`/`aria-haspopup`
- [ ] Escape closes the model picker; focus returns to the trigger
- [ ] Compile (`shadow-cljs compile main`) + preview review with browser tools
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:` / `:lipstick:`)

## After Finish

- [x] Rename `todo-` → `done-`; update README links
- [x] Note follow-ups below
- [x] Confirm Phase 02 can proceed — yes, independent (new `util/markdown.cljs` + the same two files)

### Follow-ups discovered

1. **DS: `icon-button*` has no accessible name until hover.** `has-tooltip` defaults true, routing
   `aria-label` through `aria-labelledby` → a tooltip node that is empty until hovered, so the
   computed name is `""`. Affects every icon-button in the app (verified on the pre-existing
   "Close Agent panel" button), not just this panel. Own ticket.
2. **The devenv SCSS watch does not pick up `.scss` edits** — even to an existing, already-watched
   file. Symptom: the panel renders completely unstyled (default yellow text, no layout). Fix is
   `node ./scripts/build-app-assets.js` (~3s) then reload. Needed on every phase that touches
   `ai_panel.scss` (02, 04, 06).
3. **Other panels may share the `min-height: 0` bug.** The `1fr` auto-minimum trap that let
   `.ai-panel` grow past the viewport would hit any workspace grid child whose content can outgrow
   the row. Worth checking the plugin dock.
4. **`aria-live` + streaming is still an open decision for Phase 08** — announcing every delta is
   hostile to screen readers. Gate on `:assistant-end`.

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — extract `transcript*`; a11y fixes
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — `.transcript` positioning, `.jump-to-latest`

## Notes

### Two plan assumptions were WRONG — found only by running it

**1. The real bug was never the missing autoscroll — the transcript could not scroll at all.**
`workspace.scss:17` sizes the row `1fr`, i.e. `minmax(auto, 1fr)`, and that **auto minimum** is the
content's min-content height. Every descendant defaults to `min-height: auto`, so a long transcript
grew the row to **1706px** against a 1267px viewport; `.workspace` is `overflow: hidden`, so the
panel was simply **clipped** and `.transcript`'s `overflow-y: auto` never engaged. Fix: `min-height: 0`
on `.ai-panel` (grid item), `.chat-tab` (grid item of the DS `.tab-panel`) and `.transcript` (flex
item). This is what US #27's *"we should be able to scroll the messages"* actually refers to — and no
amount of stick-to-bottom logic would have fixed it. **Only reproduces with enough messages to
overflow**, which is why it survived until now.

**2. `hooks/use-visible` is the wrong tool here — reverted to the documented fallback.**
It reports `false` until its observer first fires, so a fresh transcript never pinned to the latest
(measured: `distFromBottom 842`), and once content outgrows the panel the sentinel is never seen, so
it stays wrongly detached **forever**. It also flashed the pill on open. Replaced with a plain
`:on-scroll` handler + `mf/use-state true` (pinned by default), which the plan pre-authorised. The
sentinel and its `.transcript-end` rule are gone. `bottom-threshold` is 24px; state only updates on a
real edge crossing, so scrolling doesn't re-render per frame.

**3. The pill cannot live inside `.transcript`.** As a child of the scrolling box it scrolls away with
the messages — `position: sticky` did **not** save it (measured at `y=1897` with the scrollport ending
at 1125). It now sits in a `.transcript-wrap` (`position: relative`) as a **sibling** of the scrolling
element, positioned `absolute`.

**DS follow-up (not this phase):** `icon-button*` defaults `has-tooltip` to true and routes
`aria-label` through `aria-labelledby` → a tooltip that is empty until hover, so the computed
accessible name is `""`. Verified the **pre-existing** "Close Agent panel" button behaves identically,
so this is DS-wide, not introduced here. Worth its own ticket.

**Why `transcript*` is extracted.** It owns the scroll ref, and only mounts once there are messages
(`(if (seq messages) …)`, `ai_panel.cljs:150`), so the ref is guaranteed non-nil for its effects.

**Read the ref, not the state, in the layout effect.** `at-bottom?` state reflects the *pre-append*
position — which is what we want — but reading it via `hooks/use-update-ref` (`hooks.cljs:244`)
avoids a stale closure and stops the effect re-running when the state settles.

As built:
```
transcript*  {:keys [messages busy?]}
  scroll-ref    (mf/use-ref nil)
  at-bottom*    (mf/use-state true)                ; pinned by default
  at-bottom-ref (hooks/use-update-ref at-bottom?)  ; drives the stick
  on-scroll     -> reset! only on a real edge crossing (no per-frame re-render)
```
Two layout effects: one with `[]` (land on the newest message when the transcript first appears) and
one with `[messages busy?]` (stick while `at-bottom-ref`). Both use `dom/set-scroll-pos!`
(`dom.cljs:729`) with `.-scrollHeight`.

**The transcript is nested in a second scroll container.** `chat-tab*` renders inside
`tab-switcher*` with `:scrollable-panel true` (`ai_panel.cljs:333`), and `.scrollable-panel` is
`overflow-y: auto` (`tab_switcher.scss:120-121`) — hence `overscroll-behavior: contain` so scrolling
past our end doesn't chain to it.

**a11y specifics.**
- `role="log"` is the correct role for an append-only transcript; it implies `aria-live="polite"`
  — state it anyway per house style. **Never `assertive`** (would interrupt on every append).
  Precedent: `ds/notifications/toast.cljs:42`, `exports/files.cljs:185`.
- **Live-region + streaming conflict (decide now, not in Phase 08):** `aria-live` re-announces the
  whole region on every mutation, which is unusable with per-token deltas. Plan: announce on
  `:assistant-end` only, or move the live region to a visually-hidden node that receives the final
  text once the bubble seals. Phase 08 depends on this call.
- `✕ Clear` (`:179-184`) is a raw `[:button]` whose literal text node includes the glyph, so SRs
  read "multiplication x clear". Fix: `[:span {:aria-hidden true} "✕"]` + `"Clear"`, keep `:title`.
  Do **not** convert to `icon-button*` — that would drop the visible text.
- Model picker trigger (`:189-195`) needs `{:aria-expanded picker-open? :aria-haspopup "listbox"}`;
  the menu (`:198`) needs `:role "listbox"`, options `:role "option" :aria-selected`. Precedent:
  `combobox.cljs:306`, `select.cljs:177`.
- The picker closes on outside `pointerdown` (`:134-141`) but **not Escape**, and focus isn't
  restored — both keyboard-trap-adjacent. Add to the same effect.
- Pill: `icon-button*` with `:aria-label "Jump to latest message"` (the DS schema makes
  `:aria-label` non-optional).

**Alternatives rejected.** `dom/scroll-into-view-if-needed!` (`dom.cljs:753`, used by
`comments.cljs:1079`) is unconditional "always scroll" — the opposite of stick-only-when-at-bottom.
`layer_item.cljs:532` shows the root-scoped observer pattern if a custom root is ever needed. There
is no stick-to-bottom anywhere in Penpot today; this is the first.
