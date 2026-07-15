# Phase 04 — Per-skill detect/model fields

**Status:** todo

## Goal

Skills declare how they're watched and what model fixes them: `:detect`
(`"deterministic"` | `"model"`) and `:model` (e.g. Haiku) in the **builtin
catalog only** (`agent_skills.cljs` — no DB migration, per the discovery
decision). A Fix-it-now turn driven by a skill with a declared model runs on
that model when the user's provider pool has it, falling back to the panel's
selected model otherwise.

## Before Start

- [ ] Verify plan is still valid; Phase 03 merged
- [ ] Re-read the catalog shape in `agent_skills.cljs` and how `provider-pool` /
      `selected-settings` resolve a model in `ui/workspace/ai_panel.cljs`
- [ ] Map rules → skills: the strip's rules (`layer-naming`,
      `token-only-colors`) need a home skill for model routing (rename-layers
      exists; decide whether token fixes route through a catalog entry or stay
      on the panel model)

## Checklist

- [ ] Tests first: settings resolution (skill declares model present in pool →
      that model; absent → panel settings; no declaration → panel settings)
- [ ] Add `:detect` + `:model` to the relevant catalog entries
      (`penpot-rename-layers`: `:detect "deterministic"` now, `:model` Haiku
      4.5; mark which entries become `"model"`-detect for Phase 05)
- [ ] Resolution helper (data-side, pure): `(fix-settings skill pool
      panel-settings)`
- [ ] Fix-it-now dispatch uses the resolved settings; the transcript's existing
      model indicator (if any) reflects the actual model used — at minimum the
      pending/user chip notes "via <model>" when it differs from the panel model
- [ ] Lint + format; compile 0 warnings; tests green and listed in runner output
- [ ] Preview verify: with a Haiku entry enabled in the pool, Fix it now runs on
      Haiku (check the spend meter delta / network payload `:model`); with Haiku
      disabled, it falls back to the panel model
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README link
- [ ] Note the rule→skill routing table decided here (Phase 05 consumes it)

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — catalog fields
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — `fix-settings` helper
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — dispatch wiring, "via
  model" note
- `frontend/test/frontend_tests/data/agent_watcher_test.cljs` — resolution tests

## Notes

- Follow-up (out of scope): `detect`/`model` as `design_skill` DB columns +
  dashboard fields so user-created skills participate — becomes its own plan
  when custom skills need watching.
- Demo constraint: Anthropic models only — the fallback path matters because a
  demo profile may have exactly one enabled model.
