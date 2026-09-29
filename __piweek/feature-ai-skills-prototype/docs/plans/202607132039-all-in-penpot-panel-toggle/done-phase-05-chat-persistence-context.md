# Phase 05 — Chat content persistence & context awareness

**Status:** done

## Goal

The Chat tab renders a real, per-file transcript that **survives navigation** (page/board, dashboard round-trip) exactly like the open state, and the panel **surfaces the current page and current selection** as context. Hard-refresh survival of the transcript is explicitly **out of scope** (US #5) — in-memory per-file storage is correct here.

## Before Start

- [ ] Confirm the per-file state store from Phase 04 (`[:ai-panel file-id ...]`) is the home for the transcript too (`[:ai-panel file-id :messages]`)
- [ ] Re-read the context refs: `refs/workspace-page` (refs.cljs:299) and `refs/selected-shapes` (refs.cljs:179) — both already derefed in `workspace-content*` (workspace.cljs:59)
- [ ] Re-read `:ai-agent-round` contract (backend ai_providers.clj:308-330) and `refs/ai-providers` (refs.cljs:665) in case the live round is attempted

## Checklist

- [x] Added transcript state `[:ai-panel file-id :messages]` (vector of `{:role :content}`), in-memory only, in `data/workspace/ai_panel.cljs`; event `append-message`; ref `refs/ai-panel-messages`. ✓
- [x] Chat tab renders the transcript (user bubbles, right-aligned) and a working composer (Enter to send, Shift+Enter newline) that appends the user message and clears the input. ✓
- [x] Context chip: **current page name** + **selection** summary ("No selection" / "1 layer: <name>" / "N layers selected"), derived from `refs/workspace-page` + `refs/selected-shapes` + `refs/workspace-page-objects`, updating live. ✓
- [~] **Stretch (deferred, as planned):** the live `:ai-agent-round` turn is **not** wired here — it's the porting plan's Phase 01 (which replaces the composer's append-only submit with `run-turn`). The transcript store + rendering + context chip are the reusable foundation it builds on.
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, `stylelint` clean, `shadow-cljs compile main` → **0 warnings**. ✓
- [x] Preview review (live devenv):
  - typed message → switched **Page 2 → Page 1** → transcript intact ✓
  - hard refresh → transcript empty + panel closed (expected; US #5 owns durable chat) ✓
  - selected a rectangle / changed page → context chip updated live to "Page 1 · 1 layer: Rectangle" ✓
  - **bug found + fixed:** `refs/selected-shapes` returns a set of ids (not shape maps) — resolve names via `refs/workspace-page-objects`. ✓
- [ ] Human approval received
- [ ] Commit: `feat(workspace): per-file chat transcript and page/selection context`

## After Finish

- [ ] Rename `todo-` → `done-`, update README link
- [ ] Note whether the live `:ai-agent-round` round was wired or deferred, and any US #5 hand-off in Notes

## Files

- `frontend/src/app/main/data/workspace/ai_panel.cljs` — transcript state + `append-message`
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — transcript render, composer, context chip
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — message/composer/context styles

## Notes

- Keeping the transcript in the same per-file in-memory store as the open state means both survive navigation and both reset on refresh — coherent and matches the story's split (open state resilient across refresh? no; chat across refresh? deferred to US #5).
- The context-awareness ask ("raise awareness of current page and component selection") is surfaced here as a live context chip; deeper injection into the agent prompt is a natural US #1/#5 follow-up.
