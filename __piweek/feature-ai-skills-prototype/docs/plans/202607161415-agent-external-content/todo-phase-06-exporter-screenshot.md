# Phase 06 — exporter screenshot-url cmd

**Status:** todo

## Goal

New exporter command `:screenshot-url`: navigate a pooled Playwright Chromium to
an external URL and return a PNG. Includes the exporter's FIRST SSRF guard —
the JVM backend's `app.util.ssrf` doesn't exist on this Node service.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `exporter/src/app/handlers.cljs:79-88` (cmd dispatch) and one handler (`handlers/export_shapes.cljs`) for the request/response idioms
- [ ] Re-read `exporter/src/app/browser.cljs`: pool (`:133-169`), `exec!` fresh-context-per-request (`:192-217`), goto/networkidle helpers (`:41-44`)
- [ ] Confirm how `wrap-auth` validates the caller (cookie / shared key) — the new cmd must NOT be callable unauthenticated
- [ ] Check Playwright request-interception API available in the pinned version (1.61): `context.route('**/*', handler)` + `request.url()` for per-request host filtering

## Checklist

- [ ] New ns `exporter/src/app/util/netguard.cljs` (name TBD): private-address check — parse IP literals (v4/v6, incl. decimal/octal tricks via a normalizing parser) + `dns.promises.lookup {all:true}` for hostnames; block loopback, RFC1918, link-local 169.254/16 (metadata IP included), CGNAT 100.64/10, ULA fc00::/7, v4-mapped. Unit-testable pure predicate + async resolver wrapper
- [ ] Exporter tests (or a standalone node test script under `exporter/scripts/` if no harness exists — check first): predicate table incl. `169.254.169.254`, `0x7f000001`, `[::1]`, public host passes
- [ ] New handler `exporter/src/app/handlers/screenshot_url.cljs`: params `{url, full-page?, width?}` → guard the target host BEFORE goto → fresh context (viewport default 1280×800, `deviceScaleFactor 1`, JS enabled, no auth cookie for external nav — do NOT reuse the Penpot session cookie in the external context) → `context.route` blocks any request whose host resolves private (covers redirects + subresources) → `page.goto` timeout ~15s, `networkidle` with fallback to `load` → screenshot (full-page clipped to max height ~2400px) → PNG response
- [ ] Register `:screenshot-url` in the handlers dispatch
- [ ] Concurrency/time budget: rely on the existing pool cap; per-request hard timeout so a hung page can't pin a browser
- [ ] Lint pass (exporter is CLJS — same clj-kondo/cljfmt treatment)
- [ ] Live verify with curl through nginx (`/api/export` route, authed): public URL → PNG bytes; `http://localhost:6060` and `http://169.254.169.254` → 4xx with a clear error
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `exporter/src/app/handlers/screenshot_url.cljs` — new handler
- `exporter/src/app/util/netguard.cljs` — SSRF/private-IP guard (new)
- `exporter/src/app/handlers.cljs` — dispatch registration

## Notes

- The auth cookie question is load-bearing: the export flow injects the Penpot
  session cookie into the browser context (`browser.cljs:31-39`). For EXTERNAL
  navigation that cookie must be omitted — leaking it to an arbitrary site would
  be a session handoff.
- DNS-rebinding residual risk: we resolve at guard time and Chromium resolves
  again at request time. `context.route` re-checking per request narrows the
  window; document as accepted prototype risk in the handler docstring.
- Keep the cmd generic (no agent coupling) — it's just "screenshot a URL"; the
  agent tool arrives in phase 07.
