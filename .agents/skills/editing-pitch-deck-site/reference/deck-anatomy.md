# Reference — deck anatomy, and how to change its structure

`deck.html` is one self-contained file: `<style>`, then the slides as
`<section class="slide">`, then one `<script>`. Read this before adding,
splitting, reordering or deleting a slide — **structure lives in four places
that must agree**, and nothing warns you when they drift.

## How it works

- **Slides** are `<section class="slide">`. Exactly one carries `.active`;
  the rest are `display:none`. `show(n)` toggles it.
- **Acts** come from the `groups` array in the script:
  ```js
  { name: "Deployment options", to: 12, goal: "How it can be deployed" }
  ```
  `to` is the **last slide number (1-based) in that act**, so acts are defined by
  their end boundary, not their start.
- **`groupFor(num)`** = `groups.find(g => num <= g.to) || groups[groups.length-1]`.
  Note the fallback: **a slide past the last `to` silently inherits the final
  act** instead of erroring. A slide meant to sit *outside* the acts (an add-on)
  is handled out-of-band instead of via `groups` — see `adding-an-addon-slide.md`.
- **Colours.** Each act has `--part-N` in `:root`. `show()` sets `--cur-part` on
  the document, and the brow (`.kicker`), the "Part N of N" text and the active
  dot all read `var(--cur-part, var(--skill))`. So **N acts require N `--part-*`
  colours.**
- **"Part N of N"** is computed from `groups.length`. Add an act and every slide
  renumbers automatically.

## Checklist: adding or splitting a slide

1. Add / split the `<section class="slide">`.
2. **Fix the `groups` boundaries.** Every act *after* the insertion point shifts
   by one. This is the step that's easy to forget.
3. If you added an **act**, add a matching `--part-N` colour, or its brow and dot
   fall back to the default blue.
4. Re-check any **cross-slide links** (`data-goto="N"`) — they are 1-based slide
   numbers and do **not** auto-update when slides move. Grep: `grep -o 'data-goto="[0-9]*"'`.
5. Verify (see the `verifying-pitch-deck-changes` skill): slide count, dot count, the act line on
   the slides either side of the change, and no overflow.

## Checklist: reordering slides

1. Move the whole `<section>` block.
2. Re-point any `data-goto` that referenced the moved slide **or** anything that
   shifted past it.
3. Act boundaries only change if the slide crossed an act line.

## Gotchas worth knowing

- **Slides don't scroll.** Each is `min-height:100vh` with `justify-content:center`.
  Content taller than the viewport is simply cut off, silently. Always check
  `scrollHeight > innerHeight` after adding content.
- **`justify-content:center` means inline expansion re-centres the slide**, which
  looks like a jump. That's why the explain popovers float (absolute) instead of
  pushing layout, and why the accordion opens one item at a time.
- **The glossary auto-wraps jargon** with hover tooltips, skipping anything inside
  `.term, .act, .kicker, h1, h2, code, .explain`. If you add a new interactive
  text component, add it to that reject list or you'll get two tooltips fighting.
- **Edge clicks navigate.** The outer 12% left/right change slide. Anything
  clickable near the edges must be added to the nav-ignore selector
  (`"a, .dots, .explain, .acc"`), or clicking it will also flip the slide.

## Where things are

| What | Roughly |
|---|---|
| `:root` colours, incl. `--part-1..7` | top of `<style>` |
| `groups` array (the acts) | top of `<script>` |
| `groupFor`, `show()` | after `groups` |
| glossary terms + reject list | mid `<script>` |
| explain / accordion / slink handlers | end of `<script>`, before `show(0)` |
