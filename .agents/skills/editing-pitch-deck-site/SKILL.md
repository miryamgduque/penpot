---
name: editing-pitch-deck-site
description: Edit the two-page Penpot AI-agent pitch/deck site — design system rules, section/slide structure, and the reusable interaction patterns (popovers, accordion, cross-slide links, per-part colours, add-on slides). Triggers: "edit the pitch/deck site", "edit index.html or deck.html", "add a slide", "add an add-on slide", "change the deck copy", "add a popover/accordion to a slide".
---

<!-- TRANSIENT dev tooling — committed only so collaborators on this branch can
     see it. DELETE THIS SKILL'S OWN DIRECTORY before the PR merges; it is not
     part of the Penpot product. Do NOT delete `.agents/skills/` or the
     `.claude/skills` symlink: those are upstream's and hold real project
     skills. The branch's transient ones are the directories carrying this
     banner. -->

# Editing the AI-agent pitch & deck site

Two-page site in `__piweek/feature-ai-skills-prototype/`:
- **`index.html`** — the pitch (light theme, scroll interaction).
- **`deck.html`** — the plan deck (dark theme, arrow-keys / dots, one `.slide` `.active` at a time).
- **`oss-agent-survey.html`**, **`design-tools-comparison.html`** — reports the deck links to.

All self-contained (inline CSS/JS, inline SVG favicon). Don't blend the two interaction models; keep both themes.

## Design system (shared rules)
- **Five semantic colour roles**, and only these: `--rule` (red = enforcement/guardrail **only**), `--skill` (blue = knowledge/accent), `--affirm` (green = proven/positive), `--caution` (amber = watch-out), `--tradeoff` (neutral grey = cost/con). Costs use tradeoff, never red.
- System **sans** everywhere; **mono** for kickers/labels/tags.
- Components: `.kicker` (brow), `.card` (+ `--rule/--affirm/--caution/--tradeoff`), `.tag` (+ role modifiers).
- Theme-aware via bare `:root` + `@media (prefers-color-scheme: dark)` + `:root[data-theme=…]`. The deck is dark-only.
- **No em dashes** — use colon / semicolon / parentheses. (Range en-dashes like "Sep–Oct" are fine.)
- Full copy and layout conventions (voice, punctuation, structure rules): `reference/copy-conventions.md`.

## Pitch (`index.html`)
- Sections alternate `band` / `band--surface` backgrounds — keep the alternation when adding/reordering.
- `h2` is capped (`max-width: 48rem`) so the **title is narrower than the body**; the hero `h1` is the exception (uncapped).
- Leads are `1.12rem`; the hero lead keeps its `clamp(...)`. `#shift .lead, #teams .lead` are widened to `54rem`.
- Section order: hero → shift → why (For users) → teams (For teams) → compare → built (What we built) → bottom-line. Nav links + `id`s must match.

## Deck (`deck.html`)
- **Acts** live in the `groups` array in the `<script>`: `{ name, to (last slide, 1-based), goal }`. The "you-are-here" line (`#nav-act`) shows `Part N of <groups.length> · goal`, sits **above the dot row** (`#dot-row`), one dot per slide.
- **Per-part colour**: `--part-1..N` in `:root`; `show()` sets `--cur-part` to the active part's colour, which drives the brow, the "Part N" text, and the active dot. Adding an act ⇒ add a `--part-N`.
- Adding/removing/reordering slides ⇒ update the `groups` `to` values.
- Full anatomy (the four places that must agree, checklists for adding/reordering slides): `reference/deck-anatomy.md`.
- Adding a slide that sits *outside* the numbered acts (an appendix / add-on, not "Part N"): `reference/adding-an-addon-slide.md`.
- Building block components (click-to-explain popovers, the roadmap accordion, cross-slide links): `reference/deck-interactions.md`.

## Gotchas
- Slides are fixed-height (`justify-content: center`); adding content can overflow — always verify (see the `verifying-pitch-deck-changes` skill), especially the longest open popover / accordion body and the roadmap slide.
- After adding an `.explain`/`.slink`/`.acc`, the global handlers pick it up on load (no per-element wiring) — but only if it's present in the HTML at load.
- Nav-hijack ignores `a, .dots, .explain, .acc`; keep new interactive things inside those or they'll trigger slide navigation.
