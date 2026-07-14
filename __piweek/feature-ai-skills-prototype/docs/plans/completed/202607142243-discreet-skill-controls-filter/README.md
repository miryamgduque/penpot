# Discreet skill controls and list filtering (US #30)

**Status:** done
**Created:** 2026-07-14
**Apps:** `frontend`
**Source:** [Taiga US #30 — Discreet skill controls and list filtering](https://tree.taiga.io/project/miryam-all-in-penpot/us/30)
**Dependencies:** Reshapes the Skills-tab UI shipped in **US #8** (per-row `switch*` toggle +
per-user enable/disable state). Consolidates the *entry points* of **US #10** (fork) and **US #12**
(promote to team) into one menu — their mechanics stay out of scope. Auto-fix removal is handled by
a **separate story** (discovery decision), so this story builds against the current 3-category
catalog.

## Context

> As a Penpot user, I want a quieter way to enable and disable skills and to filter what I see, so
> that the Skills tab doesn't overwhelm me with a wall of controls and I can focus on the skills I
> actually use.

The US #8 design puts a high-contrast `switch*` on every row. With ten-plus skills that's ten
identical controls competing for attention, and it leaves no room for the other per-skill actions
the product needs (fork, promote). This story replaces the per-row switch with a **discreet
overflow menu**, makes disabled state **legible from the list**, and adds a **filter**.

**Interaction (verbatim intent):**

- **Overflow menu replaces the switch.** Each row has a single **⋯ menu on the right, always
  visible but visually muted** so it recedes. It does **not** depend on hover (works on touch, stays
  discoverable). The menu holds the per-skill actions: **Enable / Disable** (whichever applies),
  **Fork**, and **Promote to team** — consolidating US #10 + US #12 entry points into one control.
  Enable/Disable is **instant, no confirmation** (consistent with US #8).
- **Enabled vs disabled state, legible from the list.** Enabled rows render normally. **Disabled
  rows are dimmed** (reduced opacity, muted text) and carry a small **"Off" pill** — state is
  readable without opening the detail view.
- **Filter.** A control at the top of the Skills tab with two options: **All** (enabled + disabled)
  and **Enabled** (hides disabled skills).
- **Remove the per-row mode badge.** The suggest/review badge is redundant with the category header
  it sits under; rows show **name + description only**, freeing space for the ⋯ menu.

**Explicitly out of scope:** the skill **detail view keeps its own toggle** (US #8) as a secondary
path; the **mechanics** of forking (US #10) or promoting (US #12) — only their entry point moves
here.

### Decisions (from discovery)

1. **Auto-fix:** *a separate story* removes the Auto-fix category / `penpot-rename-layers`. This
   plan builds against the **current 3-category catalog** and does not touch it. The mode badge is
   still removed from rows per the story (the category header carries that meaning).
2. **Fork / Promote to team:** rendered in the menu as **visible-but-disabled** entry points (no
   action yet); US #10 / US #12 wire them.
3. **Filter persistence — deliberately narrower than the story text.** The story says "All by
   default, persists across sessions." The product owner's decision **overrides** that:
   - **Default on every new session = `Enabled`** (show only enabled skills).
   - The selection **persists only within the session** (survives tab switches + panel close/reopen)
     and **resets to `Enabled` on a new session / hard refresh**.
   - ⇒ **in-memory app-state**, *not* `localStorage`/`use-persisted-state` (which would persist
     across sessions). This mirrors US #2's "panel always reopens on Chat" in-memory philosophy.

### Current state (grounded in code)

- `frontend/src/app/main/ui/workspace/ai_panel.cljs`:
  - `skills-tab*` renders `ask/catalog` grouped by category; each row is a `div[role=button]`
    (opens the detail view) with a `switch*` in `.catalog-card-head` and a `mode-badge*` in
    `.catalog-desc`. **This is what the story reshapes.**
  - `skill-detail*` also has a `switch*` (line ~246) and a `mode-badge*` (line ~250) — **detail is
    out of scope**, its toggle stays.
  - `mode-badge*` is shared by both; **remove it from the list only**, keep it in detail.
- State layer (US #8, reuse as-is): `refs/resolved-skills-enabled` (`{skill-name → bool}`),
  `skst/set-skill-enabled` (optimistic, instant). Enable/Disable in the new menu just calls these.
- **UI primitive for the menu:** `app.main.ui.components.dropdown` (`dropdown*` / `dropdown-content*`
  — click-outside + esc handling). The US #8 model-picker in this same file is a hand-rolled
  precedent if the shared component doesn't fit.
- **No backend work** — enable/disable already persists (US #8); the filter is in-memory only.

## Phases

1. [Phase 01 — Row overflow menu + legible state](./done-phase-01-row-overflow-menu.md) — replace the
   per-row `switch*` with a muted ⋯ menu (Enable/Disable wired; Fork/Promote disabled), add the
   dimmed + "Off" pill disabled state, and remove the per-row mode badge. ✅ **done** (verified live)
2. [Phase 02 — Filter control](./done-phase-02-filter-control.md) — an All / Enabled filter at the
   top of the Skills tab, in-memory session state defaulting to **Enabled**, filtering the list.
   ✅ **done** (verified live)

## Acceptance Criteria

- Every skill row has a single, always-visible but **muted ⋯ menu** (no hover dependency) with
  **Enable/Disable** (instant, no confirmation) plus **Fork** and **Promote to team** as
  visible-but-disabled entry points. The per-row **switch is gone**.
- **Disabled rows are dimmed and show an "Off" pill**; enabled/disabled state is legible from the
  list without opening detail.
- The Skills tab has an **All / Enabled** filter; **Enabled** hides disabled skills. It **defaults
  to Enabled on a new session**, persists within the session, and **resets to Enabled on reload**.
- The **per-row mode badge is removed**; rows show name + description only. Detail view is unchanged
  (keeps its toggle + badge).
- `make lint` / typecheck pass; frontend builds; disabling a skill still drops it from the router
  (US #8 behavior intact).
