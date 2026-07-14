# Phase 01 — Catalog model in skills-core

**Status:** todo

## Goal

Give `skills-core` a first-class notion of the built-in **catalog**: parse the `mode`
frontmatter, derive a display **category** and **example trigger phrase**, exclude the
router + shared/core docs, and compute the `defaultEnabled` state — all pure and unit-tested,
so both the plugin and any future consumer share one derivation.

## Before Start

- [x] Verify plan is still valid (no conflicting work on `skills-core` since creation)
- [x] Confirm `aikit.gen.ts` still carries `mode:` on the bundled skills (regen may have changed it)
- [x] Re-read `parse.ts`, `types.ts`, `index.ts` to confirm signatures
- [x] Confirm the test runner (`skills-core` already has `*.test.ts` — check `package.json` script)

## Checklist

- [x] Write `catalog.test.ts` first: membership (3 audits / 6 build / 1 auto-fix), router + 4
      shared docs excluded, category↔mode mapping, `defaultEnabled` (autofix ⇒ false else true),
      example-phrase extraction from a `Triggers:` list (and graceful fallback when absent)
- [x] `types.ts`: add `Mode` (`"suggest" | "review" | "autofix"`) and `Category`
      (`"Audits" | "Build" | "Auto-fix"`); add optional `mode?: Mode` to `Skill`
- [x] `parse.ts`: parse `mode` from frontmatter into `Skill.mode`; keep `serializeSkill` round-trip
- [x] `catalog.ts` (new): `CatalogEntry` type + `builtinCatalog()` that maps bundled skills →
      grouped entries `{ name, category, mode, description, example, defaultEnabled }`,
      applying the exclusion rule (has catalog `mode` and name !== `penpot-router`)
- [x] Category mapping helper `categoryForMode(mode)` and example extractor `exampleTriggerOf(description)`
- [x] `index.ts`: export the new catalog surface
- [x] Typecheck pass — `skills-core` (TS 5.7.2) + `ai-skills` consumer both clean; tests 31/31 (`tsx --test`). `make lint` not run (skills-core has no local eslint/prettier; style matched by hand)
- [x] Human approval received (2026-07-14)
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename `todo-` → `done-`; update README links
- [x] Note any derivation edge cases discovered (e.g. skills with no `Triggers:` list)
- [x] Confirm Phase 02 assumptions still hold

## Files

- `skills-core/src/types.ts` — add `Mode`, `Category`, `Skill.mode`
- `skills-core/src/parse.ts` — parse `mode`
- `skills-core/src/catalog.ts` — **new** derivation (membership, category, example, default)
- `skills-core/src/catalog.test.ts` — **new** tests (write first)
- `skills-core/src/index.ts` — export catalog

## Notes

- **Done 2026-07-14.** All 10 catalog skills carry a `Triggers:` list, so example extraction
  never hit the first-sentence fallback in practice — the fallback is kept as defensive cover.
- Exclusion landed as a name set (`penpot-router`) plus the no-`mode` filter; the 4 shared docs
  fall out naturally. Verified: catalog is exactly 10 entries.
- Phase 02 can consume `builtinCatalog()` + `defaultEnabledForMode` as-is; no signature changes needed.

- Exclusion: shared docs carry no `mode` → naturally excluded; `penpot-router` has
  `mode: suggest` → must be excluded **by name**. Encode both in one predicate.
- Example phrase: descriptions end with `Triggers: 'a', 'b', …`. Extract the first quoted
  phrase and present it as a sentence. The story mockup shows a polished phrase
  ("Check this screen for accessibility problems.") — deriving the first trigger is close
  enough; note in the README if any card reads awkwardly (candidate for the `example`
  frontmatter field later, deferred by the discovery decision).
- Keep `catalog.ts` pure (no plugin/DOM deps) so it runs in the sandbox, iframe, and MCP.
