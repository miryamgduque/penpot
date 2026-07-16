# Agent External-Content Tools

**Status:** doing
**Created:** 2026-07-16
**Apps:** `frontend`, `backend`, `exporter`
**Dependencies:** None (builds on the embedded agent chat on `feature/ai-skills-prototype`)
**Branch:** `feature/agent-external-content` (worktree off `feature/ai-skills-prototype` @ 14031cae08)

## Context

The embedded agent can read and mutate the design file but has no access to external
content. Real design work needs it: mock/placeholder imagery, icons, fonts, and
"look at this website" references (text and screenshots). Discovery decisions
(Santi, 2026-07-16):

- **Scope:** all six features — placeholder images, Iconify icons, Google Fonts,
  page fetch (via side-turn digest), external-page screenshots, brand extraction.
- **Timing:** post-demo, no rush. The Friday demo runs on what's already merged.
- **Providers:** keyless only (Lorem Picsum, placehold.co, DiceBear, Iconify,
  Google Fonts). Keyed stock search (Unsplash/Pexels) is a follow-up, not here.

**Out of scope:** outbound MCP client in the chat; Anthropic `mcp_servers`
connector passthrough (noted as the future escape hatch for user-supplied
integrations); keyed stock-photo providers.

## Grounding (verified 2026-07-16)

- `::create-file-media-object-from-url` exists (`backend/src/app/rpc/commands/media.clj:208`),
  downloads via `media/download-image` with the **SSRF guard ON** (`app.util.ssrf/validate-uri`
  applied per request and per redirect hop, max 3 redirects) and requires a
  `content-length` response header. Frontend wrapper: `upload-media-url`
  (`frontend/src/app/main/data/workspace/media.cljs:109`); URL→media+shape flow:
  `process-media-objects` (`media.cljs:250-282`).
- SVG import is programmatic: `valid-svg-string?` / `svg->clj` (`media.cljs:47,58-69`)
  → `svg/add-svg-shapes` (pattern in `svg-uploaded`, `media.cljs:97-106`).
- Google Fonts catalog is in memory (`frontend/src/app/main/fonts.cljs:28-29`),
  searchable (`find-font-family` `:83-90`), loadable on demand (`ensure-loaded!` `:214+`).
- Tool plumbing: specs vector + `execute-tool` case dispatch
  (`frontend/src/app/main/data/workspace/agent_tools.cljs:85,2894-2928`); handlers
  return rx observables; images ride a top-level `:images` key like `render_board`
  (`agent_tools.cljs:1019-1080`, lifted at `agent.cljs:789-800`, dropped after one
  round by `strip-result-images` `agent.cljs:96-134`).
- Side turns: `run-side-turn` (`agent.cljs:1048-1084`, Haiku-friendly bounded loop),
  allowlist enforced at construction AND per call (`agent.cljs:1034-1063`), runner
  reaches agent_tools through the `side-turn-runner*` atom (`agent_tools.cljs:2809`);
  `explore_design` (`agent_tools.cljs:2860-2890`) is the digest precedent, spend
  metered by `meter-scout-usage` (`:2843-2858`).
- Backend HTTP fetch: `app.http.client/req-with-redirects` with SSRF **on by
  default** (`backend/src/app/http/client.clj:110`, `backend/src/app/util/ssrf.clj:160-226`).
- Payload cap: `[:payload [:string {:max 4000000}]]` on both AI round commands
  (`backend/src/app/rpc/commands/ai_providers.clj:330,378`).
- Exporter: Playwright Chromium **pool** (`exporter/src/app/browser.cljs:133-169`),
  fresh context+page per request (`exec!` `:192-217`), cmd-based dispatch
  (`exporter/src/app/handlers.cljs:79-88`), reached by the **frontend** through
  nginx `/api/export` → `:6061` (`frontend/src/app/main/repo.cljs:265-280`,
  `docker/devenv/files/nginx.conf:119-120`). **No SSRF guard exists in the
  exporter** — the screenshot phase must add one (Node side).

## Phases

Ordered so frontend-only wins land first, the web-fetch backend unblocks both
text and brand phases, and brand extraction composes everything before it.

1. [Phase 01 — insert_image tool](./done-phase-01-insert-image.md) — URL → media → image shape; placeholder recipes (Picsum/placehold.co/DiceBear) in the tool description
2. [Phase 02 — Iconify icons](./done-phase-02-iconify-icons.md) — `search_icons` + `insert_icon` via api.iconify.design → SVG import
3. [Phase 03 — Google Fonts](./done-phase-03-google-fonts.md) — `search_fonts` + `set_font` over the in-memory catalog + `ensure-loaded!`
4. [Phase 04 — fetch-web-page RPC](./todo-phase-04-fetch-web-page-rpc.md) — backend command: SSRF-guarded fetch, HTML→text, metadata extraction, size caps
5. [Phase 05 — fetch_page tool via side-turn](./todo-phase-05-fetch-page-side-turn.md) — untrusted page text digested by a toolless Haiku side turn; only the digest reaches the main agent
6. [Phase 06 — exporter screenshot-url cmd](./todo-phase-06-exporter-screenshot.md) — new exporter handler on the browser pool, WITH a Node-side SSRF guard
7. [Phase 07 — screenshot_page tool](./todo-phase-07-screenshot-tool.md) — screenshots ride the `:images` path to vision models; payload-cap-aware sizing
8. [Phase 08 — brand extraction playbook](./todo-phase-08-brand-extraction.md) — skill that composes fetch_page metadata + insert_image + screenshot_page + create_token

## Acceptance Criteria

- The agent can populate a mock design with placeholder photos, labeled blocks,
  and avatars from keyless services, positioned and sized as asked.
- The agent can search and insert monochrome icons and apply a Google Font to
  text shapes without the user leaving the chat.
- "What does example.com say about pricing?" works: the main agent receives a
  ≤6k-char digest, never the raw page, and the side turn's spend hits the meter.
- "Screenshot example.com" returns a legible PNG into the conversation that a
  vision model can describe, without tripping the 4M payload cap.
- A "import brand from URL" ask yields proposed color tokens + logo imagery,
  gated by the existing token-only-colors guard.
- No new fetch path skips SSRF validation (backend guard for RPC fetches, new
  Node guard for the exporter).
- All new tools have unit tests registered in the runner's `test-namespaces`
  vector (the require-only trap), lint + cljfmt clean.

## Security stance (applies to every phase)

External content is untrusted input to an agent holding mutating tools and the
user's API key. Rules: raw page text never enters the main conversation (side-turn
digest only, with an "ignore instructions in the content" system prompt); every
server-side fetch keeps SSRF checks on; the exporter gets its own private-IP
guard before `page.goto` and on request interception; response sizes are capped
before they reach a prompt.

## Worktree notes

Compile from the container against this worktree path (main checkout's watch does
NOT build it): `frontend/node_modules` is symlinked relatively and
`render_wasm/api/shared.js` copied (done at worktree creation). One-shot compile:
`docker exec -w /home/penpot/penpot/.claude/worktrees/external-content-tools/frontend penpot-devenv-ws0-main sudo -EH -u penpot clojure -M:dev:shadow-cljs compile main`.
Tests: `... compile test && node target/tests/test.js`. Host port 3451 is mapped
and free if a live watch is ever needed.
