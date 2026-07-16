# Phase 01 — Catalog model

**Status:** done (2026-07-16) — catalog is 2 categories (Audits/Build), `:mode`
→ `:reactive`, `reactive-label`, `watched-rules` + `model-detect-skills` scoped
to Observer, manifest + system-prompt index updated. Verified live: catalog
renders Audits/Build only with the correct per-skill behavior.

## Goal

Retire the per-skill `:mode` axis and the **Auto-fix** category from the built-in
catalog, replacing them with **reactive behavior** (`:reactive` ∈ `"on-call"` |
`"observer"`), and make the watcher observe exactly the Observer skills.

## Before Start

- [ ] Re-read the catalog + helpers in
      [agent_skills.cljs](../../../../../../frontend/src/app/main/data/workspace/agent_skills.cljs):
      `catalog` (:40), `mode-label` (:105), `user-skill->entry` (:228),
      `watched-rules` (:320), `catalog-manifest` (:329), `system-prompt-section` (:342)
- [ ] Confirm the watcher's two consumers key off `:rule`/`:detect`, not `:mode`:
      `model-detect-skills` (data/workspace/ai_panel.cljs:341), rule seeding (:291)

## Checklist

- [ ] **Categories:** delete the `"Auto-fix"` group; move `penpot-rename-layers`
      into the `"Build"` group (keep its `:rule`/`:detect`/`:model`).
- [ ] **Field swap:** replace each skill's `:mode "…"` with `:reactive "on-call"`
      except `penpot-audit-tokens` and `penpot-rename-layers` → `:reactive
      "observer"`. `penpot-migrate` → `"on-call"` (not named in US #14; documented
      default).
- [ ] Replace `mode-label` with `reactive-label`
      `{"on-call" "On-call" "observer" "Observer"}`.
- [ ] `watched-rules`: filter enabled skills to `(= "observer" (:reactive %))`
      before `(keep :rule)` — semantically "Observer skills drive the watch"
      (behavior identical today; future-proofs user Observer skills).
- [ ] `model-detect-skills` (data/workspace/ai_panel.cljs): add the same Observer
      guard alongside `:detect "model"`.
- [ ] `user-skill->entry`: map `:reactive (:reactive us)` (was `:mode`).
- [ ] `catalog-manifest` (both arities) + `system-prompt-section`: swap `:mode`
      for `:reactive` and `mode-label` for `reactive-label` (the agent index line
      reads `(Build · Observer)`).
- [ ] Grep the frontend for any remaining `:mode`/`mode-label`/`"autofix"`/
      `"Auto-fix"` reference tied to skills (not governance prose) and update.
- [ ] Compile 0 warnings on our namespaces; `clj-kondo` clean.

## Notes

- Do **not** touch `inner-knowledge`'s governance section (Suggest /
  Apply-with-review / Auto-fix) — that is the per-change policy, still valid.
- `rule-fix-model` (:95) keys off `:rule`/`:model` — unaffected.
