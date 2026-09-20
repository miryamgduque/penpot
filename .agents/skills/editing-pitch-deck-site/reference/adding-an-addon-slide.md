# Reference — adding an add-on slide (outside the numbered acts)

Sometimes you want a slide that lives at the end but is **not** part of the
numbered story — an appendix, a "one more thing", a topic bolted on after the
close. It should not renumber "Part N of N" or borrow the last act's colour.
Slide 20 ("Nitrate") is the worked example. Read `deck-anatomy.md` first for how
acts and `groups` work; this is the deliberate exception to that machinery.

## Why not just extend `groups`

`groups` is boundary-based and `groupFor(num)` falls back to the **last** act for
any slide past the final `to`. So an appended slide either:

- silently inherits the last act (the old bug — it showed "Part 7 of 7 · The
  roadmap (and the ask)"), or
- if you add a `groups` entry for it, it becomes **"Part 8 of 8"** and bumps the
  "of N" total on *every* slide.

Neither is what an add-on wants. The fix is to handle it **out-of-band**: mark the
slide and special-case it in `show()`, leaving `groups` untouched.

## The five touch points

All line numbers are approximate — grep the anchors.

1. **The section** (`grep -n 'data-addon' deck.html`). Mark it and give it the
   background class:
   ```html
   <section class="slide slide--addon" data-addon>
   ```
   `data-addon` is the flag everything else keys off; `slide--addon` paints the
   background.

2. **`:root` tokens** (`grep -n -- '--addon' deck.html`). Its own accent + a
   background that is **another tone of the same neutral**, not a new hue:
   ```css
   --addon:#63E6BE; --addon-bg:#1C1D26;   /* bg is #121218 → this → surface #191A22 → card #1E202B */
   ```

3. **The background rule** (right after `.slide.active`):
   ```css
   .slide--addon { background: var(--addon-bg); }
   ```

4. **`show()` special-case** (`grep -n 'hasAttribute("data-addon")' deck.html`).
   Bypass the `groups` / "Part N of N" path:
   ```js
   if (slides[i].hasAttribute("data-addon")) {          // add-on: not a numbered part
     navAct.innerHTML = '<span class="act__part">Add-on</span>' +
       '<span class="act__goal">' + slides[i].querySelector("h2").textContent + '</span>';
     document.documentElement.style.setProperty("--cur-part", "var(--addon)");
   } else { /* the normal Part-N branch */ }
   ```

5. **The dot** (`grep -n 'addonIdx' deck.html`). Relabel it and set it apart from
   the previous group, since `groupStarts` won't mark it:
   ```js
   const addonIdx = slides.findIndex((s) => s.hasAttribute("data-addon"));
   if (addonIdx > -1) {
     dots[addonIdx].setAttribute("aria-label", "Add-on · " + slides[addonIdx].querySelector("h2").textContent);
     dots[addonIdx].classList.add("group-start");
   }
   ```

## Do NOT

- **Add it to `groups`** or bump the last act's `to`. Either makes it a numbered
  part again and changes the "of N" total on every slide.
- **Mint a `--part-N` for it.** Add-ons use `--addon`, not the act ramp.
- **Pick a new hue for the background.** Keep `--addon-bg` inside the neutral ramp
  (a lighter/darker tone of `--bg`) so the cards still separate above it. (We tried
  a mint tint first; a same-hue tone reads as "a different slide", not "a different
  theme".)

## Gotchas

- **It still counts in `slides.length`.** Slide-count and dot-count checks expect
  the +1 — but it must **not** count toward `groups.length` ("Part N of **N**").
- **`data-goto` links.** The add-on sits after the last numbered slide, so existing
  links are unaffected. If you ever link *to* it, use its 1-based index.
- **More than one add-on?** The `show()` check and `findIndex` handle each slide
  fine, but the dot-relabel block only touches the *first* `data-addon`. Loop over
  all of them instead.
- **Print/export.** `@media print` shows every `.slide`; the add-on keeps its
  background tone there too. That's usually what you want.

## Verify (see the `verifying-pitch-deck-changes` skill)

```js
show(<addonIndex>);
document.querySelector('#nav-act').textContent;                 // "Add-on · <heading>", NOT "Part …"
show(<anyNumberedSlide>);
document.querySelector('#nav-act').textContent;                 // still "Part N of 7" — total unchanged
getComputedStyle(document.querySelector('.slide--addon')).backgroundColor;   // differs from --bg
const s = document.querySelector('.slide--addon');
s.getBoundingClientRect().height === innerHeight;               // background fills the viewport
```

## Where things are

| What | Anchor to grep |
|---|---|
| `--addon`, `--addon-bg` in `:root` | `grep -n -- '--addon' deck.html` |
| `.slide--addon` background rule | `grep -n 'slide--addon' deck.html` |
| the section itself | `grep -n 'data-addon' deck.html` |
| `show()` add-on branch | `grep -n 'hasAttribute("data-addon")' deck.html` |
| dot relabel + separator | `grep -n 'addonIdx' deck.html` |
