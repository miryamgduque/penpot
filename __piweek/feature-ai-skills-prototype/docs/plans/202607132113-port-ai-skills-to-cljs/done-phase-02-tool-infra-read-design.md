# Phase 02 — Tool-call infrastructure + `read_design`

**Status:** done

## Goal

Add the tool-call machinery to `run-turn` (declare tools, execute them, feed results back, loop) and ship the first native tool, `read_design` — the read-only orientation call. Proves the tool loop before any mutating tools.

## Before Start

- [ ] Re-read the tool loop in `agent.ts` (`:188-209` `executeTool`, `:538-562` the per-call execution + `tool_results` feedback, statuses running/ok/rejected/error)
- [ ] Re-read `read_design` shape (`agent.ts:104-108`) and what the plugin's `read-design` composed (context + tokens + skills + open-violation count — `plugin.ts:775-780`)
- [ ] Confirm the read refs: `refs/workspace-page-objects` (`refs.cljs:313`), `refs/selected-shapes` (`:179`), top-level children = `(:shapes (get objects uuid/zero))`, `refs/workspace-frames` (`:369`), and `dsh/lookup-file-data` for tokens (`:tokens-lib`)

## Checklist

- [x] Tool declarations moved to a new `agent-tools.cljs` (`tool-specs`, a vector of `{:name :description :input-schema}`); `agent.cljs` has `anthropic-tools`/`openai-tools` projections feeding `build-round-body`. ✓
- [x] Tool dispatch: `agent-tools/execute-tool` (a `case` on name) returns an **rx observable** of the result (or an observable that errors with a `{:rule …}`-tagged ex-info) — uniform for sync reads and async mutations later. ✓
- [x] `run-turn` (replaces `run-round`): recursive rx loop (≤ `max-rounds`) that decodes each round, runs tool calls via `execute-tool`, collects `tool_results` (capped ~20k chars), feeds them back, and recurses. Emits **turn events** (`{:kind :assistant|:tool|:done}`); the panel's `send-message` maps them to `append-message`/`append-tool`/`store-history`. Canonical history (with tool_use/tool_result) is threaded across turns via `[:ai-panel file-id :history]`. ✓
- [x] `read_design`: current file/page name, selection (id/name/type), top-level shapes (id/name/type/x/y/w/h). **Seams** (empty for now, filled by their phases): `:colorTokens` (Phase 05), `:skills` (Phase 07), `:openViolations` (Phase 08). *(Deviation: tokens deferred to Phase 05 rather than included here, to keep this phase about the loop + orientation.)* ✓
- [x] Tool chips render in the Chat tab (✓/✕ glyph + monospace name; rule/detail on error). ✓
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, `stylelint` clean, `shadow-cljs compile main` → **0 warnings** (1180 files). ✓
- [x] Preview (live devenv, Haiku): "Use your read_design tool… what shapes are on this page?" → **✓ read_design** chip → grounded reply *"Page 1 contains one shape: Rectangle, x:241 y:399, 240×173"* (matches the canvas). Multi-round loop confirmed. ✓
- [ ] Human approval; commit `feat(workspace): agent tool loop and native read_design`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; note the tool-registry shape (multimethod vs map) so later phases extend it consistently

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — tool declarations, dispatch, `read_design`, loop extension
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — tool-chip rendering in the transcript

## Notes

- Keep the tool JSON-schemas as CLJS data literals; `read_design` takes no input.
- `read_design`'s skills + violation-count fields are seams filled by Phases 07 (skills) and 08 (audit) — leave them as explicit TODO keys so those phases are drop-in.
