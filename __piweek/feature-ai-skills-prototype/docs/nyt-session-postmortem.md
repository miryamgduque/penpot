# Postmortem — NYT Cooking home screen (2026-07-16, New File 6) + context-window strategy

Session: chat `1f923df0…dd77c6`, Opus 4.8, one screen built to ok-ish fidelity.
Meter: **133 API rounds, 165 tool calls, ≈$3.91** — and the turn hard-aborted
TWICE mid-flight ("conversation too large to send, 5396k of 4000k").

## Where the money went (usage anatomy)

| bucket | tokens | ≈cost (Opus) | share |
|---|---|---|---|
| cache reads | 3,326,785 | $1.66 | 42% |
| output | 42,386 | $1.06 | 27% |
| cache writes | 154,675 | $0.97 | 25% |
| fresh input | 43,950 | $0.22 | 6% |

The caching layer is doing its job (94% hit rate). The bloat is structural:
**~25k tokens of context re-read on each of 133 rounds.** Total cost is
`rounds × prefix`, and both factors are inflated:

- **Rounds: 133 for one screen, at 1.24 tool calls per round.** The model
  worked almost strictly serially — 20 separate `create_token` rounds, a
  25-round "make 4 cards from 1" dance (duplicate ×3 → they escape the grid →
  re-nest ×3 → explore_design to re-find children → set_text ×6 → image swap
  ×3 → cleanup), a grid-cell-order fight it eventually solved by swapping card
  *content* instead of positions.
- **Prefix: ~46 tool specs + system prompt + growing history.** Cheap per
  round at 0.1×, expensive × 133.

## The two aborts: photographic renders, not the stock images

The stock photos themselves never enter the context (insert_image downloads
server-side; only ids come back). The reference screenshot is also cheap in
tokens (~2.4k) and already windowed by `prune-history-images`.

