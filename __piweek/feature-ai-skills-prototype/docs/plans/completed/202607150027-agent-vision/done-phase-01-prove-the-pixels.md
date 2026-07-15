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

- [x] Check the devenv's flag state: is `:wasm-export` in `cf/flags`, and is the
      `render-wasm/v1` feature active on the demo file? **Record the actual values** — do not
      assume
- [x] From the browser console, call `render-shape-pixels` on a real board in the Skills Demo
      file. Confirm a `Uint8Array` comes back and that the bytes are a valid PNG (magic number
      `89 50 4E 47`)
- [x] Save one output to disk and **open it** — confirm it is the board, correctly rendered, and
      not blank/transparent/cropped
- [x] Verify it works on a board that is **scrolled off-screen** — this is the claim that
      separates a real export from a viewport capture
- [x] Measure: bytes and wall-clock for a small board, a large board, and a whole page. Record
      them in Findings — these numbers set Phase 05's budget and Phase 07's cost story
- [x] Measure main-thread block time for the largest case (the call is synchronous through a
      full Skia render + PNG encode)
- [x] **If the flags are off:** try the fallback — `render/render-frame` →
      `app.main.rasterizer/render`. Record whether it works and what it costs
      → **not needed.** `?wasm=true` reaches the primary path; fallback left unexplored on
      purpose (see *The renderer problem*, which is a product question, not a technical one)
- [x] Write the verdict below: **primary path**, fallback, and any blocker

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] If the verdict is "not reachable", say so plainly in the README and mark Phases 06–07
      deferred with the reason. Phases 02–05 (attachments) are **unaffected** and still proceed
      → **not needed: the verdict is "reachable".** Phases 02–05 proceed regardless
- [ ] If the verdict changes the approach, update Phase 06 before it starts
      → **it does, twice** (the renderer problem; no full-page render). **Blocked on a product
      decision from the user** — see the blocker above. Do not edit Phase 06 until that is
      answered, or the edit will encode a guess

## Files

None — spike only. Console snippets may live in the scratchpad; do not commit them.

## Findings

**Run:** 2026-07-15, devenv at `:3450`, file "New File 1" (52 shapes, 14 boards, text + rects).
Measured via `app.render_wasm.api/render_shape_pixels` from the console. Read-only — no file
data was mutated.

| Measure | Small flat board<br>`Card` 400×300, 1 kid | Text board<br>`info-card` 320×140, 2 kids | Full page<br>(root frame) |
|---|---|---|---|
| PNG bytes | 1 181 | 6 271 | **84 — useless** |
| PNG dimensions | 400×300 ✅ exact | 320×140 ✅ exact | **1×1** ❌ |
| Wall-clock (ms) | 5.5–32.7 | 4.2–66.9 | 9.2 (meaningless) |
| Main-thread block | = wall-clock (synchronous) | = wall-clock | — |
| Distinct colours | 2 (flat fill) | 352 (antialiased text) | — |

**First call costs 160 ms (warm-up); every subsequent call is 4–40 ms.** Budget for one slow
render per session, not per call.

**Scale sweep** (`info-card`, the text-rich board):

| Scale | Dimensions | Bytes | ms |
|---|---|---|---|
| 1 | 320×140 | 6 271 | 58.1 |
| 2 | 640×280 | 15 073 | 23.0 |
| 4 | 1280×560 | 33 044 | 36.9 |
| 8 | 2560×1120 | 71 420 | 111.8 |

**Flag state (measured live, not assumed):**
- `:wasm-export` = **false**. The devenv sets no `PENPOT_FLAGS` at all and `penpotFlags` is
  `undefined` in the browser, so the frontend runs on `flags/default` alone — and `:wasm-export`
  is not in it. **It is not in `all-flags` either**: it is referenced in four frontend files and
  declared nowhere. `parse` does not validate against `all-flags`, so `enable-wasm-export` would
  still work — it is undeclared, not forbidden.
- `render-wasm/v1` = **false by default**, forced **true** with `?wasm=true`.

**Off-screen board renders correctly:** ✅ **Proven byte-identical.** Rendered `info-card`
(at 1300,900) while visible → 6271 B, FNV hash `95a05ea0`. Zoomed to 12× on a different board so
the viewport became a 33×41 px window at (1043,1640) — nowhere near it — and re-rendered:
6271 B, hash `95a05ea0`. **Identical.** It is a true export off a dedicated surface, not a
viewport capture.

**Renders are correct and text is legible:** verified visually, not just statistically. Four
boards rendered at scale 2 and displayed: "Card heading", "One line of supporting body text for
this card.", "Card Title", "Description text goes here", "Additional content" all render crisply,
with correct fills and geometry. **The agent will be able to read its own output.**

**Verdict:** ✅ **WORKS — better than the plan assumed.** Fast, cheap, accurate, legible.
**Primary path:** `wasm.api/render-shape-pixels(shape-id, scale)` → `Uint8Array` of PNG bytes.
**Fallback needed:** No — but see the blocker below, which no fallback solves.

### 🚩 Blocker for Phase 06 — the renderer problem

**`render_board` can only work for users on the WASM renderer, and that is not the default.**

