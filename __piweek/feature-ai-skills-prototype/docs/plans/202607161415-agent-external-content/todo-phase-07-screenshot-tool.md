# Phase 07 — screenshot_page tool

**Status:** todo

## Goal

Frontend tool `screenshot_page {url, full-page?}` riding the phase-06 exporter
cmd. The PNG returns to the model as a real image block via the `:images` result
key (the `render_board` path), sized to stay inside the 4M payload cap.

## Before Start

- [ ] Verify plan is still valid; phase 06 cmd answers through nginx
- [ ] Re-read the images contract: `:images` lifted before JSON-stringify (`agent_tools.cljs:1074-1076`, `agent.cljs:789-800`), dropped after one round by `strip-result-images` (`agent.cljs:96-134`), text-only models get an omitted note
- [ ] Re-read the exporter call pattern in `frontend/src/app/main/repo.cljs:265-280` (`send-export` → `public-uri + "api/export"`, credentials) — decide: extend `cmd! :export` or add a sibling `cmd! :screenshot-url`
- [ ] Re-check payload math: base64 chars ≈ bytes×4/3; budget ≤ ~1.5M chars per screenshot so a screenshot + history fits under the 4M cap with room (history compaction triggers at 100k chars but images bypass that path)

## Checklist

- [ ] Tests: input validation, base64 conversion, size-guard rejection path; runner registration confirmed
- [ ] Repo helper for the exporter call (arraybuffer response → base64)
- [ ] `screenshot_page {url, full-page?}` spec — description notes: one screenshot per call, image visible for one round only (re-call to look again), private hosts rejected
- [ ] Handler: call exporter cmd → base64 → if the encoded size exceeds the budget, fail with a clear error suggesting `full-page: false` (v1: no client-side downscale; the exporter's viewport + height clip should keep normal pages ≤~500kB) → return `{:images [{:mtype "image/png" :data b64}] :width :height :url}`
- [ ] Friendly error mapping: exporter 4xx (blocked host), timeout, non-2xx
- [ ] Wire into `execute-tool` dispatch
- [ ] Lint + cljfmt pass
- [ ] Live verify: console-drive the tool, then one real LLM turn on a vision model (Claude) describing a screenshotted page; confirm round 2 drops the image (meter/cached% sane)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec + handler + dispatch
- `frontend/src/app/main/repo.cljs` — exporter call helper
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — tests

## Notes

- Image token cost is `⌈w/28⌉ × ⌈h/28⌉` — a 1280×800 shot ≈ 1.3k tokens; full-page
  (2400px clip) ≈ 4k. Cheap enough; the payload cap is the binding constraint,
  not tokens.
- "Recreate this site" / moodboard flows are prompts over this tool + existing
  shape tools — no extra code; consider a quick-start prompt later, not here.
