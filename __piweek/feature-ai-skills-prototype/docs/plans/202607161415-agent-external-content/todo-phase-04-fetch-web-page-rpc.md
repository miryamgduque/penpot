# Phase 04 — fetch-web-page RPC (backend)

**Status:** todo

## Goal

A backend command `::fetch-web-page` that fetches an external URL with SSRF
checks ON, extracts readable text + brand-relevant metadata, and returns a
size-capped payload. This is the single server-side fetch primitive for phases
05 (digest) and 08 (brand).

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `backend/src/app/http/client.clj` (`req-with-redirects` `:110+`, SSRF on by default — `:9-16,64-69,118-122`) and `app.util.ssrf` (`validate-uri` `ssrf.clj:160-226`)
- [ ] Check what HTML-parsing lib the backend classpath already has (jsoup? enlive? — grep `deps.edn`); if none, decide: add jsoup (preferred, tiny) vs regex strip. Flag to user if adding a dep.
- [ ] Look at a small existing command for the ns skeleton + registration (`app.rpc.commands.ai-providers` is the closest sibling; registration list in `backend/src/app/rpc.clj` scan-ns)

## Checklist

- [ ] Backend tests (`backend_tests`): SSRF-blocked URL → `:validation/:ssrf-blocked-target`; oversized body truncated with `:truncated? true`; content-type rejection; metadata extraction from a fixture HTML
- [ ] New ns `backend/src/app/rpc/commands/agent_web.clj` with `::fetch-web-page {url}` (auth: any authed profile, same stance as ai-providers)
- [ ] Fetch: `http/req-with-redirects` (do NOT pass `:skip-ssrf-check?`), `:response-type :input-stream`, accept only `text/html`/`text/plain`/`application/xhtml`, read at most ~2MB then stop, overall timeout ~10s
- [ ] Extract and return: `{:title :text :truncated? :meta {:favicon :og-image :og-title :og-description :theme-color}}` — text is script/style-stripped, whitespace-collapsed, capped ~100k chars; favicon/og-image resolved to absolute URLs
- [ ] Register the ns in `rpc.clj` scan-ns
- [ ] Lint (clj-kondo) + cljfmt pass
- [ ] Backend picks it up via nREPL `(user/restart)` on :6064 (new RPC command needs restart, not :reload)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `backend/src/app/rpc/commands/agent_web.clj` — new command ns
- `backend/src/app/rpc.clj` — scan-ns registration
- `backend/test/backend_tests/...` — tests (path per existing suite layout)

## Notes

- Metadata extraction lives here (not phase 08) so brand work is pure composition
  later; it's a few selectors on the already-parsed document.
- Return raw-ish text, not a summary — summarization is the frontend side-turn's
  job (phase 05), keyed to the asker's question.
- SSRF errors must stay opaque (the util deliberately doesn't echo resolved IPs);
  map to a friendly "private or blocked host" message at the tool layer, not here.
