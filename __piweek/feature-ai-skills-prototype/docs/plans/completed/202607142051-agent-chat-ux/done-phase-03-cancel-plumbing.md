# Phase 03 — Cancel plumbing

**Status:** done

**This is the highest-value phase in the plan.** It fixes an invisible correctness bug, not polish.

## Verified

**Unit** — `npm test`: **438 tests / 1793 assertions, 0 failures** (was 429/1775; +9 tests, +18
assertions). All nine `frontend-tests.data.agent-test` vars ran — confirmed by name in the output,
because a registered-but-skipped test is a green lie (see the runner trap below). Includes a
deliberate sanity test, `anthropic-uncancelled-dangling-would-be-rejected`, asserting the *unfixed*
shape really is unbalanced — otherwise the suite could pass without proving anything.

**Live (devenv :3450)** — a real turn, cancelled mid-flight, then a follow-up turn:

| Behaviour | Result |
|---|---|
| `busy?` during the turn | ✅ `true` |
| **`busy?` after cancel** (the deadlock risk) | ✅ `false` |
| transcript marks the interruption | ✅ `⏹ Stopped.` |
| **user message survives the cancel** | ✅ `history` count 1 — previously the exchange was forgotten entirely |
| **the next turn succeeds** | ✅ replied `OK`, no `⚠️` — proves `cancel-history` left a history the provider accepts |

Cost: two Haiku calls.

## Before Start

- [ ] Verify plan is still valid
- [ ] Re-read `agent.cljs:305-369` (`run-turn`/`step`/`tool-round`) and `data/workspace/ai_panel.cljs:137-171`
- [ ] Confirm `encode-anthropic` (`:70-91`) / `encode-openai` (`:102-116`) still emit tool blocks as described
- [ ] Confirm `viewer.cljs:561-564` is still a good `take-until` template

## Checklist

- [x] **Tests first**: `frontend/test/frontend_tests/data/agent_test.cljs`, registered in `runner.cljs`
      (both the `:require` **and** `test-namespaces` — see trap 1)