The killer is **`render_board` after real photos land on the canvas**. It has
NO byte budget (unlike screenshot_page's 1.6M cap): a scale-2 render of the
recipe grid with four photos is a multi-MB **photographic PNG**, whose base64
is 2–4M chars. The render rides the next send as the tool result — and the
4M-char RPC payload cap kills that send. Both aborts happened immediately
after `render_board ["recipe-grid"]` with photos in place. `history-chars`
deliberately ignores image bytes, so no hygiene mechanism ever saw it coming.

## Other findings

- **Grid cell ordering is a real tool gap**: `nest_shape` index changes the
  shapes vector but grid CELLS pin children, so reading order didn't change;
  the agent burned ~10 rounds before swapping content instead. The reflow
  path (`assign-cells` + `reorder-grid-children`) needs to honor the requested
  order on re-nest.
- **The settled-geometry return earned its keep**: the model used the
  settled-vs-requested drift to deduce that absolute layout children take
  PARENT-relative coordinates — 3 rounds instead of the Kahoot session's
  endless re-sends. Document that coordinate space in modify_shape outright
  and those 3 rounds also disappear.
- **picsum can't do "food"**: the model wanted recipe photos, got landscapes,
  and spent a full redo cycle (+ left tmp1–4 on the canvas when stopped).
  insert_image's description should offer a keyword-capable keyless source
  (e.g. `loremflickr.com/{w}/{h}/{keyword}`) beside picsum.
- The one playbook fetch (build-screen) happened, and the plan was good —
  the method wasn't the problem this time; mechanics were.

## The mystery "⏹ Stopped." markers

Two different stops share one message:

- **Segments 1 and 2 (72 and 57 tool calls) hit the `max-rounds` 32 hard cap.**
  When `step` exhausts the cap it returns `rx/empty` — the stream completes
  with no `:done` event, and the panel's fallback prints the same bare
  "⏹ Stopped." as a user cancel, with zero explanation. Resume works because
  the loop publishes `:turn-history` as it grows, so the fallback stores a
  valid history and a typed "continue" picks up cleanly — by accident of
  design rather than intent.
- **The last segment (9 calls) was a real user stop** (the stop button).

Disabling the 12-round spend checkpoint made this visible: turns now run to
32 rounds and end in the uninformative way instead of pausing at the styled
"Stopped at the checkpoint" + Continue row. Fix is small and reuses kept
machinery: at `max-rounds`, emit a `:checkpoint` event instead of `rx/empty` —
the user gets the Continue/Stop row (one click, fresh 32-round allowance)
and the marker can say *why* it stopped. Distinguish the user-stop marker
("⏹ Stopped.") from the cap ("⏹ Paused — round limit").

## Strategy: context-window management, ranked by leverage

### 1. Composition tools — collapse rounds (Santi's proposal; the big one)

Cost is `rounds × prefix`; nothing else moves it 5–10×. Concretely:

- **`build_tree`** — one declarative call creates a whole subtree: nested
  boards with layouts, texts, token bindings, fonts, in reading order.
  The whole chrome (status bar + header + logo) was ~35 calls; a tree is 1–2.
- **`clone_shape` with overrides** — duplicate INTO a parent at an index,
  with per-clone `{textByName, imageByName, name}` swaps. The 25-round card
  dance becomes 1 call ("clone card-sunday-sauce ×3 into recipe-grid with
  these titles/durations/images").
- **`create_tokens` (batch)** — the 20-round token bootstrap becomes 1–2
  calls. Same shape as apply_tokens' existing batch form.
- **`update_shapes` (batch modify)** — 23 modify_shape rounds, most of them
  fill-clearing and nudges, batchable.
- **Prompt nudge**: "issue independent tool calls together in one response" —
  the loop already executes parallel calls; the model just rarely offers them.

Projection for this session: 165 calls → ~25, rounds ~30, cache reads ~0.7M,
**cost ≈ $0.9, and the payload cap never approached**. The abstraction layer
also shrinks the failure surface: fewer round-trips = fewer places for async
settle and ordering to bite.

### 2. Image lifecycle (agnostic — no provider Files API)

- **Budget `render_board` like screenshot_page**: cap the base64; on
  overflow, auto-retry at scale 1, then refuse with "render a smaller shape".
- **JPEG for photographic renders**: wasm gives pixels; encoding
  `image/jpeg` q0.8 instead of PNG is ~10× smaller for photos and visually
  identical for self-review purposes. (Keep PNG below a size threshold —
  crisp text renders compress fine either way.)
- **Pre-send payload check that names the culprit**: we already fail with a
  size message; degrade instead — drop the oversized image from the outgoing
  round, replace with "[render too large — re-render a smaller area]", and
  send. The turn survives.

### 3. Playbooks "at first level" (Santi's proposal, made cache-compatible)

Inlining the matched playbook into the system prompt per task is impossible
without destroying the cached prefix, and inlining ALL bodies costs ~21k
tokens of prefix (≈$0.14/session at 0.1× — affordable, but it dilutes
attention and eats window on every unrelated task). The shape that works:

- **detect-round prefetch.** The existing detect side-turn already classifies
  the task; have it also name the matching skill, then **inject the body into
  the first user message's context block** — arrives WITH the task, paid
  once, cached thereafter by the history breakpoint, zero extra rounds, and
  no reliance on the model choosing to fetch. `get_design_skills` stays for
  references and mid-task pivots.

### 4. Model routing — Sonnet 5 is already in the catalog

`claude-sonnet-5` is in the picker today (curated catalog, vision, priced
$3/$15 vs Opus $5/$25). Two cheap experiments:

- Run the same brief on Sonnet 5 as the main model — with the improved tool
  boundary the tasks are more mechanical than clever, exactly where Sonnet
  shines; expect ~40–50% cost cut before any other change.
- Later: an orchestrator/builder split — Opus plans a section, a Sonnet/Haiku
  side-turn executes the build_tree/clone spec. Needs a write-capable
  side-turn (today's side turns are read-only by design), so this is after
  composition tools land — and they reduce the need for it.

### 5. Prefix diet (lowest priority)

46 tool specs are the biggest stable block. Measure first; if worth it,
tighten descriptions (carefully — most prose there is load-bearing
correctness teaching) or tier rare tools (comments/variants/pages) behind a
shorter index. At 0.1× per round the dollar win is small; the real win is
window headroom and attention.

## Suggested order

1. `create_tokens` batch + `clone_shape` overrides + `update_shapes` (small,
   high yield) → then `build_tree`.
2. render_board budget + JPEG + graceful payload degrade (kills the aborts).
3. detect-round playbook injection.
4. Sonnet 5 A/B on the same brief.
5. Grid re-nest ordering fix + insert_image keyword-source doc + absolute-child
   coordinate-space doc (small follow-ups from this session).
