# Phase 02 — Image blocks in the codecs

**Status:** todo
**Blocks:** every other phase. Both halves of this plan need it.

## Goal

Teach the canonical message model and both provider codecs to carry images. Nothing user-facing
ships here — this is the foundation, and it is the only phase that touches the codecs, so it
should be reviewable on its own and covered by tests before anything depends on it.

## Before Start

- [ ] Confirm the metaprompt plan has landed — it edits the same file and would conflict
- [x] Re-read the canonical model docstring at `agent.cljs:28-39`
- [x] Re-read `user-content` (`agent.cljs:41-53`) — its docstring asserts the very thing this
      phase invalidates: *"Both providers take a plain string here, so one renderer serves
      both."* Update that comment; a stale contract note is worse than none
      → the claim is now stated where it is still **true** (the no-image fast path) and the
      divergence documented next to it
- [x] Re-read `encode-anthropic` (`:80-110`) and `encode-openai` (`:112-136`)

## Checklist

- [x] Write the codec tests first — extend `frontend/test/frontend_tests/data/agent_test.cljs`
      (`encode-anthropic` at `:71`, `encode-openai` at `:91`). Cover: no images (the string
      fast-path is unchanged), one image, five images, and image-without-text
      → **10 tests**, written before the implementation and **confirmed failing for the right
      reason** first (11 assertion failures, 0 errors — content was a plain string)
- [x] **Confirm the tests actually run.** `test/frontend_tests/runner.cljs` keeps a
      `(def test-namespaces [...])` vector separate from its `:require` list — a namespace that
      is only required compiles but never executes, and the suite goes green while the tests do
      not run. Grep the new test names in `node target/tests/test.js` output
      → **no runner change needed**: `frontend-tests.data.agent-test` was already in *both* the
      `:require` list (`:11`) and the vector (`:87`). All 10 names verified present in the output
- [x] Add `:images [{:mtype :data}]` to the canonical user message; update the model docstring at
      `:28-39`. Store **raw base64 + mimetype**, never a pre-assembled dialect shape
- [x] Fork `user-content` per provider (or split it in two). Keep the plain-string return when
      there are no images — the fast path is the common path and it keeps the diff honest
      → split three ways: `user-text` (shared) + `user-content-anthropic` / `user-content-openai`
- [x] Anthropic encoder (`:86`): `:content` becomes a block vector —
      `{:type "image" :source {:type "base64" :media_type … :data …}}`
- [x] OpenAI encoder (`:118`): `:content` becomes a block vector —
      `{:type "image_url" :image_url {:url "data:…;base64,…"}}`
- [x] Confirm `trim-history` (`:353-368`) still behaves — it cuts only at `:user` role and is
      image-agnostic, but verify rather than assume, since it is the one place a malformed
      history could silently drop blocks
      → verified by test (`trim-history-preserves-images`), not by reading. Untouched
- [x] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean; tests green
      → kondo 0/0 · cljfmt clean · `[:main] 1185 files, 0 warnings` · **457 tests, 1843
      assertions, 0 failures, 0 errors**
- [x] Human approval; commit `:sparkles: Carry image blocks through both agent codecs`

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] Note whether the two encoders diverged more than expected — if the fork is ugly, say so
      now; Phase 06 adds a third shape (images in tool results) on top of it
      → **less than expected.** The two `user-content-*` fns are structurally identical and
      differ only in the block they build (bare base64 + `media_type` vs. a `data:` URI). The
      shared parts — text rendering, the images-first ordering, the empty-text guard — all live
      in one place. **Not worth abstracting**: a single fn parameterised by dialect would hide
      the one line that actually differs, which is the line most likely to be wrong. Phase 06 can
      add a third caller without touching this.

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — canonical model, `user-content`, both
  encoders
- `frontend/test/frontend_tests/data/agent_test.cljs` — codec tests
- `frontend/test/frontend_tests/runner.cljs` — register the namespace **in the vector**, if new

## Notes

- No backend change. `ai_providers.clj` forwards the payload byte-for-byte and never decodes it
  (`:23-26`). Resist touching it.
- The response side is untouched: both accumulators (`:209-249`, `:251-283`) fold into `outcome`
  via `accumulator->outcome` (`:285-293`), and images only ever go **up**.
- `agent.cljs:107` — the Anthropic `tool_result` `:content` is block-capable too. That is the
  seam Phase 06 needs. Note it now; do not build it here.
