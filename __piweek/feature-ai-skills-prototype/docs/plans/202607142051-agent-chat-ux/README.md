<!-- TRANSIENT — part of the __piweek/ team scratch. Delete before the PR is finalized. -->

# Agent chat UX — streaming, stop, scroll, collapsible tools

**Status:** doing
**Created:** 2026-07-14
**Apps:** `frontend`, `backend`
**User story:** [US #27 — Agent chat UI improvements](https://tree.taiga.io/project/miryam-all-in-penpot/us/27)
**Depends on:** [Port the `ai-skills` chat agent to native CLJS](../202607132113-port-ai-skills-to-cljs/) (done) — this plan polishes the panel that plan built.
**Related:** [Agent chat metaprompt](../202607142003-agent-metaprompt/) (US #26) — touches `build-system-prompt` in the same `agent.cljs`; this plan does not.
**Branch:** `feature/agent-chat-ux` (off `feature/ai-skills-prototype`)

## Context

The chat works, but its UX was built for short exchanges. A turn runs up to 32 tool rounds
([`agent.cljs:259`](../../../../../frontend/src/app/main/data/workspace/agent.cljs)) with **no
streaming**, **no way to stop**, **no scroll-into-view**, and a wall of flat tool chips. The
panel shows a static `"Thinking…"` for the whole turn.

### The story cites the retired React path — read this before starting

US #27 is written against `ai-skills/` (`Chat.tsx`, `agent.ts`, `onAssistantDone`). That app is
now **only the porting spec**; the live chat is native CLJS. The story text stays as the
product-level intent (owner's call); **this plan is the technical source of truth.** Three of
the story's specifics are wrong against the real code, and one is a worse bug than described:

| Story says | Reality (CLJS) |
|---|---|
| "autoscroll yanks you back down" | There is **no autoscroll at all** — new messages never scroll into view (`ui/workspace/ai_panel.cljs:151`). |
| "Send button becomes Stop" | There **is no send button** — bare textarea, Enter-to-send, `:disabled busy?` (`:213-218`). You can't even type while a turn runs. |
| "`ToolEvent` already carries `input`" | CLJS tool events carry **neither input nor result** (`agent.cljs:297-303`, `data/workspace/ai_panel.cljs:66-79`). |
| "aborting must synthesize `tool_result`s" | True, **and worse**: `store-history` fires only on `:done` (`agent.cljs:361,365`), so a cancelled turn is **silently forgotten** by the agent while still visible in the transcript. |

That last row is the real prize: an invisible correctness bug, not polish.

### Scope

**In:** streaming, stop, scroll, collapsible tool detail, markdown, a11y.
**Out:** undo/rollback of tool calls that already mutated the file (Stop halts the agent, it does
not roll Penpot back); the tool set; the skills cascade; the Skills tab; the system prompt
(owned by the metaprompt plan).

## Key decisions (resolved, with evidence)

1. **New backend command `::ai-agent-round-stream`** — not a flag, not content negotiation.
   Penpot has **no Accept-based negotiation**: `::export-binfile` unconditionally returns
   `(sse/response …)` (`binfile.clj:79`), and `repo.cljs`'s stale `:export-binfile
   {:response-type :blob}` entry has **zero callers** — it would trip `repo.cljs`'s own
   `"expected normal response, received sse stream"` assertion. Streaming is a per-command,
   server-side decision. `::ai-agent-round` stays buffered (`::doc/added`-published; `doc.clj:58`
   reads `::sse/stream?` off defmethod metadata, so a runtime flag can't be reflected).
2. **Dumb pipe** — tap provider `data:` lines verbatim; the client reassembles. Preserves the
   proxy invariant, *"forwarded verbatim so no transit/JSON key mangling can corrupt it"*
   (`ai_providers.clj:314`). `sse.clj:33` transit-encodes each event, which escapes `\n` and
   protects SSE framing. ~6 KB/s at 100 tok/s.
3. **Client sets `"stream": true`** in `build-round-body` — backend injection would mean
   decode→assoc→encode, exactly what decision 2 forbids. OpenAI's `stream_options.include_usage`
   must be client-set anyway.
4. **Accumulators produce the existing `outcome` map** `{:text :tool-calls :stopped-for-length?
   :usage}`, so `step`'s `cond` (`agent.cljs:358-368`) is untouched and `:assistant-delta` is a
   pure presentation channel. Canonical history never learns about streaming.
5. **Synthesize `tool_result`s on cancel** — Anthropic 400s on a `tool_use` without a matching
   `tool_result`; OpenAI 400s on unmatched `tool_call_id`. One fix at the canonical layer covers
   both.
6. **Markdown via `marked.lexer` → rumext elements.** Never `parse()`, never innerHTML.
   `marked@18.0.5` is **already in `package.json:88`** (vestigial since 2021, zero importers) —
   **no new dependency**, and no sanitizer needed since no HTML string is produced. `lexer` does
   **not** filter `javascript:` hrefs → explicit allowlist required.
7. **No `:tool-start` event.** Tools are **synchronous** (`agent_tools.cljs` has zero `rp/cmd!`
   or promises; every tool returns `rx/of`), so a spinner would render for zero frames. Shape the
   event map for `:status :running`, but don't build it until a tool goes async.

## Phases

Each ≤3 files, independently demoable. **01–06 are frontend-only and independent of 07–08**,
which can proceed in parallel (different app).

1. [Phase 01 — Scroll + a11y](./done-phase-01-scroll-a11y.md) — stick-to-bottom, jump-to-latest pill, live region ✅ **done**
2. [Phase 02 — Markdown rendering](./done-phase-02-markdown.md) — `marked.lexer` → elements, safe hrefs, code highlighting ✅ **done**
3. [Phase 03 — Cancel plumbing](./done-phase-03-cancel-plumbing.md) — `::cancel-turn`, `take-until`, `cancel-history` + unit test ✅ **done**
4. [Phase 04 — Composer send/stop](./done-phase-04-composer-send-stop.md) — real button, type-while-busy ✅ **done**
5. [Phase 05 — Tool event payload](./done-phase-05-tool-payload.md) — `:input`/`:result`, map-arity `append-tool` ✅ **done**
6. [Phase 06 — Collapsible tool groups](./todo-phase-06-collapsible-tools.md) — render-time grouping *(needs 05)*
7. [Phase 07 — Backend streaming + abort](./todo-phase-07-backend-streaming.md) — `::ai-agent-round-stream`, `events/closed?`
8. [Phase 08 — Client streaming](./todo-phase-08-client-streaming.md) — accumulators, `:assistant-delta`, batching *(needs 07)*

**Suggested order:** 01 → 02 (highest quality-per-risk, pure view layer) → 03 → 04 (highest-value
correctness fix) → 05 → 06 → 07 → 08.

## Acceptance Criteria

- Assistant text appears incrementally; the panel never shows a static `"Thinking…"` for a whole turn.
- New messages scroll into view **only** when already at the bottom; scrolling up during a live
  turn leaves the view put and shows a "Jump to latest" pill.
- A running turn can be stopped; `busy?` clears, the composer stays usable, generation actually
  halts server-side, and **the next turn succeeds** (proves `cancel-history`).
- The user can type while a turn is running.
- Consecutive tool calls collapse into one expandable summary showing input + result; groups
  containing a rejection/error auto-expand.
- Assistant markdown renders (headings, lists, code); `javascript:` hrefs are inert.
- The transcript is a `role="log"` live region; every control has an accessible name.

## Risks

**Riskiest: cancellation actually reaching the provider (07).** A four-link chain — browser
`(.cancel reader)` → Jetty write throws → `spawn-listener` closes the channel → pump sees
`closed?` → `.close()` aborts the JDK exchange — where **every link fails silently**. Nothing
throws, no test fails; you find out on the provider invoice. See Phase 07 for the de-risking
sequence and the `send-async` contingency.

**Second: `cancel-history` (03)** — fails silently and *later*, as a 400 on the **next** turn,
surfacing as a generic "⚠️ …" bubble with no hint the cancel caused it. Mitigated by a
pure-function unit test.

## Verification

Frontend CLJS tests: `:test` target → `frontend/test/frontend_tests/`, run with `npm test` in
`frontend/` (note `build:test` runs `build:wasm` first — slow). New test namespaces must be
registered in `runner.cljs`'s `:require`.

Live (devenv — needs Docker freed; other stacks OOM it):
```
docker exec -w /home/penpot/penpot/frontend penpot-devenv-ws0-main npx shadow-cljs compile main
```
App at `http://localhost:3450`. `ai_panel.scss` is already watched, so it hot-reloads (the
new-SCSS-file gotcha needing `build-app-assets.js` does not apply). `util/markdown.cljs` is a new
**CLJS** file — fine — but its `marked` import regenerates `target/index.js` and needs a libs
rebuild (see Phase 02).