- [x] `cancel-history` synthesizes `tool_result`s for dangling `tool_use`s
- [x] `::cancel-turn` event in `data/workspace/ai_panel.cljs`
- [x] `rx/take-until` wired in `send-message`; `watch` binds `stream`
- [x] Cancel path → `store-history` (via `:turn-history` + a deferred tail, not a `:cancelled`
      event — `run-turn` can't detect its own cancellation; see trap 2)
- [x] `npm test` passes (438/1793, 0 failures)
- [x] Human approval received
- [x] Committed with a gitmoji commit (`:bug:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Note follow-ups below
- [ ] Phase 04 unblocked

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — `cancel-history`, `cancelled-result`, `:cancelled` event
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — `cancel-turn`, `take-until` wiring
- `frontend/test/frontend_tests/data/agent_test.cljs` — **new**
- `frontend/test/frontend_tests/runner.cljs` — register the ns

## Notes

### Traps hit while building this

**1. The test runner has a SEPARATE `test-namespaces` list.** Adding the ns to `runner.cljs`'s
`:require` compiles it but **does not run it** — the suite went green while my tests never executed.
`runner.cljs` has both a `:require` entry *and* a `(def test-namespaces ['…])` vector; you need both.
Always confirm your test names appear in the output.

**2. `run-turn` cannot detect its own cancellation.** `take-until` unsubscribes from the *outside*, so
the loop simply stops — it never learns why. The turn therefore publishes `{:kind :turn-history}` after
each round, and `send-message` keeps the latest in an atom (seeded with the user message so an early
stop still remembers it). That published history is what `cancel-history` closes off.

**3. `rx/defer` does not exist in beicon** (0 uses in-tree). The deferred tail is
`(->> (rx/of ::end) (rx/mapcat …))` — `concat` only subscribes to it once the turn is over, so the
atoms have settled by then.

**4. `encode-anthropic` / `encode-openai` are now public.** They were `defn-`, which made the wire
invariant — the single most valuable assertion in this plan — untestable. They are pure functions and
the wire format is legitimately part of this ns's contract.

**5. `send-message` had drifted.** The metaprompt work (US #26) moved `context` onto the user message
and dropped the `build-system-prompt` argument while this plan was being written. Re-read before
editing; the plan's snippet was already stale.

**The bug.** `send-message` builds `history` locally (`:149`) and `store-history` fires **only** on
`:done` (`agent.cljs:361,365`). So cancelling mid-turn reverts `:history` to the previous turn: no
dangling `tool_use` (good), but the whole exchange is **forgotten by the agent while still visible
in the transcript**. "Make that button blue" after a cancelled turn silently refers to nothing.
That's worse than a crash because it's invisible.

**Why synthesize rather than leave it forgotten.** Verified against the Anthropic Messages API docs
rather than memory, because the whole decision turns on it:

> "Tool result blocks must immediately follow their corresponding tool use blocks in the message
> history."

and the exact failure — a **400**:

> "tool_use ids were found without tool_result blocks immediately after"

Synthesizing a result the tool never produced is an **explicitly documented remedy**: the docs' own
"Invalid tool name" example returns `{"type":"tool_result", "content":"Error: …", "is_error": true}`
for a call that was never executed. So a fabricated "Cancelled by user" with `is_error: true` is a
sanctioned pattern, not a hack.

**It bites in `build-round-body`.** `encode-anthropic:70-79` emits a `tool_use` for every
`:tool-calls` entry, and emits `tool_result` **only** from a following `:tool-results` message
(`:84-91`). Storing `messages'` from `step:353-355` mid-round produces exactly the rejected shape.
Same for OpenAI: `encode-openai:102-111` emits `tool_calls`; `:114-116` is the only source of
`role:"tool"`, and OpenAI 400s on unmatched `tool_call_id`. **One synthesis at the canonical layer
fixes both providers** — the payoff of the canonical-history design.

```clojure
;; agent.cljs — next to tool-outcome->result (:293)
(defn- cancelled-result [call]
  {:id (:id call) :content "Cancelled by the user before this tool ran." :error? true})

(defn- cancel-history
  "Closes any dangling tool_use so the next request is well-formed."
  [messages]
  (let [last-msg (peek messages)]
    (if-let [calls (and (= :assistant (:role last-msg)) (seq (:tool-calls last-msg)))]
      (conj messages {:role :tool-results :results (mapv cancelled-result calls)})
      messages)))
```
Emit `{:kind :cancelled :history (trim-history (cancel-history messages'))}`. Cleanest seam is
`tool-round` (`:326-340`) — the only place a dangling `tool_use` can exist. `trim-history`
(`:263-278`) already only cuts at `:user`, so it won't split the pair just closed.

**Wiring — two subtleties that will bite.**
```clojure
(defn cancel-turn [] (ptk/reify ::cancel-turn))

;; send-message's (watch [_ state stream])  ← currently binds `_` (:146). Must change.
(let [stopper (rx/filter (ptk/type? ::cancel-turn) stream)]
  (->> (agent/run-turn settings history system)
       (rx/mapcat …)      ; :156-163, add :cancelled → store-history
       (rx/catch …)       ; :164-170
       (rx/take-until stopper)))
```
1. `watch` binds `_` for `stream` today — that must become `stream`.
2. **`take-until` must wrap the pipeline including `rx/catch`, but `(rx/of (set-busy false))`
   (`:171`) MUST stay outside it** — otherwise a cancel leaves `busy?` stuck true forever and the
   panel deadlocks. The existing `rx/concat` at `:151`/`:161` already gives that structure; keep
   `set-busy false` in the outer concat.

`rx/take-until` is heavily idiomatic here (80 in-repo uses). Teardown is genuinely correct for
Phase 08 too: `sse.cljs:35`'s `rx/create` returns `(fn [] (.cancel reader))`, so cancel aborts the
HTTP body read rather than merely unsubscribing.

**Why this is the second-riskiest phase.** A `cancel-history` bug does **not** error at cancel
time — it errors on the **next** user message, as a provider 400, surfacing through the generic
`rx/catch` (`:164-170`) as a "⚠️ …" bubble with no hint that a cancel three turns ago caused it.
It's also the only change writing to the canonical `:history` every future turn reads. Hence
tests-first: assert `cancel-history` → `encode-anthropic`/`encode-openai` yields **a matching
`tool_result`/`tool` message for every `tool_use` id**. Pure functions, no network. Also cover
`trim-history` not splitting the pair it just closed.

**Note:** client-side cancel stops the *panel*. It does **not** stop the provider burning tokens
server-side — that's Phase 07. Ship 07 before claiming Stop works end-to-end.
