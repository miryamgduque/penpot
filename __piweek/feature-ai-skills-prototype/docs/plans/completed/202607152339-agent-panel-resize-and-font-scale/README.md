# Agent Panel: Resizable Width + Font Size Stepper

**Status:** done
**Created:** 2026-07-15
**Apps:** `frontend`
**Dependencies:** None

## Context

The Agent panel is a fixed 360px right-docked column. Long transcripts — markdown
replies, tool payloads, code blocks — get cramped at that width, and the panel's
text (all `body-small-typography`, 12px-class sizes) is small for extended reading.

Two ergonomic upgrades, both decided in the discovery interview:

- **Resizable width** via a drag handle on the panel's left edge — the same
  interaction as the Layers/Design sidebars.
- **Font size stepper** (A− / A+) scaling *all* panel text: transcript, composer,
  skills catalog, chips.

Both preferences persist **globally** (one width, one font size across all files),
via the existing `hooks/use-persisted-state` (localStorage-backed, already used in
this panel for the remembered model). This deliberately does *not* use
`use-resize-hook`'s built-in persistence, which is keyed per-file.

## Execution Mode (deviation, agreed 2026-07-15)

Executed in a worktree (`feature/agent-panel-resize`, branched off
`feature/ai-skills-prototype`) while other sessions work on the base branch:

- **No tests** — the user waived the tests-first step for both phases.
- **Per-phase commits happen without a stop-and-ask** — the review gate moves to
  the end: merge into `feature/ai-skills-prototype` only with the user's explicit
  go-ahead (they confirm no other session is mid-flight), then verify visually
  in the running dev environment together.

## Phases

1. [Phase 01 — Resizable width](./done-phase-01-resizable-width.md) — drag handle on the left edge, min 360 / max clamped to viewport, globally persisted
2. [Phase 02 — Font size stepper](./done-phase-02-font-size-stepper.md) — A−/A+ control, discrete steps, CSS-variable-driven scale over the whole panel

## Completion Summary

**Completed:** 2026-07-16 — merged to `feature/ai-skills-prototype` (`158baa24af`) and
verified live in devenv (localhost:3450, Chrome): drag 360→654 pixel-exact, max clamp
= half window (756), min clamp = 360, width persisted across hard reload; stepper
1→1.45 with A+ disabling at max, composer text 12→17.4px while the header title held
14px, scale persisted across reload; a live agent turn rendered correctly at max scale.

### What Shipped
- Left-edge drag handle resizing the panel between 360px and half the window,
  width persisted globally via `use-persisted-state ::panel-width`.
- A−/A+ stepper in the chat header stepping all panel body text through
  `[0.85 1 1.15 1.3 1.45]` of the design sizes, via a `--ai-font-scale` custom
  property and local `scaled-*` mixin wrappers in `ai_panel.scss`; step
  persisted globally via `::font-step`.

### What Changed from Original Plan
- Tests waived and per-phase approval folded into the merge gate (user
  decision — see "Execution Mode").
- The header band (title + stepper buttons) deliberately does not scale, to
  stay aligned with the workspace right-header.

### Lessons & Follow-ups
- No Clojure lint tooling exists outside devenv on this machine; cljs changes
  got manual review + paren-balance only — the shadow-cljs compile after the
  merge was the real syntax gate (0 warnings).
- **A replace-all bit back** (`baed6fd6f3`): routing includes through the
  `scaled-body-small` wrapper also rewrote the include *inside the wrapper's
  own definition* → infinite Sass recursion, stack overflow at asset build.
  Stylelint can't catch self-recursive mixins; only evaluating Sass does.
  Lesson: exclude the definition site when replace-all'ing an include.
- The stepper on-clicks close over the rendered step, so multiple *synthetic
  same-tick* clicks only step once (verified in console). Human clicks
  re-render between presses and are unaffected; `swap!` with the clamp inside
  would make it airtight if it ever matters.
- **User-caught post-close bug** (`bf81da8243`): Penpot's legacy global
  `p { font-size: $fs12 }` (base.scss:69) beat the bubble's *inherited* scaled
  size, pinning assistant markdown paragraphs at 12px while user bubbles
  scaled. Invisible at scale 1 — which is exactly why measuring the bubble
  *container's* computed style passed. Lesson: when scaling by inheritance,
  measure the innermost text element, and expect bare element selectors in
  legacy globals to defeat inheritance.
- Same pattern, second instance (also user-caught): the app-level
  `.workspace ::placeholder { font-size: $fs12 }` pinned the composer
  placeholder at 12px while the typed text scaled. Fixed with a
  class+element+pseudo rule on the panel root (out-ranks class+pseudo) setting
  `font-size: inherit`. Audit checklist for scaled surfaces: innermost
  elements, pseudo-elements (`::placeholder`), and anything a global element
  rule can reach.
- Follow-up candidates: show the stepper in the Skills header too (currently
  chat-only, though the scale applies panel-wide); double-click the drag
  handle to reset width to default.

## Key Code

- [`ai_panel.cljs`](../../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs) — the panel component (`ai-panel*` root renders the `:aside`)
- [`ai_panel.scss`](../../../../../frontend/src/app/main/ui/workspace/ai_panel.scss) — fixed `width: $s-360` at the root; all text via `body-small-typography`-family mixins
- [`hooks/resize.cljs`](../../../../../frontend/src/app/main/ui/hooks/resize.cljs) — `use-resize-hook`: the drag mechanics to model (pointer capture, clamp), but per-file persistence we won't reuse
- [`hooks.cljs`](../../../../../frontend/src/app/main/ui/hooks.cljs) `use-persisted-state` — global localStorage persistence, already imported by the panel
- [`sidebar.cljs`](../../../../../frontend/src/app/main/ui/workspace/sidebar.cljs) / `sidebar.scss` — the `.resize-area` handle pattern (right sidebar: axis `:x`, negated)
- [`workspace.scss`](../../../../../frontend/src/app/main/ui/workspace.scss) — the grid gives `ai-panel` an `auto` column, so the element's own width is authoritative

## Acceptance Criteria

- The panel can be widened/narrowed by dragging its left edge; the cursor and
  hit-area match the sidebars' handles.
- Width is clamped (min = current 360px; max leaves the viewport usable) and the
  chosen width survives reload and applies in every file.
- A−/A+ steps all panel text through discrete sizes with sane bounds; buttons
  disable at the ends; the chosen size survives reload and applies in every file.
- No layout breakage at extremes: composer buttons stay anchored, transcript
  still scrolls, tool payloads still scroll horizontally inside their boxes.
