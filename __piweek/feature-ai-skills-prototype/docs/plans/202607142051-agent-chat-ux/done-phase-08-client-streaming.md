# Phase 08 — Client streaming

**Status:** done
**Depends on:** Phase 07 (backend endpoint) ✅, Phase 01 (autoscroll) ✅

## Verified

**Unit** — `npm test`: **443 tests / 1805 assertions, 0 failures** (+5 tests since phase 03). All 14
`agent-test` vars confirmed by name in the output. The accumulators are asserted to rebuild exactly
what the buffered decoders returned: text from deltas, tool JSON from `partial_json` fragments,
usage merged across `message_start` + `message_delta`, a no-arg tool's empty JSON, and OpenAI's
id/name-only-on-first-fragment plus its `include_usage` tail chunk.

**Live (devenv :3450):**

| Behaviour | Result |
|---|---|
| **text streams into the bubble** | ✅ first text at 1206ms, growing over 6.8s |
| a tool round splits bubbles correctly | ✅ `user → assistant(56) → tool → assistant(291)` — the second bubble opens off `append-delta`'s fallback |
| usage accumulates across rounds | ✅ 3 calls, 10.6k in / 452 out, `~$0.01` |
| **Stop mid-stream** | ✅ text froze, partial answer kept, `busy?` cleared, `⏹ Stopped.` |
| **Stop aborts the provider** | ✅ backend logged `ai stream aborted, client gone … events=13` |
| buffered `::ai-agent-round` untouched | ✅ still serves external callers |

### Cache tokens read 0 — checked, NOT a streaming regression

The plan warned usage can silently read zero. It does — but calling the **buffered** and
**streaming** commands with the same cached-prefix payload returns *identical* usage
(`cache_creation_input_tokens: 0`, `cache_read_input_tokens: 0`). So caching isn't engaging on
either path and the accumulator reads `message_start.usage` correctly. **Belongs to the metaprompt
plan (US #26), which owns prompt caching** — not this one.

## Before Start

- [ ] Verify plan is still valid
- [ ] Confirm Phase 07's event shape matches what's assumed here (the contract, not an observation)
- [ ] Read `agent.cljs:119-194` (`build-round-body`, `decode-anthropic`, `decode-openai`) and `:342-368` (`step`)
- [ ] Read `util/sse.cljs` and `repo.cljs:127-155,215-228`
- [ ] Confirm the Phase 01 `aria-live` decision (announce on `:assistant-end`, not per delta)

## Checklist

- [x] **Tests first**: accumulator unit tests (deltas → `outcome`) in `agent_test.cljs`
- [x] `::sse/ai-agent-round-stream {:stream? true}` in `repo.cljs` (landed with phase 07)
- [x] `build-round-body`: `"stream": true` (+ OpenAI `stream_options.include_usage`)
- [x] `accumulate-anthropic` / `accumulate-openai` → the existing `outcome` map
- [x] `step` consumes deltas — bookends dropped, see deviation 2
- [x] `append-delta` mutates the last message in place
- [x] ~~Batch with `rx/buffer-time 100`~~ — **not needed, measured**; see deviation 3
- [x] `npm test` passes (443/1805, 0 failures)
- [x] Live review: text streams; stop/tool-splitting/usage all work
- [x] Human approval received
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Update README status to `done`; write the Completion Summary
- [ ] Consider retiring `ai-skills/` (the porting spec) — coordinate; out of scope here

## Files

- `frontend/src/app/main/repo.cljs` — one `default-options` entry
- `frontend/src/app/main/data/workspace/agent.cljs` — stream flags, accumulators, `step`
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — `append-delta`, batching

## Notes

### Deviations from the plan

**1. The SSE event type is `"delta"`, not `"event"`.** `sse/read-stream` maps the frame's `event:`
name to `:type`, and the backend taps `:delta` per provider line. (`sse/event?` checks for
`"event"` — that helper is for handlers that tap `:event`, and does not apply here.)

**2. No `:assistant-start` / `:assistant-end` bookends.** They turned out redundant:
`append-delta` opens a bubble when the last message isn't an assistant one, which — after a user
message or a run of tool chips — is exactly the round's first delta. Emitting an `:assistant-end`
the panel ignores would be noise. Verified across a tool round: `user → assistant(56) → tool →
assistant(291)` produced two correctly-separated bubbles. The `{:kind :assistant …}` emission is now
reserved for the empty-reply warning, where nothing was streamed.

**3. `rx/buffer-time` batching was dropped — the premise didn't hold.** The plan feared ~50 root-atom
`swap!`s/sec. Measured reality is **~7/sec** (phase 07: 28 events over 4.1s), because Anthropic packs
multiple tokens per delta. Batching would have added an ordering hazard (deltas racing `:done`) to
solve a problem that doesn't exist. Revisit only if a provider streams per-token.

**4. `aria-live` gating is `aria-busy`, not an `:assistant-end` trigger.** `aria-busy` on the live
region is the standard way to hold announcements until content settles, and it needs no event —
`busy?` already tracks exactly that window.

**5. Dead code removed.** `step` no longer decodes a buffered response, so the `decode` letfn went
with it — and clj-kondo then flagged `parse-round` / `decode-anthropic` / `decode-openai` as unused
private vars, so they are gone too (git history keeps them if a client-side fallback is ever
wanted). The buffered `::ai-agent-round` command stays on the backend for external callers. The ns
docstring was also stale ("Phase 01 is text-only: no tools, a single round per turn") — tools and
the multi-round loop shipped long ago.

**Verified after removal:** `aria-busy` is `"true"` during a stream and `"false"` after, so the live
region holds announcements until the text settles; markdown still renders in a streamed bubble
(`<strong>` present).

**6. `empty-usage` moved above the accumulators** (they seed themselves with it) — CLJS has no
forward references.

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
