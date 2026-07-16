# Phase 06 — exporter screenshot-url cmd

**Status:** done (live curl verify deferred to the post-merge testing pass)

## Goal

New exporter command `:screenshot-url`: navigate a pooled Playwright Chromium to
an external URL and return a PNG. Includes the exporter's FIRST SSRF guard —
the JVM backend's `app.util.ssrf` doesn't exist on this Node service.

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] Dispatch + handler idioms re-read (`handlers.cljs` cmd multi-spec + case; export-shapes exchange/response shape)
- [x] Pool re-read: `exec!` takes a `#js` context config + page handler, fresh context per request, auto-release/destroy
- [x] **Auth finding (load-bearing):** `wrap-auth` only ATTACHES the cookie token — it validates nothing. Export handlers get validation implicitly by calling the backend with the token; a screenshot handler that skipped this would be an unauthenticated open proxy. Fix: `assert-authenticated!` calls backend `get-teams` with the cookie (200 = authenticated), reusing the `resources.cljs` undici + `cf/get-internal-uri` idiom
- [x] Playwright 1.61 `context.route("**/*", handler)` + `request.url()` confirmed available for per-request filtering

## Checklist

- [x] New ns `exporter/src/app/util/netguard.cljs`: `ip-private?` pure predicate (v4: this-net/RFC1918/loopback/link-local/CGNAT/192.0.0/TEST-NETs/benchmarking/multicast/reserved; v6: first-hextet 0 (covers ::, ::1, all v4-mapped)/link+site-local/ULA/multicast; fails CLOSED on non-literals) + `assert-public-host!` (dns.lookup {all:true} = getaddrinfo, same parser as the browser so decimal/hex IPv4 encodings resolve and get checked; ANY private record rejects; bracketed IPv6 hosts unwrapped)
- [x] Exporter test harness ADDED: `:test` node-test build in shadow-cljs.edn (output `.cjs` — the package is `"type":"module"` and node-test emits CommonJS). 11 tests / 46 assertions on the predicate table (metadata IP, CGNAT boundaries, RFC1918 lookalikes public, mapped-v4 blocked wholesale, fail-closed rows) — 0 failures
- [x] Handler `screenshot_url.cljs`: params `{url, full-page?}`; auth validated first; top-level host guard before nav; `context.route` re-guards EVERY request (redirect hops + subresources) and aborts non-http(s) schemes; context created with NO cookies (session-cookie leak documented as the reason); viewport 1280×800 dsf 1; `networkidle` 15s with screenshot-anyway-on-timeout; full-page clipped at 2400px; PNG response
- [x] `:screenshot-url` registered in the cmd multi-spec + case dispatch
- [x] Per-request hard timeout via nav-timeout + pool's own acquire timeout
- [x] clj-kondo 0/0, cljfmt clean; exporter `:main` build compiles 0 warnings
- [ ] Live curl verify — DEFERRED to the post-merge testing pass: authed public URL → PNG bytes; `http://localhost:6060` and `http://169.254.169.254` → 400 `:blocked-host`; no cookie → `:unauthorized`
- [x] Human approval: Santi pre-approved phase-by-phase commits for this plan (2026-07-16)
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment

## Files

- `exporter/src/app/handlers/screenshot_url.cljs` — new handler
- `exporter/src/app/util/netguard.cljs` — SSRF/private-IP guard (new)
- `exporter/src/app/handlers.cljs` — dispatch registration
- `exporter/shadow-cljs.edn` — new `:test` build (first exporter test harness)
- `exporter/test/app/util/netguard_test.cljs` — predicate table tests

## Notes

- DNS-rebinding residual risk documented in the handler docstring: we resolve
  at guard time, Chromium resolves again — the per-request route re-check
  narrows but does not close the window. Accepted for the prototype.
- The route guard does a DNS lookup per request; OS caching keeps it cheap and
  the nav timeout bounds the total.
- Worktree note: `exporter/node_modules` symlinked relatively to the main
  checkout's (same recipe as frontend).
- The handler stays agent-agnostic — it's just "screenshot a URL"; the agent
  tool is phase 07.
