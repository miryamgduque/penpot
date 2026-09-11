# Phase 07 — screenshot_page tool

**Status:** done (live vision-model verify deferred to the post-merge testing pass)

## Goal

Frontend tool `screenshot_page {url, fullPage?}` riding the phase-06 exporter
cmd. The PNG returns to the model as a real image block via the `:images` result
key (the `render_board` path), sized to stay inside the 4M payload cap.

## Before Start

- [x] Verify plan is still valid; phase 06 merged (same branch)
- [x] Images contract re-read (`:images` lifted before JSON-stringify, dropped after one round by `strip-result-images`, text-only models get the omitted note)
- [x] **Simplification found:** no repo.cljs change needed — `cmd! :export` posts arbitrary transit params (the cmd dispatch rides the BODY) and `blob? true` already returns a Blob, so the tool calls `rp/cmd! :export {:cmd :screenshot-url …}` directly
- [x] Payload math: budget one screenshot at ≤1.6M base64 chars (40% of the 4M cap, history has room); viewport shots run ~100-500k, photographic full-page captures are what trip it

## Checklist

- [x] Tests (5): url validation, `data-url->b64` stripping, size guard pass/fail (fail names the fullPage fix), error translation table (blocked-host→private, unauthorized→session, unable-to-load→loaded, timeout/browser-not-ready→busy, fallback names the code); 831 tests 0 failures, confirmed in output
- [x] `screenshot_page {url, fullPage?}` spec — notes one-round image visibility and the fetch_page/text vs screenshot/visual split
- [x] Handler: `rp/cmd! :export {:cmd :screenshot-url :blob? true}` → `wapi/read-file-as-data-url` → base64 → size guard → `{:images [{:mtype "image/png" :data b64}] :url :fullPage :note}`
- [x] Exporter error codes surface as friendly one-liners; unexpected errors pass through unwrapped
- [x] Wired into `execute-tool` dispatch
- [x] clj-kondo 0/0, cljfmt clean, `main` build 0 warnings
- [ ] Live verify — DEFERRED to the post-merge testing pass: console-drive the tool, one real Claude vision turn describing a screenshotted page, round-2 image drop (meter/cached% sane), blocked-host and no-auth error paths
- [x] Human approval: Santi pre-approved phase-by-phase commits for this plan (2026-07-16)
- [x] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec + handler + dispatch (repo.cljs untouched — see Before Start)
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — tests

## Notes

- Image token cost ≈ `⌈w/28⌉ × ⌈h/28⌉` — a 1280×800 shot ≈ 1.3k tokens, a
  2400px full-page clip ≈ 4k; the payload cap is the binding constraint.
- "Recreate this site" / moodboard flows are prompts over this tool + existing
  shape tools — the phase-08 skill and future quick-starts compose it.
