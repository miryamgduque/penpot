# Phase 04 — fetch-web-page RPC (backend)

**Status:** done

## Goal

A backend command `::fetch-web-page` that fetches an external URL with SSRF
checks ON, extracts readable text + brand-relevant metadata, and returns a
size-capped payload. This is the single server-side fetch primitive for phases
05 (digest) and 08 (brand).

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions)
- [x] `req-with-redirects` re-read — SSRF on by default per hop; `:timeout` rides the request map (`ct/duration`), `:response-type :input-stream` + `:max-redirects` ride the options map (the `media/download-image` idiom)
- [x] HTML parser: **jsoup 1.22.2 is already in backend/deps.edn** — no new dependency, nothing to flag
- [x] Skeleton mirrored from `ai-providers` (`sv/defmethod` + `::sm/params`, `::doc/added "2.13"`); registration is the `sv/scan-ns` list in `rpc.clj:333`

## Checklist

- [x] Tests: `backend/test/backend_tests/agent_web_test.clj` — 9 tests / 22 assertions, pure extraction (title, script/style/svg-free collapsed text, relative og:image + favicon resolved absolute, /favicon.ico fallback, description fallback, blank title → nil, charset parsing incl. quoted values, text cap + flag). Run focused: `clojure -M:dev:test --focus backend-tests.agent-web-test` — 0 failures. (SSRF/redirect/cap network behavior belongs to `app.http.client`, exercised elsewhere)
- [x] New ns `backend/src/app/rpc/commands/agent_web.clj` with `::fetch-web-page {url ::sm/uri}` (default auth = any authed profile)
- [x] Fetch: `http/req-with-redirects` with SSRF ON, input-stream response, 2MB bounded read (`read-capped`), 10s timeout, ≤4 redirects, explicit browser-ish user-agent; `IOException` → stable `:unable-to-fetch-page`, `ex/raise`d errors (`:ssrf-blocked-target`) pass through untouched
- [x] Content types allowed: text/html, application/xhtml+xml, text/plain (plain skips jsoup); anything else → `:content-type-not-allowed`
- [x] Returns `{:title :text :truncated :meta {:og-title :og-description :og-image :favicon :theme-color}}`, text capped at 100k chars
- [x] Registered in `rpc.clj` scan-ns (alphabetical, after agent-chats)
- [x] clj-kondo 0/0, cljfmt clean
- [ ] Backend `(user/restart)` via nREPL — DEFERRED to the post-merge testing pass (the running backend serves the main checkout; restarting it now onto worktree code would be wrong anyway)
- [x] Human approval: Santi pre-approved phase-by-phase commits for this plan (2026-07-16)
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment

## Files

- `backend/src/app/rpc/commands/agent_web.clj` — new command ns
- `backend/src/app/rpc.clj` — scan-ns registration
- `backend/test/backend_tests/agent_web_test.clj` — tests

## Notes

- Metadata extraction lives here (not phase 08) so brand work is pure
  composition later.
- Raw-ish text is returned, not a summary — summarization is the frontend
  side-turn's job (phase 05), keyed to the asker's question.
- Gotcha found: quoted charset values (`charset="utf-8"`) need the quote
  outside the capture class or the regex silently falls back to UTF-8.
- Backend suite runs via kaocha (`clojure -M:dev:test --focus <ns>`); the
  focused run needs no DB for this namespace.
