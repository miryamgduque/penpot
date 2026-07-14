# Phase 07 — Backend streaming + abort

**Status:** todo

Backend-only; can proceed in parallel with Phases 01–06.
**Abort ships in this phase, not later** — it's ~5 lines, and retrofitting means re-testing the
whole chain.

## Before Start

- [ ] Verify plan is still valid
- [ ] Read `rpc/commands/ai_providers.clj` (`::ai-agent-round` :318, `provider-req!` :70-79)
- [ ] Read `http/sse.clj` (`encode` :28-39, `response` :49-73) and `util/events.clj` (`tap` :20-27, `spawn-listener` :34-45)
- [ ] Read the SSE precedent: `rpc/commands/management.clj:459-476`
- [ ] Confirm `sp/closed?` is still available (used at `msgbus.clj:229`)

## Checklist

- [ ] Extract `round-request` (shared by both commands)
- [ ] Add an options-passthrough arity to `provider-req!`
- [ ] Add `events/closed?` to `app.util.events`
- [ ] `check-stream-status!` — raise **before** `sse/response`
- [ ] `pump-provider-stream!` — tap `data:` lines verbatim, pass `ping` through
- [ ] `::ai-agent-round-stream` defmethod with `::sse/stream? true`
- [ ] **Abort test against a local slow-body server** (no credentials needed)
- [ ] `curl -N` against a real provider key — assert incremental frames
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Record whether `.close()` actually aborted upstream (see Notes) — Phase 08 depends on it
- [ ] Phase 08 unblocked

## Files

- `backend/src/app/rpc/commands/ai_providers.clj` — `round-request`, `check-stream-status!`, `pump-provider-stream!`, `::ai-agent-round-stream`
- `backend/src/app/util/events.clj` — `closed?`

## Notes

**A new command, not a flag — settled by evidence.** Penpot has **no Accept-based content
negotiation**: `::export-binfile` unconditionally returns `(sse/response …)` (`binfile.clj:79`), and
`repo.cljs`'s `:export-binfile {:response-type :blob}` entry has **zero callers** (only
`::sse/export-binfile` is used, `exports/files.cljs:64`) — it would trip `repo.cljs`'s own
`"expected normal response, received sse stream"` assertion. Also: `default-options` is keyed by
command id and `cmd!` is strictly 2-arity (`repo.cljs:244`), so a per-call flag means touching every
`defmethod`; `::doc/added` publishes `ai-agent-round` at `/api/main/methods/ai-agent-round`, so
flipping its content-type conditionally is a breaking change; and `doc.clj:58` reads `::sse/stream?`
off *defmethod metadata*, which a runtime flag can't reflect.

Note `::sse/stream? true` is **documentation-only** — the sole consumer is `doc.clj:58`. Actual
streaming is driven purely by returning a fn from the handler (`rpc.clj:70-77`). Set it anyway; it's
the convention.

**Header-first is the key design move.** With `:response-type :input-stream`, `provider-req!`
returns as soon as **response headers** arrive. So the provider's status is known *before* we commit
to `sse/response` — meaning bad key / 401 / 429 / bad model / context-too-long (the overwhelming
majority of real errors) stay on the **ordinary HTTP 400 transit path** the panel already handles at
`data/workspace/ai_panel.cljs:164-170`. **No panel change for errors.** Verified chain:
`ex/raise :type :validation` → `errors.clj:105-106` (`{::yres/status 400 ::yres/body data}`) →
`repo.cljs:114-115` → panel reads `(:hint data)`.

`check-stream-status!` must slurp the (small) error body and lift the provider's own message into
`:hint`, because an SSE client can never do what `parse-round` (`agent.cljs:142-155`) does today.

**Dumb pipe.** Tap each `data:` payload verbatim. Anthropic's `event:` names are droppable — every
Anthropic data payload carries its own `"type"` duplicating the event name, so `data:` alone covers
both dialects with no branching. `sse.clj:31-34` puts the payload on a single `data:` line via
`println`; raw provider JSON containing a newline would shatter framing, but **transit escapes `\n`
inside strings, so the double-encoding is exactly what protects it** — non-obvious and load-bearing,
worth a code comment.

**Pass Anthropic's `ping` through, don't filter it.** It keeps nginx's `proxy_read_timeout 300s`
(`docker/images/files/nginx.conf.template:38`) alive through a long thinking pause, **and** it forces
a `write!` every ~10s — the only way a broken pipe gets detected while the model is silent.

**Abort.** `events/tap` (`events.clj:20-24`) discards `sp/put!`'s return and always yields nil, so
the pump can't learn anything from it. The real signal is the channel's closed state: on a broken
pipe `write!` throws → `spawn-listener` (`:39-44`) logs and `(sp/close! channel)`. Add:
```clojure
(defn closed? []
  (let [ch *channel*] (or (nil? ch) (sp/closed? ch))))
```
Then **break the loop and let `with-open` close the InputStream** — deliberately *not* an exception:
`sse.clj:67-69`'s catch would route it through `errors/handle'` and tap `:error` on a closed channel,
polluting logs with a spurious error for a *normal* user action. Returning normally taps `:end` on a
closed channel — a clean no-op. Log `l/inf "ai stream aborted, client gone"` so absence in prod =
broken chain.

### The riskiest thing in the whole plan

**Cancellation actually reaching the provider is a four-link chain where every link fails silently:**
1. browser `(.cancel reader)` (`sse.cljs:35`) → TCP close
2. yetti/Jetty notices → `write!` (`sse.clj:22-26`) throws
3. `spawn-listener` → `(sp/close! channel)`
4. pump sees `closed?` → `with-open` → `.close` → JDK aborts the exchange

Nothing throws, nothing 500s, no test fails. **You find out on the invoice.**

**Unverified:** whether `.close()` on java-http-clj 0.4.3's `:input-stream` body actually aborts the
JDK exchange. (JDK's `HttpResponseInputStream.close()` cancels the body subscription and closes a
partially-read connection, which is the TCP close that makes the provider stop generating — but the
library source went unread; no local `~/.m2`.)

**De-risk, cheapest first:** test link 4 in isolation against a **local slow-body server** before
touching a provider — assert the server observes the disconnect. Zero credentials, and it's the only
genuinely unknown link. Then end-to-end: long round, close the tab, watch for the FIN, confirm
against the provider's usage dashboard.

**Contingency if link 4 fails:** `app.http.client/send!` (`client.clj:40-44`) uses the sync
`http/send`; java-http-clj also exposes `send-async`, whose `CompletableFuture.cancel` reliably
aborts. Larger change to a shared file — contingency, not plan A.

**Also unverified:** `:timeout (ct/duration "240s")` (`:336`) maps to `HttpRequest.Builder/timeout`;
under `ofInputStream` the future completes at headers, so this likely degrades to a **header-only**
timeout with unbounded body reads — a stalled provider could hang a virtual thread forever. Consider
a watchdog or a read deadline. Also: `events/tap` discards the `sp/put!` promise, so with `:buf 32`
(`sse.clj:50`) a slow client could grow an unbounded pending-put queue (almost certainly moot at
~6 KB/s, but unconfirmed).
