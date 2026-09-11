# Phase 01 — Native agent loop (text-only round)

**Status:** done

## Goal

Port the provider-agnostic agent core from `ai-skills/src/ui/agent.ts` to a native CLJS namespace and wire it to the US #2 Chat tab so a real conversational turn works end-to-end — user message → `:ai-agent-round` → assistant reply — with **no tools yet** (tool list empty). This proves the whole native round-trip before any design tools exist.

## Before Start

- [ ] US #2 Chat tab (persisted transcript + composer) is in place ([US #2 Phase 05](../202607132039-all-in-penpot-panel-toggle/todo-phase-05-chat-persistence-context.md))
- [ ] Re-read `agent.ts` canonical model + codecs (`:35-38` types, `:313` `encodeAnthropic`, `:341` `encodeOpenAI`, `:377` `parseRound`, `:399/:422` decoders, `:449` `buildRoundBody`, `:504` `runTurn`)
- [ ] Confirm `:ai-agent-round` contract (`backend/src/app/rpc/commands/ai_providers.clj:301-330`): params `{:provider :payload}` (payload = provider-shaped JSON **string**), returns `{:provider :status :body}`
- [ ] Confirm `refs/ai-providers` (`refs.cljs:665`) exposes connected providers + enabled models for the model pool

## Checklist

- [x] New ns `app.main.data.workspace.agent` — canonical message model (`:user`/`:assistant`/`:tool-results` maps), `encode-anthropic`/`encode-openai`, `decode-anthropic`/`decode-openai`, `parse-round`, `build-round-body` (Anthropic `max_tokens 32000` + ephemeral cache on system; OpenAI-compat `max_completion_tokens`/`max_tokens 16000`), `build-system-prompt`, and `run-round`. ✓
- [~] **Phase 01 is single-round text-only** (`run-round`), not the full `run-turn` loop — the loop + `prune-stale-tool-results` + `MAX_ROUNDS` arrive with tools in Phase 02 (the `tools` var + encoders already thread tool blocks). Intentional split.
- [x] `run-round` calls `(rp/cmd! :ai-agent-round {:provider … :payload (build-round-body …)})` (rx observable), decodes, returns the assistant text (silent-empty-turn guard). ✓
- [x] `build-system-prompt` scaffold: preamble + operating-modes + current-context JSON, with a **clearly-marked seam** for the Phase 07 skills/rules sections. ✓
- [x] Context = page name + selection (name/type) from the Chat tab's derefs, passed into `send-message`. ✓
- [x] Wired the Chat tab composer → `dwaip/send-message` (appends user, runs the round, appends assistant, `busy?` gate + "Thinking…" indicator); added a minimal **model picker** (`provider / model`) over the connected-providers pool; fetches providers on mount. ✓
- [x] Empty-pool / not-connected state: "Connect an AI provider in Settings → Integrations" link. ✓
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, `stylelint` clean, `shadow-cljs compile main` → **0 warnings**. ✓
- [x] Preview (live devenv, **user connected an Anthropic key + enabled Claude Haiku 4.5**):
  - sent "Hello! …what can you help me with?" → **real Haiku reply** rendered as an assistant bubble ✓
  - **context reaches the model**: with nothing selected → *"You have nothing selected on Page 1."*; after selecting the rectangle → *"You have a Rectangle selected on Page 1."* ✓
  - `busy?` / "Thinking…" indicator, empty-pool→settings link, and the not-connected error path (**"⚠️ provider not connected"**) all verified ✓
  - Mid-thread **provider** switch not exercised (only one model enabled) — the canonical history re-encodes per provider each round, so this is correct by construction; Phase 09's picker revisits it.
- [ ] Human approval; commit `feat(workspace): native ai agent turn loop (text-only)`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; note the ns layout chosen (single ns vs `agent/wire.cljs` split)

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` *(new)* — canonical model, codecs, `run-turn`, system-prompt scaffold
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — Chat tab submit wired to `run-turn`

## Notes

- Keep the canonical history in one form and re-encode per provider each round (this is what lets the user switch models/providers mid-conversation — port that property faithfully).
- No streaming: replies are buffered per round (same as the source).
