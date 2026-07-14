# Phase 05 — Fit the budget

**Status:** todo
**Depends on:** Phase 04

## Goal

Make the 4M payload cap unreachable by normal use. Two separate leaks, one phase:

1. **On attach** — a modern phone photo is several MB before base64's 4/3 expansion. One photo
   can nearly fill the budget alone.
2. **Across history** — images accumulate. Every round re-encodes the *whole* conversation, so
   turn 10 still carries turn 1's screenshots. A conversation that worked fine at turn 3 fails at
   turn 12 with no new user action. **This is the leak that will actually bite**, and it is
   invisible until it isn't.

The cap is ours, not the provider's: `:payload [:string {:max 4000000}]`
(`ai_providers.clj:330`, `:378`). Overrun is an **RPC validation error**, so the user sees a
generic failure, not "your image is too big".

## Before Start

- [ ] Re-read Phase 04's recorded payload size for a 5-image message — the starting number
- [ ] Re-read `trim-history` (`agent.cljs:353-368`) and the prior art it echoes:
      `pruneStaleToolResults` stubbed tool results >400 chars older than the last 8 messages.
      The same shape applies to images, and reusing the existing idea beats inventing one
- [ ] Confirm the provider's own caps (Anthropic ~5 MB/image, ~8000px) and check they surface
      readably — `check-stream-status!` (`ai_providers.clj:380-400`) passes the provider's
      message through verbatim as `:ai-provider-error`

## Checklist

- [ ] Downscale on attach: cap the longest edge and re-encode via canvas.
      `webapi/create-blob-from-canvas` (`webapi.cljs:75-81`) already exists. Pick the cap from
      Phase 01/04's real numbers, not a round number that feels nice
- [ ] Measure what downscaling costs in **model comprehension**, not just bytes. An unreadable
      screenshot is worse than no screenshot — if the model can no longer read UI text in the
      image, the cap is too aggressive. Test with a real dense-UI screenshot
- [ ] Strip images from history beyond the last N turns, mirroring the `pruneStaleToolResults`
      pattern. Replace them with a text stub so the model knows an image *was* there — a silent
      disappearance invites confabulation about what it saw
- [ ] Decide N with evidence. Record the reasoning
- [ ] Guard the cap explicitly: if the assembled payload would exceed it, fail **before** the RPC
      with a message that names the actual problem
- [ ] Verify the pathological case end-to-end: 5 large photos, then 10 more turns. It must not
      break
- [ ] Extend the codec tests to cover history stripping
- [ ] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [ ] Human approval; commit `:sparkles: Keep agent image payloads inside the RPC budget`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] Note whether the 4M cap itself should be raised. It is one number in a schema and the
      layers above it allow 300M+ — but raising it is a **product/cost decision**, not a
      unilateral one. Record the recommendation; do not just change it

## Findings

| Case | Payload chars | Under cap? | Model still reads it? |
|---|---|---|---|
| 1 phone photo, raw | | | |
| 1 phone photo, downscaled | | | |
| 5 images, downscaled | | | |
| 5 images + 10 turns of history | | | |
| Dense-UI screenshot, downscaled | | | |

## Files

- `frontend/src/app/main/data/workspace/ai_panel.cljs` — downscale on attach
- `frontend/src/app/main/data/workspace/agent.cljs` — history image stripping, cap guard
- `frontend/test/frontend_tests/data/agent_test.cljs` — history tests

## Notes

- Interaction with the metaprompt plan's cache work: images sit in the **volatile** part of the
  payload, after the cached prefix. Stripping old images rewrites history mid-conversation —
  check the `% cached` meter before and after, because that plan's whole finding was that
  mutating the prefix collapses caching from ~95% to 0%. If image stripping perturbs the prefix,
  it costs far more than the bytes it saves.
- Only the Gitpod config would 413 before our own cap does (`docker/gitpod/files/nginx.conf:55`,
  `client_max_body_size 5M`). Not our environment; worth a note, not a fix.
