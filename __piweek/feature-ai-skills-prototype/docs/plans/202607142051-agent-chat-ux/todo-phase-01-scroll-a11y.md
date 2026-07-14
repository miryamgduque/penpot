# Phase 01 — Scroll + a11y

**Status:** todo

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

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Note follow-ups below
- [ ] Confirm Phase 02 can proceed

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — extract `transcript*`; a11y fixes
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — `.transcript` positioning, `.jump-to-latest`

## Notes

**Why `transcript*` must be extracted (not optional).** `use-visible`'s effect (`hooks.cljs:358-379`)
calls `.observe` on `(mf/ref-val ref)` with deps `[once?]` — it runs once at mount and **throws a
TypeError if the node is nil**. Today `(if (seq messages) …)` (`ai_panel.cljs:150`) means the
transcript doesn't exist on first render. A child that only mounts when there are messages owns
the ref safely.

**Read the ref, not the state, in the layout effect.** `at-bottom?` state reflects the
*pre-append* position — which is what we want — but reading it via `hooks/use-update-ref`
(`hooks.cljs:244`) avoids a stale closure and stops the effect re-running when the hook's async
state settles.

```
transcript*  {:keys [messages busy?]}
  scroll-ref    (mf/use-ref nil)
  end-ref       (mf/use-ref nil)
  at-bottom?    (hooks/use-visible end-ref)        ; drives the pill
  at-bottom-ref (hooks/use-update-ref at-bottom?)  ; drives the stick
```
`(mf/with-layout-effect [messages busy?] …)` → when `(mf/ref-val at-bottom-ref)`, call
`dom/set-scroll-pos!` (`dom.cljs:729`) with `.-scrollHeight`. Ordering is safe: React commits →
layout effect scrolls → observer re-fires and stays `true`.

**Known gap, stated honestly.** The shared observer is a `defonce` hardcoded to `rootMargin "0px"`
/ `threshold #js [0 1.0]` with **no `root`** (`hooks.cljs:344-350`), so it measures against the
viewport. That's still correct — per spec the intersection rect is clipped by ancestor `overflow`
— but `rootMargin: 0` makes "at bottom" exact-pixel, a brittle stick threshold. Fix in layout,
not by forking the observer:
```scss
.transcript-end { height: 24px; margin-block-start: -24px; pointer-events: none; }
```
**Fallback** if that proves fiddly in review: a plain `:on-scroll` handler writing
`(< (- scrollHeight scrollTop clientHeight) 24)` into a `mf/use-ref` — ~6 lines, no observer, no
async. Prefer `use-visible` first (reuse), but don't burn a day on it.

**The transcript is nested in a second scroll container.** `chat-tab*` renders inside
`tab-switcher*` with `:scrollable-panel true` (`ai_panel.cljs:333`), and `.scrollable-panel` is
`overflow-y: auto` (`tab_switcher.scss:120-121`). `.chat-tab` is `height:100%` so the outer never
scrolls, but it clips — hence `overscroll-behavior: contain`.

```scss
.transcript { position: relative; overscroll-behavior: contain; }  // has NO position today
.jump-to-latest { position: sticky; inset-block-end: var(--sp-s); align-self: center; z-index: 1; }
```
`position: sticky` beats `absolute` on a flex child — rides the column flow, no offset math.

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
