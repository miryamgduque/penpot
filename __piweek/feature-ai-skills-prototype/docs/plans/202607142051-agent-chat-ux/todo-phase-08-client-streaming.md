# Phase 08 — Client streaming

**Status:** todo
**Depends on:** Phase 07 (backend endpoint), Phase 01 (autoscroll — or streaming text runs off-screen)

## Before Start

- [ ] Verify plan is still valid
- [ ] Confirm Phase 07's event shape matches what's assumed here (the contract, not an observation)
- [ ] Read `agent.cljs:119-194` (`build-round-body`, `decode-anthropic`, `decode-openai`) and `:342-368` (`step`)
- [ ] Read `util/sse.cljs` and `repo.cljs:127-155,215-228`
- [ ] Confirm the Phase 01 `aria-live` decision (announce on `:assistant-end`, not per delta)

## Checklist

- [ ] **Tests first**: accumulator unit tests (deltas → `outcome`) in `agent_test.cljs`
- [ ] `::sse/ai-agent-round-stream {:stream? true}` in `repo.cljs` `default-options`
- [ ] `build-round-body`: `"stream": true` (+ OpenAI `stream_options.include_usage`)
- [ ] `accumulate-anthropic` / `accumulate-openai` → the existing `outcome` map
- [ ] `step` consumes deltas; emits `:assistant-start` / `:assistant-delta` / `:assistant-end`
- [ ] `append-delta` mutates the last message in place
- [ ] Batch with `rx/buffer-time 100` in `send-message`'s `mapcat`
- [ ] `npm test` passes
- [ ] Live review: text streams; scroll/stop/collapse still work
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Update README status to `done`; write the Completion Summary
- [ ] Consider retiring `ai-skills/` (the porting spec) — coordinate; out of scope here

## Files

- `frontend/src/app/main/repo.cljs` — one `default-options` entry
- `frontend/src/app/main/data/workspace/agent.cljs` — stream flags, accumulators, `step`
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — `append-delta`, batching

## Notes

**Transport is already built — this is the cheap half.** Verified line by line in `repo.cljs`:
method → `:post` (`:175-178`); `:response-type nil` when `stream?` (`:200-201`) so `body` stays a
`ReadableStream`; `accept` already includes `text/event-stream` (`:187`); dispatch by content-type
(`:215-228`) → `(sse/create-stream body)` → `(sse/read-stream t/decode-str)`; no retry on POST
(`:240-242`) — correct, a retried agent round would double-bill. Consumer template:
`exports/files.cljs:64-77`. So the only client transport change is one registry line.

**Client accumulates — the backend is a dumb pipe.** `accumulate-anthropic` / `accumulate-openai`
fold deltas into **the existing `outcome` map** `{:text :tool-calls :stopped-for-length? :usage}`
(`agent.cljs:161-173`). Then `step`'s whole `cond` (`:358-368`) — usage → empty-reply → no-calls →
`tool-round` — stays untouched, and `:assistant-delta` is a **pure presentation channel**. The
canonical history never learns about streaming. Keep `decode-anthropic`/`decode-openai` for the
buffered command.

**Dialect details.**
- *Anthropic:* `content_block_start` (`tool_use` id/name) → N× `content_block_delta`
  (`input_json_delta.partial_json`) → `content_block_stop`. Accumulate `partial_json` per `index`,
  `JSON.parse` at the end. **A no-arg tool emits zero deltas** → parsing `""` throws → default to
  `{}`, mirroring the existing guard at `:186-188`. Usage spans **two events**:
  `message_start.message.usage` (input/cache) + `message_delta.usage.output_tokens`; merge them.
  `message_delta.delta.stop_reason` drives `:stopped-for-length?`.
- *OpenAI:* `choices[0].delta.tool_calls` = `[{index, id?, type?, function:{name?, arguments:"frag"}}]`;
  `id`/`name` typically arrive only on the first fragment per index. Accumulate by the **tool-call**
  index (not the choice index). Usage only with `stream_options.include_usage` → a final chunk with
  `choices: []` + `usage`.
- **Silent regression risk:** if `include_usage` is unsupported (zhipu/moonshot unverified —
  `ai_providers.clj:45-49` already documents zhipu deviating from OpenAI on `/models`, so assume
  nothing), `usage` is absent and the spend meter reads **0 with no error** — `add-usage`
  (`:204-207`) uses `merge-with +` over `empty-usage`, so it degrades to zeros silently. Check this
  explicitly per provider.

**Mid-stream provider errors ride as `:delta`, not as our `:error` event.** `sse.cljs:40-41` does
`(ex-info "stream exception" data)` — `ex-info` **requires a map**, and a dumb-piped provider payload
is a **string**, so tapping it as `:error` would throw inside the operator. The accumulator
recognises `type == "error"` and raises an `ex-info` shaped `{:hint …}` so
`data/workspace/ai_panel.cljs:167-169` renders it. Partial-then-error is then correct by
construction: deltas already rendered stay, the observable errors, `rx/catch` appends "⚠️ …". You
keep the half-answer and learn it broke.

**Per-token potok updates are not viable — and not for the obvious reason.** Each `UpdateEvent` is a
`swap!` on the **root** app-state atom, so every `mf/deref` subscriber across the *entire workspace*
(viewport, layers, options sidebar) is notified and re-runs its equality check. At ~50 tok/s that's
50 root-atom churns/sec competing with canvas rendering. The panel's own re-render is the cheap part.

Batch in the stream, not the component, so `run-turn` stays a pure event source and remains testable:
```clojure
(->> deltas
     (rx/buffer-time 100)   ; confirmed in beicon — 4 in-repo uses
     (rx/filter seq)
     (rx/map #(append-delta (str/join "" %))))
```
100ms ≈ 10 updates/sec — still reads as smooth streaming, ~5-10× less atom churn.
`append-delta` mutates the last message: `(update messages (dec (count messages)) update :content str chunk)`
— O(1) on a vector, no rebuild.

**Bookends are required.** `:assistant-start` (open a bubble) and `:assistant-end` (seal it) —
without them you can't tell "append to current bubble" from "begin a new one" across multi-round
turns where round 2's text follows tool chips. `:assistant-end` is also the `aria-live` announcement
trigger (Phase 01).

**Teardown already correct:** `read-stream`'s `rx/create` returns `(fn [] (.cancel reader))`
(`sse.cljs:35`), so Phase 03's `rx/take-until` genuinely aborts the HTTP body read rather than merely
unsubscribing — which is what makes Phase 07's server-side abort fire.

**Infra note.** Prod nginx sets `proxy_buffering off` on `location /api`
(`docker/images/files/nginx.conf.template:132-135`). Devenv does **not**
(`docker/devenv/files/nginx.conf:123-126`) and relies on `X-Accel-Buffering: no` from `sse.clj:47`,
which nginx honours. If deltas arrive batched in devenv, that's the first thing to check.
