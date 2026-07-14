# Phase 06 — Tool-boundary enforcement (`token-only-colors`)

**Status:** todo

## Goal

Enforce the `token-only-colors` rule **at the native tool boundary**: when the rule is active for the file, the color-setting tools (`modify_shape` fill/stroke, `create_shape` initial fill) reject a raw (non-token) color and throw a rule-tagged error the agent recovers from by using `apply_tokens`. No change-pipeline plumbing — the native tools are the agent's only write path.

## Before Start

- [ ] Re-read the guard logic to port (`skills-core/src/guard.ts`): `collectAllowedColors` (`:55-79`, allowed = color-token values in active sets + all library colors, normalized to lowercase 6-hex), `findDisallowedColors` (`:86-99`), `SkillViolationError("token-only-colors", …)` (`:101-135`)
- [ ] Confirm there is **no** value-level veto in the internal pipeline (only `:can-edit` at `changes.cljs:250`) — so tool-boundary is the correct layer
- [ ] Decide "is the rule active?": read the effective rules for the file. Until Phase 07 lands the backend resolver, gate on a local check of file-scope enforced rules; after P07, use the resolved manifest's `enforced` set.
- [ ] Confirm how a token-bound fill is represented on a shape (`:fill-color-ref-id`/`:ref-file` present) vs a raw fill (`:fill-color` only)

## Checklist

- [ ] CLJS port of `collect-allowed-colors` (active color-token resolved values + library colors, normalized) and `find-disallowed-colors` in a small `agent_guard.cljs` (or reuse in `agent_tools.cljs`).
- [ ] In the color-setting tools: when the rule is active, inspect the incoming fill/stroke; if it carries a raw color not in the allowed set (and no token ref), **throw** `{:rule "token-only-colors" :message "…names the offending color + up to ~20 allowed tokens + points at apply_tokens"}`.
- [ ] `run-turn` already surfaces `{:rule …}` errors as `rejected` tool events with the rule shown — verify the chip renders the rule + hint (Phase 02 wiring).
- [ ] `apply_tokens` / `create_color_token` are **never** gated (they are the safe path).
- [ ] Gradients/images skipped (out of scope, matches the source guard).
- [ ] `make lint/frontend`, `make typecheck/frontend`
- [ ] Preview: with `token-only-colors` active, "fill the box with #ff0000" → tool **rejected** citing the rule; the agent then creates/applies a token and succeeds. With the rule inactive, raw fills go through.
- [ ] Human approval; commit `feat(workspace): token-only-colors enforcement at agent tool boundary`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; note how "rule active" is determined (interim local vs Phase 07 manifest)

## Files

- `frontend/src/app/main/data/workspace/agent_guard.cljs` *(new)* — allowed-colors + disallowed detection
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — gate fill/stroke inputs on the guard

## Notes

- The error text is load-bearing: it teaches the model to self-correct. Mirror the source's message (offending color + allowed tokens + "use apply_tokens").
- Enforcement stays **client-side** by design (the backend can't proxy a live-object write veto). Phase 07's backend resolver only tells the frontend *which* rules are `enforced`; activation + checking remain here.
