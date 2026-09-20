# Phase 02 — Enabled state + payload

**Status:** done (closed as out-of-scope — no new code)

## Resolution (2026-07-14)

Closed after re-reading US #7. The story's **"What this story does not cover"** section
explicitly defers on/off **toggling and persistence to story #8**, and scopes #7 to the
built-in catalog *"on first use, before any personal or team activity has happened."* The
detail view is likewise **read-only** ("no toggling, no editing, no triggering").

So a **persisted enabled-state / override layer is not part of this story.** The only
enabled-related requirement for #7 is the **static default** (which skills ship on vs. off on
first run), and that is already implemented:

- `defaultEnabledForMode` / `defaultEnabled` in skills-core ([Phase 01](./done-phase-01-catalog-model.md))
- rendered as muted / "off by default" for the auto-fix skill in both the React panel
  ([Phase 03](./done-phase-03-catalog-list-ui.md)) and the native CLJS panel
  ([Phase 05](./done-phase-05-cljs-catalog-port.md))

## Outcome

- **No new code for #7.** Requirement satisfied by the static default from Phase 01/05.
- **Deferred to #8** (skill on/off toggling): the persisted-override layer + any payload
  plumbing for user/team overrides.

Decision confirmed with the product owner ("Close as out-of-scope").