`:wasm-export` turned out to be a red herring: it gates whether *Penpot's export feature* picks
the WASM path — a product policy gate, not a technical precondition. The real precondition is
`render-wasm/v1`, and it is **off by default**: `:render-switch` ships enabled
(`flags.cljc` `default`), so the renderer comes from the profile prop
`[:profile :props :renderer]`, which **defaults to `:svg`** (`features.cljs:36`).

The failure mode is confirmed, and it is not graceful. With `render-wasm/v1` inactive:

```
#error {:message "WASM Error (wasm-critical)"
        :data {:fn "_render_shape_pixels" :type :wasm-error :code :wasm-critical}
        :cause RuntimeError: Aborted(Assertion failed: Exception thrown, but exception
               catching is not enabled...)}
```

Two mitigating facts, both verified:
- **It is catchable.** It surfaces as a normal CLJS error; a `try/catch` contains it.
- **The blast radius is contained.** After the abort the app was fully alive — store healthy,
  52 objects intact, SVG viewport still rendering boards normally (screenshotted). This makes
  sense: when render-wasm is inactive the WASM module is not driving the viewport, so aborting
  it harms nothing else. It is *repeatable*, not a one-way page kill.

So a guard on `(features/active-feature? state "render-wasm/v1")` is both **necessary and
sufficient**. But guarding only converts a crash into "this tool does not work for you" — which
is a **product decision Phase 06 cannot make on its own**:

1. Offer `render_board` only to WASM-renderer users, and degrade for everyone else; or
2. Have the agent ask the user to switch renderers; or
3. Build the SVG fallback (`render/render-frame` → `app.main.rasterizer/render`) so vision works
   for everyone — the real cost of universal support, and the reason the fallback is worth
   pricing even though the primary path works.

**Raise this with the user before Phase 06 starts.**

### 🚩 There is no "full page" render

The root frame's selrect is **0.01 × 0.01** despite having 22 top-level children, so
`render-shape-pixels` on it returns an **84-byte 1×1 PNG** — silently, with no error. "Render
the whole page" is not available at any price from this API.

This invalidates the plan's own "Full page" measurement column and constrains Phases 06–07:
the agent renders **specific boards**, never "the page". Compositing top-level boards into one
image is possible but is real work nobody has scoped. Phase 07's "re-feed the affected region"
should read **"re-feed the affected board"**.

### Cost is a non-issue

A text-rich board is 6 KB at scale 1 and 15 KB at scale 2 (≈20 KB base64). Against the 4 M-char
payload cap that is **~0.5%** — three orders of magnitude from trouble. The Phase 07 worry that
"an image is worth a lot of tokens" is about the *provider's* token accounting, not our payload
budget; those are separate concerns and only the former is still open.

## Notes

### How to reproduce this spike

- Devenv at `:3450`, already-authenticated browser session. Open a file with
  **`&wasm=true`** appended to the workspace URL — that is the whole trick.
  `features.cljs:38-45` gives the `?wasm` query param **priority 1**, above the profile
  preference and the team feature set, so it is the cheapest way to force `render-wasm/v1` on
  without touching a profile.
- State accessors moved: there is **no `:workspace-data` key** in the store any more. Use
  `app.main.data.helpers/lookup-page-objects(state)`. Reaching for the old path returns an
  empty map and reads as "the file is empty" — a lie that cost time here.
- `zoom-to-selected-shape` and `reset-zoom` are **ptk event values, not functions** — `emit!`
  them directly. `select-shape`/`set-zoom` *are* functions. Guessing wrong throws
  "is not a function".
- Shape `:type` is a keyword, so from JS `String(type)` is `":frame"`, **not** `"frame"`.
  Filtering on `"frame"` silently yields zero boards in a file with fourteen.

### For whoever picks up Phase 06

- The tool's guard is `(features/active-feature? state "render-wasm/v1")` — **not**
  `:wasm-export`. Copying `wasm-export-enabled?` from `assets.cljs` would inherit a policy gate
  that is off by default and undeclared, and the tool would be dead for everyone.
- Skip the blob-URI round-trip the plugin API does at `plugins/shape.cljs:1524`; it exists only
  to satisfy a JS promise contract native CLJS does not have. `render-shape-pixels` hands back a
  `Uint8Array` directly — which is exactly what an image content block needs.
- Cap the scale. Scale 8 is a 112 ms synchronous main-thread block for a *small* board, and it
  buys nothing: text is already legible at scale 2 (verified visually). **Scale 2 is the sweet
  spot** — legible text, 15 KB, 23 ms.
- The console is enough here. Resist building a tool "while we're in there" — that is Phase 06,
  and it should be designed with these numbers in hand.
- Precedents to copy rather than invent: `frontend/src/app/plugins/shape.cljs:1513-1577`
  (`shape.export()`) and `clipboard.cljs:1174-1177`. Note the plugin fetches its own blob URI
  over `http/send!` purely to satisfy a JS promise contract — **native CLJS should skip that
  round-trip entirely**.
- Do **not** go near `rp/cmd! :export`. That path requires the headless-browser `exporter`
  service plus a `force-persist-and-wait` round-trip. It is the expensive answer this phase
  exists to avoid.
