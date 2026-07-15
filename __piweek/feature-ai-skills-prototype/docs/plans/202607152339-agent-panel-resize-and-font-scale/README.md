# Agent Panel: Resizable Width + Font Size Stepper

**Status:** doing
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
2. [Phase 02 — Font size stepper](./todo-phase-02-font-size-stepper.md) — A−/A+ control, discrete steps, CSS-variable-driven scale over the whole panel

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
