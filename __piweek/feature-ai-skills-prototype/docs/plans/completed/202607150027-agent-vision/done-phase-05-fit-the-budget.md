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

> **⚠️ Phase 04 measured it, and it is worse than this file assumed.** Five 1440×900 UI
> screenshots are **2.65M base64 chars — 66% of the 4M cap in a single message**, before the
> system prompt, tool schemas, or any history. Incompressible content (a photo, a photographic
> mockup) is **743% — over the cap on the first message**. So the ordering below is wrong:
> **downscale-on-attach is the load-bearing fix and should come first**; history accumulation is
> real but arrives second. Five images is not a stress test — it is what the button invites.

## Before Start

- [x] Re-read Phase 04's recorded payload size for a 5-image message — the starting number
      → 2,649,360 chars (66% of cap) realistic; 29,732,536 (743%) pathological
- [x] Re-read `trim-history` (`agent.cljs:353-368`) and the prior art it echoes:
      `pruneStaleToolResults` stubbed tool results >400 chars older than the last 8 messages.
      The same shape applies to images, and reusing the existing idea beats inventing one
- [x] Confirm the provider's own caps (Anthropic ~5 MB/image, ~8000px) and check they surface
      readably — `check-stream-status!` (`ai_providers.clj:380-400`) passes the provider's
      message through verbatim as `:ai-provider-error`

## Checklist

- [x] Downscale on attach: cap the longest edge and re-encode via canvas.
      `webapi/create-blob-from-canvas` (`webapi.cljs:75-81`) already exists. Pick the cap from
      Phase 01/04's real numbers, not a round number that feels nice
- [x] Measure what downscaling costs in **model comprehension**, not just bytes. An unreadable
      screenshot is worse than no screenshot — if the model can no longer read UI text in the
      image, the cap is too aggressive. Test with a real dense-UI screenshot
- [x] Strip images from history beyond the last N turns, mirroring the `pruneStaleToolResults`
      pattern. Replace them with a text stub so the model knows an image *was* there — a silent
      disappearance invites confabulation about what it saw
- [x] Decide N with evidence. Record the reasoning
- [x] Guard the cap explicitly: if the assembled payload would exceed it, fail **before** the RPC
      with a message that names the actual problem
- [x] Verify the pathological case end-to-end: 5 large photos, then 10 more turns. It must not
      break
- [x] Extend the codec tests to cover history stripping
- [x] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [x] Human approval; commit `:sparkles: Keep agent image payloads inside the RPC budget`

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] Note whether the 4M cap itself should be raised. It is one number in a schema and the
      layers above it allow 300M+ — but raising it is a **product/cost decision**, not a
      unilateral one. Record the recommendation; do not just change it
      → **Recommendation: leave it at 4M.** It was never the problem. With downscale + pruning
      the worst realistic case sits at 60% and is *bounded*, so raising the cap would buy nothing
      except a slower, more expensive failure further out — the payload would still have to be
      paid for on every round. The cap is doing useful work as a backstop; the fix belonged on
      our side of it. Revisit only if a real use case needs images the 1568px cap ruins.

## Findings

All measured live in the devenv against the real code path (a real Claude turn for the
comprehension test), not estimated.

### The two fixes, and what each one buys

| Case | base64 chars | % of 4M cap | Verdict |
|---|---|---|---|
| **1 phone photo (12.2MP), raw** | 21,761,086 | **544%** | one photo blew the cap 5× over |
| 1 phone photo → capped to 1568px + WebP | 240,928 | 6% | **90× smaller** |
| 5 dense UI screenshots, raw PNG | 1,770,375 | 44% | (Phase 04 measured 66% for larger ones) |
| 5 dense UI screenshots → WebP | 720,040 | **18%** | 2.5× smaller |
| **12 turns × 5 photos, no pruning** | 14,461,997 | **362%** | conversation dies with no user action |
| 12 turns × 5 photos, pruned | 2,411,957 | **60%** | 6× smaller, and **bounded** |

**The property that matters is the last one: it stops growing.** Same worst case, measured
across conversation lengths:

| turns | 2 | 5 | 12 | 30 | 60 |
|---|---|---|---|---|---|
| % of cap | 60.3 | 60.3 | 60.3 | 60.4 | 60.5 |

Flat. The 0.2% drift is the omission notes accumulating, not images. A conversation cannot walk
into the cap any more — which was the whole failure mode ("worked at turn 3, dead at turn 12").

Note that 60% is the *absolute pathological* case: five 12MP **phone photos on every single
turn**. Realistic use (five screenshots, occasionally) sits near 18%.

### ✅ Downscaling costs nothing in comprehension — measured, not assumed

The risk was over-compressing into an unreadable screenshot, which would be a worse failure than
the one being fixed. Rendered a 1440×900 UI screenshot with **11px monospace** text, ran it
through the real attach path (WebP q0.85), and asked Claude to read it back:

> **Large text in purple rectangle:** READ-THIS-9184
> **Three smallest text lines:** 1. padding 0  2. margin 4  3. flex-start 8

All verbatim and correctly positioned, at 11px, after compression. **WebP at 0.85 is free.**

### Why these constants

- **`max-image-edge` 1568** — not a round number chosen for feel: it is Anthropic's
  standard-tier resolution limit, above which the provider downscales server-side *anyway*, so
  a larger upload buys nothing but bytes. (High-res tier is 2576, but the payload budget binds
  first and 1568 proved ample.) It is also what turns a 4032px phone photo into 1568px.
- **WebP q0.85 over JPEG** — holds flat UI colour and text edges better at the same size, and
  text legibility is the one thing not tradeable. Verified above.
- **The cap alone would not have worked.** A 1440×900 screenshot is *already* under 1568, so the
  cap does nothing for it — the re-encode is what pays (2.5×). Conversely the re-encode alone
  would not have saved the phone photo. **Both are needed, for different inputs.**
- **`max-image-turns` 2** — an image is the subject of the turn it arrives in and usually of one
  follow-up ("match this" → "now warm the palette"). Past that it is re-uploaded every round for
  nothing. 2 costs 60% in the pathological case vs ~30% for 1; 1 felt likely to break the
  ordinary "and now change the colour" follow-up, which is exactly the interaction this feature
  is for. **Turns, not messages**: a turn can be twenty tool-call messages, and counting messages
  would strip the image out of the very turn asking about it (there is a test for this).

### The cap guard, and why the build had to be deferred

The guard throws with an actionable message rather than letting the RPC schema reject it:

> ⚠️ This conversation is too large to send (4210k of a 4000k limit). Remove an image, or clear
> the chat and start again.

Verified end-to-end by poisoning the stored history with an oversized image: the message lands
in the transcript and the panel does **not** stick busy.

That only works because `stream-round` now builds the body **inside** the stream. Round 1 is
constructed eagerly inside `send-message`'s watch, so a throw from `build-round-body` would have
escaped past the caller's `rx/catch` and surfaced as an unhandled error instead of a bubble.
This was a real trap, not a hypothetical — the guard would have "worked" in a unit test and
failed the user.

### The cache question, answered by construction

The Notes below worried that pruning rewrites history mid-conversation and could collapse the
`% cached` meter the way the metaprompt plan's selection bug did. **It cannot.** The only
`cache_control` marker in the payload is on the **system** block (`agent.cljs:253`), and tools
render ahead of it — so the cached prefix is *tools + system*, and `messages` is never cached.
Pruning touches only `messages`. This is a structural answer rather than a measured one, which
is the honest way round: the meter currently reports 0% cached on both paths regardless (a known
open follow-up from the metaprompt plan, not caused here).

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
