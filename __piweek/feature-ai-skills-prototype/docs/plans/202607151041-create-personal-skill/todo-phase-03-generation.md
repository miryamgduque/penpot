# Phase 03 — LLM skill-doc generation

**Status:** todo

## Goal

Turn the captured answers (**what / trigger / mode**) into a full **structured skill document** with
a single model completion through the existing proxy — returning `{name, label, category, mode,
trigger, body}` ready for `create-skill` (Phase 01) and the catalog (Phase 02). The user never sees
the raw structure.

## Before Start

- [ ] Read `agent.cljs`: `encode-anthropic` / `encode-openai`, the proxy call in `run-turn`, and
      `provider-pool` / the selected `settings` (provider + model) from the panel
- [ ] Decide the completion path: reuse the streaming `run-turn` and collect the final text, **or** a
      small non-streaming one-shot through the same `rp/cmd!` proxy — pick the simplest that yields
      one complete assistant message
- [ ] Note the built-in categories to classify into: **Audits**, **Build**, **Auto-fix** (closest
      match; the story files the tone checker under Audits)

## Checklist

- [ ] **Generation prompt:** a system/user prompt that, given the answers, emits a skill doc in a
      **fixed shape we can parse** — prefer strict JSON (`{name, label, category, mode, trigger,
      description, body}`) so no brittle frontmatter parsing. `body` is the multi-section playbook
      (method/steps/checkpoints) written for the native agent; `category` constrained to an existing
      one; `mode` echoes the confirmed mode.
- [ ] **`generate-skill` fn** (`data/workspace/skill-gen.cljs` or in `user_skills.cljs`): builds the
      prompt from answers, calls the proxy with the selected provider/model, parses the JSON result,
      normalizes (slugify `name`, clamp category to a known one, default mode). Returns the skill map
      (or an error).
- [ ] **Robustness:** tolerate the model wrapping JSON in prose / code fences (extract the JSON
      object); on parse failure or empty body, surface a retryable error rather than creating a
      broken skill. Bound output tokens.
- [ ] **No provider case:** generation needs a connected model — guard the entry (Phase 04 disables
      the flow / prompts to connect when the pool is empty).
- [ ] Unit test the parse/normalize (fenced JSON, unknown category → closest, missing trigger) with
      a stubbed completion; keep the network call thin and mockable.
- [ ] `make lint` + frontend build, 0 warnings
- [ ] Human approval received
- [ ] Committed (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README; note the `generate-skill` API for Phase 04

## Files

- `frontend/src/app/main/data/workspace/skill_gen.cljs` — **new** (prompt + call + parse/normalize)
- `frontend/test/frontend_tests/data/workspace_skill_gen_test.cljs` — **new** (parse/normalize)

## Notes

- **JSON over frontmatter:** the stored `body` can still be a markdown playbook, but wrapping the
  whole generation in a JSON envelope keeps parsing deterministic and avoids re-implementing the
  aikit frontmatter reader on the client.
- Generation reuses the **user's selected provider/model** (same as chat) — no separate key.
