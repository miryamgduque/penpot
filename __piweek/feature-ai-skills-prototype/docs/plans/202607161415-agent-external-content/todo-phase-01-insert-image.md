# Phase 01 — insert_image tool

**Status:** todo

## Goal

One new agent tool `insert_image` that fetches an image by URL (server-side, SSRF
already guarded) and places it as an image shape. Placeholder recipes live in the
tool description so the model composes URLs itself — no per-service code.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `frontend/src/app/main/data/workspace/media.cljs:109-116` (`upload-media-url`) and `:250-282` (`process-media-objects`) — confirm the `:on-image` handler and which event actually creates the image shape (follow it to see the shape attrs: `:type :image` metadata vs fill-image)
- [ ] Confirm the tool-result contract: handlers return an rx observable (`agent_tools.cljs:13`) — the handler must chain RPC completion → shape creation → result map, not fire-and-forget like `apply_tokens`
- [ ] Smoke-test the three placeholder services against `media/download-image` constraints (content-length header required, ≤3 redirects, media-type validation — `backend/src/app/rpc/commands/media.clj:336-341`): `https://picsum.photos/600/400` (redirects to fastly), `https://placehold.co/600x400/EEE/31343C.png` (default is SVG — must force `.png`), `https://api.dicebear.com/9.x/lorelei/png?seed=x`

## Checklist

- [ ] Tests in `frontend/test/frontend_tests/data/agent_tools_test.cljs`: schema/validation (bad URL, missing params), result shape; confirm the ns is in `runner.cljs` `test-namespaces` AND its tests appear in `node target/tests/test.js` output
- [ ] Add `insert_image` spec to `tool-specs`: `{url, name?, x?, y?, width?, height?, board-id?}`; description documents the three keyless recipes (photos: `picsum.photos/W/H`, blocks: `placehold.co/WxH/BG/FG.png?text=`, avatars: `api.dicebear.com/9.x/<style>/png?seed=`) and states that arbitrary http(s) URLs are allowed but private hosts are rejected server-side
- [ ] Handler: `rp/cmd! :create-file-media-object-from-url` → create the image shape at position via the changes pipeline (reuse whatever `:on-image` emits; `cb/empty-changes`+`add-object`+`dch/commit-changes` pattern like `create_shape` `agent_tools.cljs:1240-1265` if more direct) → return `{:shape-id :media-id :width :height}`
- [ ] Wire into the `execute-tool` case dispatch (`agent_tools.cljs:2894-2928`)
- [ ] Friendly error mapping: SSRF rejection (`:ssrf-blocked-target`), missing content-length (`:unknown-size`), bad media type — each becomes a one-line tool error naming the fix
- [ ] Lint + cljfmt pass (in container: `pnpm run lint:clj`, `/opt/utils/bin/cljfmt`)
- [ ] Live verify in devenv: drive via console `at.execute_tool("insert_image", m)` (no LLM needed) with each of the three services
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec + handler + dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — tests

## Notes

- SSRF is free here: the download happens in `media/download-image` which keeps
  the check on per hop. Do not add a frontend fetch of the image — the browser
  never touches the external host.
- `width`/`height` params resize the created shape after the media's intrinsic
  dims are known; if omitted, use intrinsic size.
