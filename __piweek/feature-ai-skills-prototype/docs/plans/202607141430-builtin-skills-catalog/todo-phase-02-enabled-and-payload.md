# Phase 02 — Enabled state + payload

**Status:** todo

## Goal

Resolve each catalog entry's **effective enabled** state (`defaultEnabled` merged with any
persisted override) and surface the grouped catalog + enabled state to the panel through the
existing plugin→iframe payload — without adding any toggle UI (that's #8).

## Before Start

- [ ] Verify Phase 01 landed and `builtinCatalog()` is exported
- [ ] Re-read `ai-skills/src/plugin.ts` `skillsPayload()` / `loadDisabled()` / `set-skill-enabled`
- [ ] Re-read `ai-skills/src/ui/bridge.ts` `SkillsPayload` and the `init`/`skills-change` events
- [ ] Confirm where platform-scope disabled state persists (`penpot.localStorage` `skills.disabled.platform`)

## Checklist

- [ ] Write tests (skills-core) for `resolveCatalog(persistedDisabled)` — default off for
      `penpot-rename-layers`, on for the rest, and a persisted override flips a single entry
- [ ] `catalog.ts`: add `resolveCatalog(disabledNames: string[]): CatalogEntry[]` (or extend
      entries with `enabled`) — `enabled = defaultEnabled && !disabled` seeded from persisted names
- [ ] `plugin.ts`: build a `catalog` block in `skillsPayload()` from `builtinCatalog()` +
      current disabled set; seed `penpot-rename-layers` into the default-disabled baseline so
      first run resolves it off without a stored toggle
- [ ] `bridge.ts`: extend `SkillsPayload` with `catalog: CatalogEntry[]` (typed from skills-core)
- [ ] Verify the existing scope/enforcement path is untouched (payload is additive)
- [ ] Lint + typecheck pass (`make lint`, panel build)
- [ ] Preview review: log the payload in the panel, confirm catalog shape + rename-layers off
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Note how default-off is seeded (baseline disabled vs. `defaultEnabled` at resolve time)
- [ ] Confirm the UI phases can read `payload.catalog` directly

## Files

- `skills-core/src/catalog.ts` — `resolveCatalog` / `enabled` on entries (+ tests)
- `ai-skills/src/plugin.ts` — add `catalog` to `skillsPayload()`, default-off seed for rename-layers
- `ai-skills/src/ui/bridge.ts` — `SkillsPayload.catalog` type

## Notes

- Decision: include the enabled data model now so #8 only adds the control. Keep the
  persistence mechanism aligned with the existing `disabled` machinery (`set-skill-enabled`,
  per-scope stores) so #8 reuses it rather than inventing a parallel store.
- Default-off for the one `autofix` skill can be expressed purely (`defaultEnabled=false` in
  Phase 01) — prefer that over writing a disabled entry to storage on first run, to avoid a
  persisted side effect before the user has toggled anything. Decide and record which.
- No `set-*` op or checkbox in this phase — read-only exposure only.
