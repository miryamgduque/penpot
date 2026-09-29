# Reference — the deck's interactive components

Three custom components. All are plain HTML + a few lines of JS at the end of the
script; none need a library.

## 1. Click-to-explain popovers

For a dense bullet: click it, a plain-language explanation floats near it.
Used on slides 3, 10, 11, 14, 15, 16, 17.

**Markup** — add the class, append a `.detail` span inside the `<li>`:
```html
<li class="explain">The visible bullet text.<span class="detail">The
plain-language explanation, shown on click.</span></li>
```
That is all. The handler binds every `.explain` on load, so a new one just works.

**Behaviour you get free**
- Accordion: opening one closes the others on that slide.
- Click inside the popover does **not** close it (so you can read/select).
- Click outside, or changing slide, closes it.
- It **floats** (absolute) rather than pushing layout, so the slide doesn't
  re-centre and jump.
- Positioning: below the line, shifted right to overlap the text end by ~200px on
  wide screens; falls back to below-left when there's no room; **flips above the
  line** when opening below would run off the bottom.
- The glossary's hover tooltips are suppressed inside `.explain` so the two
  don't collide.

**Writing the explanation.** It should say what the jargon *means* and why it
matters, in the register of "explaining to a smart colleague outside the project".
Define the term inline (`a11y`, `CI`, `MCP`, `write path`) rather than assuming.

## 2. The roadmap accordion

Used only on the Roadmap slide. Header row = when / title / what it needs /
chevron; body = the expansion.

```html
<div class="acc">
  <button class="acc__head" type="button">
    <span class="acc__when">Aug '26</span>
    <span class="acc__title">Harden</span>
    <span class="acc__dep">needs: nothing</span>
    <span class="acc__chev" aria-hidden="true">&rsaquo;</span>
  </button>
  <div class="acc__body"><p>The expansion…</p></div>
</div>
```

- One open at a time (keeps the slide inside the viewport).
- The chevron rotates and takes the current act colour when open.
- `.acc__opt` is the small **OPTIONAL** badge (also reused on the ask slide).
  At the start of a line, zero its left margin: `style="margin-left:0"`.

## 3. Cross-slide links

Inside a popover or accordion body, link to another slide:

```html
<a class="slink" data-goto="13">plan tool</a>
```

- `data-goto` is the **1-based slide number**. It does not auto-update if slides
  move — re-check after any reorder.
- External links are ordinary `<a href="…" target="_blank" rel="noopener">`.
  **Do not** give those the `slink` class: the handler calls `preventDefault()`
  and would swallow the navigation.
- Links inside accordion bodies are styled colour-only, **no underline**, and
  brighten on hover.

## Adding a new clickable component

1. Add it to the nav-ignore selector so edge clicks don't also flip the slide:
   `if (e.target.closest("a, .dots, .explain, .acc")) return;`
2. Add it to the glossary reject list if it contains prose, so you don't get
   competing tooltips.
3. Prefer floating (absolute/fixed) over inline expansion, because slides are
   fixed-height and vertically centred.
