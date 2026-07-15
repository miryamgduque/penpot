# Phase 03 — LLM skill-doc generation

**Status:** done

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

- [x] **Generation prompt:** system `instructions` (JSON-only reply, native-tools framing, no
      MCP/plugin-API, category from the existing set) + `answers->user-message`. The model supplies
      `name/label/category/body`; the user's `mode`/`trigger`/`what` stay authoritative in normalize.
- [x] **`generate-skill` fn** (`data/workspace/skill_gen.cljs`): bare tool-free `generation-body`
      (mirrors `agent/build-round-body` minus tools/stream), calls **`:ai-agent-round`** (the
      buffered proxy twin), `extract-text` per provider, `parse-generation` → skill map; throws a
      retryable ex-info on unusable output.
- [x] **Robustness:** `extract-json` strips ``` / ```json fences + surrounding prose (first `{` …
      last `}`); parse failure or empty body → nil → retryable error. `max_tokens` 4000.
- [x] **No provider case:** generation needs a connected model — the entry guard is Phase 04's
      (it disables the flow / prompts to connect when the pool is empty). Noted.
- [x] Unit tests (`workspace-skill-gen-test`, 5 deftests / 21 assertions): fenced/prose/plain/none
      JSON extraction; category clamp incl. unknown → Audits; full parse (user fields win, category
      clamped, name slugified); nil on missing body / no JSON; name fallbacks (label → `what`).
- [x] `clj-kondo` 0/0; shadow test build clean; tests green
- [x] Human approval received
- [x] Committed (`:sparkles:`)

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
