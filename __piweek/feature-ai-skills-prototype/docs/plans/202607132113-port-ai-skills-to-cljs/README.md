<!-- TRANSIENT — part of the __piweek/ team scratch. Delete before the PR is finalized. -->

# Port the `ai-skills` chat agent to native ClojureScript

**Status:** doing
**Created:** 2026-07-13
**Apps:** `frontend`, `backend`, `common`
**Depends on:** [All-In Penpot — Open & Close the Panel (US #2)](../202607132039-all-in-penpot-panel-toggle/) — this plan fills the native **Chat tab** that US #2 builds. Execute US #2 first (panel shell + tabs + persistence), then this.
**Reference base:** the React `ai-skills/` app stays on disk untouched as the porting spec; it is retired only after this port is verified.

## Context

Today the AI chat is a React app running in a plugin iframe (`ai-skills/`): a UI (`Chat.tsx`, `agent.ts`) talks over `postMessage` (`bridge.ts`) to plugin code (`plugin.ts`) that drives the **public** `penpot.*` plugin API. US #2 replaces the panel shell with native CLJS. This plan replaces the **agent itself** with native CLJS, so the Chat tab runs a real agent with no iframe, no bridge, and no plugin runtime.

### Decisions locked in with the user (2026-07-13)

1. **Fully native — drop `execute_code`.** The agent's arbitrary-JS tool is removed. Every design capability it covered (create/modify shapes, boards, text, components, apply tokens) becomes a **fixed set of native CLJS tools** calling Penpot's internal mutation events directly. This is the largest piece of the port and the cleanest end-state.
2. **Skills cascade resolved in the backend.** A new `:get-effective-skills` RPC ports the `skills-core` resolver to Clojure and serves a resolved manifest; the native panel consumes it instead of resolving client-side.
3. **Chat agent core first.** Port the agent loop, wire encoders, native tools, and Chat wiring. The Skills / Audit / Tokens **manager** tabs are deferred to a later plan (they partly overlap the existing native dashboard).

### Why this shape works (grounded in the codebase)

- The provider round-trip is **already native**: `:ai-agent-round` ([ai_providers.clj:308](../../../../../backend/src/app/rpc/commands/ai_providers.clj)) proxies one buffered provider round server-side (keys never reach the browser). The CLJS loop calls it directly via `rp/cmd!` — the whole `bridge.aiRound` + `relay-ai-round` postMessage hop disappears.
- Every mutation has a **proven internal entry point** already exercised by `frontend/src/app/plugins/*.cljs`, all flowing through the `pcb` changes-builder → `app.main.data.changes/commit-changes` pipeline (automatic undo): `cts/setup-shape`+`pcb/add-object` (create), `dwsh/update-shapes` + `data/workspace/colors` (modify), `data/workspace/transforms` (geometry), `dwsh/relocate-shapes` (nest), `dwta/toggle-token` (apply token), `dwtl/create-token` (create token), `dwl/add-component` (component).
- **Enforcement gets simpler, not harder.** There is no value-level write veto in the change pipeline today (only a coarse `:can-edit` gate at `changes.cljs:250`). But because the native tools are the agent's *only* write path, `token-only-colors` is enforced **at the tool boundary** — the color-setting tools reject a raw hex and accept only a token reference. No core-pipeline plumbing required.
- The `skills-core` cascade (`parse` / `resolveCascade` / `skillManifest`) is pure, dependency-free logic that ports to Clojure almost line-for-line; `:get-design-skills` already returns the app+team rows + overrides the resolver needs. The one gap — **file-scope skills live only in file `pluginData`** — is handled by the client passing file-scope sources to the RPC (avoids a stale server-side file-data decode).

### Scope boundary

**In:** the agent loop (canonical model + Anthropic/OpenAI wire codecs + turn loop), the native tool set (read + structural + text/component + token tools), tool-boundary enforcement of `token-only-colors`, the backend skills-resolution RPC + `get_design_skills`, the native `audit_file`, and chat polish (model picker, spend meter, history trim/persist) beyond the US #2 shell.

**Out (own later plans):** the Skills / Audit / Tokens **manager** UI tabs; the change-watcher / live violations ledger (`skill-triggered` toasts); gradient/image color enforcement; the MCP external-agent path; deleting the `ai-skills` React app (a final coordinated cleanup once this is verified).

## Phases

1. [Phase 01 — Native agent loop (text-only round)](./done-phase-01-agent-loop.md) — canonical model + wire codecs + round runner, calling `:ai-agent-round`; Chat tab actually talks. ✅ **done**
2. [Phase 02 — Tool-call infrastructure + `read_design`](./todo-phase-02-tool-infra-read-design.md) — tool declarations, the execute-tool dispatch, and the read-only orientation tool.
3. [Phase 03 — Structural tools: create / modify / nest](./todo-phase-03-structural-tools.md) — `create_shape`, `modify_shape`, `nest_shape` — the core `execute_code` replacement.
4. [Phase 04 — Text & component tools](./todo-phase-04-text-component-tools.md) — `create_text` (WASM resize), `create_component` (id-ref atom).
5. [Phase 05 — Token tools: create & apply](./todo-phase-05-token-tools.md) — `create_color_token`, `apply_tokens` (async StyleDictionary settle).
6. [Phase 06 — Tool-boundary enforcement (`token-only-colors`)](./todo-phase-06-enforcement.md) — port allowed-color logic; color tools reject raw hex, agent self-corrects.
7. [Phase 07 — Backend skills resolution + `get_design_skills` + system prompt](./todo-phase-07-skills-resolution.md) — port the cascade to Clojure, `:get-effective-skills` RPC, enrich the system prompt.
8. [Phase 08 — `audit_file` tool](./todo-phase-08-audit-file.md) — native page scan against active rules, returns violations.
9. [Phase 09 — Chat polish: model picker, spend meter, history](./todo-phase-09-chat-polish.md) — parity with `Chat.tsx` beyond the US #2 shell.

## Acceptance Criteria

- The Chat tab runs a full agent turn natively: user message → `:ai-agent-round` → assistant reply, with **no plugin iframe or postMessage**.
- The agent can orient (`read_design`) and **build/modify** a design through native tools — create shapes/boards/text, move/resize, restyle, nest, componentize — with all changes undoable as normal Penpot history.
- `execute_code` no longer exists; the agent accomplishes generative work through the fixed native tool set.
- Setting a raw (non-token) fill/stroke through a tool is **rejected citing `token-only-colors`**, and the agent recovers by applying a token; token-valued writes succeed.
- The agent can create and apply color tokens.
- The effective skills manifest (app→team→file cascade, mandatory no-loosening, disabled filtered) reaches the agent via the backend RPC and shapes its system prompt.
- `audit_file` returns the open violations for the current page.
- Model switching (incl. across providers) carries the full history; the spend meter shows usage/cost for Claude models.
- `make lint/{frontend,backend}` and `make typecheck/frontend` pass; the `ai-skills` React app is left intact as reference.

## Open questions / risks

- **Async settle** (token apply, WASM geometry/text): tools must report "applied — verify with `read_design`/`audit_file`" rather than returning a synchronous confirmation, mirroring the plugin's async contract. Phase 05 pins the pattern.
- **File-scope skills transport**: client passes file-scope sources to `:get-effective-skills` (Phase 07). If a future story needs server-authoritative resolution, revisit decoding `file.data.plugin-data[:shared/penpot-skills]`.
- **Component id return** comes back via a side-channel atom (`dwl/add-component` id-ref) — Phase 04 reads it post-emit like the plugin does.
