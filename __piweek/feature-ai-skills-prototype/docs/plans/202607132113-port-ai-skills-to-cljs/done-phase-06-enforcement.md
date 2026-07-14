# Phase 06 — Tool-boundary enforcement (`token-only-colors`)

**Status:** done

## Goal

Enforce the `token-only-colors` rule **at the native tool boundary**: when the rule is active for the file, the color-setting tools (`modify_shape` fill/stroke, `create_shape` initial fill) reject a raw (non-token) color and throw a rule-tagged error the agent recovers from by using `apply_tokens`. No change-pipeline plumbing — the native tools are the agent's only write path.

## Before Start

- [ ] Re-read the guard logic to port (`skills-core/src/guard.ts`): `collectAllowedColors` (`:55-79`, allowed = color-token values in active sets + all library colors, normalized to lowercase 6-hex), `findDisallowedColors` (`:86-99`), `SkillViolationError("token-only-colors", …)` (`:101-135`)
- [ ] Confirm there is **no** value-level veto in the internal pipeline (only `:can-edit` at `changes.cljs:250`) — so tool-boundary is the correct layer
- [ ] Decide "is the rule active?": read the effective rules for the file. Until Phase 07 lands the backend resolver, gate on a local check of file-scope enforced rules; after P07, use the resolved manifest's `enforced` set.
- [ ] Confirm how a token-bound fill is represented on a shape (`:fill-color-ref-id`/`:ref-file` present) vs a raw fill (`:fill-color` only)

## Checklist

- [x] CLJS port of `collect-allowed-colors` (active color-token `:resolved-value`/`:value` + the file's library colors, normalized via `normalize-hex`) + `color-violation` — kept in `agent_tools.cljs` (small enough, avoids a new ns). ✓
- [x] `create_shape` (initial `fill`) and `modify_shape` (`fill`/`stroke`) check `color-violation` first; a raw color not in the allowed set (with the rule enforced) → `(rx/throw (ex-info … {:rule "token-only-colors"}))` naming the offending color + up to 20 allowed values + pointing at create_color_token/apply_tokens. ✓
- [x] `run-turn`'s `rx/catch` surfaces `{:rule …}` errors as **rejected** chips (Phase 02 wiring) — verified: the chip shows `✕ create_shape token-only-colors`. ✓
- [x] `apply_tokens` / `create_color_token` are never gated. ✓
- [x] Gradients/images skipped (only string hex fills checked). ✓
- [x] **"Rule active" source:** `[:ai-panel <file-id> :enforced-rules]` in app state, set by the new `dwaip/set-enforced-rules` event — **wired to the backend skills manifest in Phase 07**; off by default (nothing populates it yet). Enabled via console for this test. ✓
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, `shadow-cljs compile main` → **0 warnings**. ✓
- [x] Preview (live devenv, Haiku): with the rule enforced — console: raw `#ff0000` create **not added to canvas** (rejected), raw `#6366f1` (a token value) **succeeds**, `apply_tokens` still works. LLM: "create a red rectangle with raw fill #ff0000" → **✕ rejected chip citing token-only-colors** → agent self-corrected (created `color.red.bright` token, then `create_shape` + `apply_tokens`) → red rectangle, token-bound. ✓
- [ ] Human approval; commit `:sparkles: token-only-colors enforcement at the agent tool boundary`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; note how "rule active" is determined (interim local vs Phase 07 manifest)

## Files

- `frontend/src/app/main/data/workspace/agent_guard.cljs` *(new)* — allowed-colors + disallowed detection
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — gate fill/stroke inputs on the guard

## Notes

- The error text is load-bearing: it teaches the model to self-correct. Mirror the source's message (offending color + allowed tokens + "use apply_tokens").
- Enforcement stays **client-side** by design (the backend can't proxy a live-object write veto). Phase 07's backend resolver only tells the frontend *which* rules are `enforced`; activation + checking remain here.
