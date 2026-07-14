# Phase 04 — File-bound open/close persistence

**Status:** done

## Goal

Move the panel's open/closed state out of the global layout flag and into a **per-file, in-memory** store so it: survives page/board navigation, survives leaving to the dashboard and returning (SPA, no reload), stays **bound to the file** (file A's open state is independent of file B's), and **resets to closed on a hard browser refresh**. The button and shortcut from Phase 02 are rebound to this store; their public API is unchanged.

## Before Start

- [ ] Study the per-file-keyed precedent: `[:recent-colors file-id]` / `[:recent-fonts file-id]` (refs.cljs:283-297) seeded on `initialize-workspace` (workspace.cljs:345-346). We copy the **keying** but **not** the storage seeding (so it resets on reload).
- [ ] Confirm what `finalize-workspace` / `finalize-file` dissoc from root state (workspace.cljs:536-547) — **verify our chosen key is NOT in any dissoc list**, so it survives the dashboard round-trip in memory
- [ ] Confirm `initialize-page*` / `initialize-workspace` do not clobber the key (they must not reset it per-page)
- [ ] Confirm the current file-id ref (`refs/current-file-id` or equivalent)

## Checklist

- [x] Created `frontend/src/app/main/data/workspace/ai_panel.cljs`: state `[:ai-panel file-id :open?]` in root `st/state`, **in-memory only** (never written to `storage/*`); events `close-panel` + `toggle-panel` reading `:current-file-id`. ✓
- [x] Added `refs/ai-panel-open?` derived (current-file-keyed) for the button variant + render gate. ✓
- [x] Replaced the flag gate in `workspace.cljs` with `(mf/deref refs/ai-panel-open?)`; **removed** `:ai-panel` from `valid-flags`. ✓
- [x] Rebound the button `on-click`, the panel close button, and the `:toggle-ai-panel` shortcut `:fn` to `(dwaip/toggle-panel)` / `(dwaip/close-panel)`. ✓
- [x] Render gate + button variant now read from the ref. ✓
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, `shadow-cljs compile main` → **0 warnings** (1179 files). ✓
- [x] Preview review (live devenv) — **the story's core acceptance test, all passing**:
  - open panel → switch **Page 1 → Page 2** → still open ✓
  - open panel → **go to dashboard → return** → still open ✓ (the old `workspace-local` bucket lost this)
  - open panel → **hard refresh** → closed ✓ (button back to ghost)
  - file-bound: state keyed by `[:ai-panel file-id]` — a file with no entry reads `nil` ⇒ closed (verified-by-construction; returning to the same file preserved state) ✓
- [ ] Human approval received
- [ ] Commit: `feat(workspace): file-bound persistence for all-in-penpot panel`

## After Finish

- [ ] Rename `todo-` → `done-`, update README link
- [ ] Record the exact state path + any finalize-list surprise in Notes

## Files

- `frontend/src/app/main/data/workspace/ai_panel.cljs` *(new)* — per-file open-state events
- `frontend/src/app/main/refs.cljs` — `ai-panel-open?` derived ref
- `frontend/src/app/main/ui/workspace.cljs` — gate render on the ref, drop the flag
- `frontend/src/app/main/ui/workspace/right_header.cljs` — button toggles via the event
- `frontend/src/app/main/data/workspace/shortcuts.cljs` — shortcut toggles via the event
- `frontend/src/app/main/data/workspace/layout.cljs` — remove the scaffold `:ai-panel` flag

## Notes

- Root `st/state` survives SPA navigation and dashboard round-trips (only a hard reload rebuilds it) → this is precisely the "survive nav, reset on refresh" behaviour with **zero** persistence code.
- File-binding comes free from keying by file-id; a fresh file has no entry ⇒ closed by default.
- If `finalize-workspace` turns out to dissoc a parent key that contains `:ai-panel`, store it under a key that is preserved (mirror how `:workspace-cache` survives) and document the choice.
