# Phase 01 — Prove the pixels

**Status:** todo
**Timebox:** half a day. If it runs longer, stop and record the verdict as "deferred" — that is a
legitimate outcome, not a failure.

## Goal

Answer the user's literal question — *can the agent take a screenshot?* — before building
anything that assumes the answer is yes. Exploration says the call exists and is cheap; this
phase proves it **runs in our devenv, on a real board, and produces bytes a model can read**.

This is a spike, not a feature. No tool, no UI, no commit of production code. The deliverable is
a **verdict written into this file**.

## Before Start

- [ ] Confirm the metaprompt plan has landed (this plan depends on it)
- [ ] Re-read `frontend/src/app/main/data/exports/wasm.cljs:14-19` — the whole path is 5 lines
- [ ] Re-read `frontend/src/app/main/data/exports/assets.cljs:166-181` — the flag fork and the
      docstring warning that a WASM render **crashes** when render-wasm is inactive

## Checklist

- [ ] Check the devenv's flag state: is `:wasm-export` in `cf/flags`, and is the
      `render-wasm/v1` feature active on the demo file? **Record the actual values** — do not
      assume
- [ ] From the browser console, call `render-shape-pixels` on a real board in the Skills Demo
      file. Confirm a `Uint8Array` comes back and that the bytes are a valid PNG (magic number
      `89 50 4E 47`)
- [ ] Save one output to disk and **open it** — confirm it is the board, correctly rendered, and
      not blank/transparent/cropped
- [ ] Verify it works on a board that is **scrolled off-screen** — this is the claim that
      separates a real export from a viewport capture
- [ ] Measure: bytes and wall-clock for a small board, a large board, and a whole page. Record
      them in Findings — these numbers set Phase 05's budget and Phase 07's cost story
- [ ] Measure main-thread block time for the largest case (the call is synchronous through a
      full Skia render + PNG encode)
- [ ] **If the flags are off:** try the fallback — `render/render-frame` →
      `app.main.rasterizer/render`. Record whether it works and what it costs
- [ ] Write the verdict below: **primary path**, fallback, and any blocker

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] If the verdict is "not reachable", say so plainly in the README and mark Phases 06–07
      deferred with the reason. Phases 02–05 (attachments) are **unaffected** and still proceed
- [ ] If the verdict changes the approach, update Phase 06 before it starts

## Files

None — spike only. Console snippets may live in the scratchpad; do not commit them.

## Findings

| Measure | Small board | Large board | Full page |
|---|---|---|---|
| PNG bytes | | | |
| Wall-clock (ms) | | | |
| Main-thread block (ms) | | | |

**Flag state:** `:wasm-export` = ? · `render-wasm/v1` = ?
**Off-screen board renders correctly:** ?
**Verdict:** ?
**Primary path:** ?
**Fallback needed:** ?

## Notes

- The console is enough here. Resist building a tool "while we're in there" — that is Phase 06,
  and it should be designed with these numbers in hand.
- Precedents to copy rather than invent: `frontend/src/app/plugins/shape.cljs:1513-1577`
  (`shape.export()`) and `clipboard.cljs:1174-1177`. Note the plugin fetches its own blob URI
  over `http/send!` purely to satisfy a JS promise contract — **native CLJS should skip that
  round-trip entirely**.
- Do **not** go near `rp/cmd! :export`. That path requires the headless-browser `exporter`
  service plus a `force-persist-and-wait` round-trip. It is the expensive answer this phase
  exists to avoid.
