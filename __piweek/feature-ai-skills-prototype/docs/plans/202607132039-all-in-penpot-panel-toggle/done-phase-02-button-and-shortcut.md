# Phase 02 — Bot icon, toolbar button & Alt+B

**Status:** done

## Goal

A single toolbar button with the Lucide **bot** icon, sitting next to the **View mode** (viewer/play) button in the right header, that toggles the panel — plus the **Alt+B** shortcut that does the same. This is the real open/close control the story asks for; it drives the `:ai-panel` flag from Phase 01 (Phase 04 swaps that for the file-bound event, keeping the button API stable).

## Before Start

- [ ] Confirm the **View mode** button is the viewer/play `[:a]` at the end of `right-header*` (right_header.cljs:289-292, title `workspace.header.viewer`)
- [ ] Confirm the two current AI buttons are the `i/feedback` (chat) and `i/puzzle` (skills) `icon-button*`s (right_header.cljs:250-259) — these get replaced by the single bot button
- [ ] Re-confirm **Alt+B is free**: only `["b" "a"]` (plain board tool) exists at shortcuts.cljs:287; no `(ds/a-mod "b")` anywhere
- [ ] Locate the icon sprite source that `collect-icons` reads (icon.cljs:16-309) to add a new `^:icon-id`

## Checklist

- [x] Added `resources/images/icons/bot.svg` (Lucide `bot` adapted to Penpot's 16×16 stroke convention) and registered `(def ^:icon-id bot "bot")` in `icon.cljs`. `icon-bot` verified present in the compiled sprite. ✓
- [x] In `right_header.cljs`, removed the two `comments-section` AI buttons (feedback + puzzle) + the now-unused `ref:skills-dock`, and added **one** `icon-button*` (`:icon i/bot`, variant primary when open / ghost when closed, `:aria-label "All-In Penpot"`) immediately **before** the View mode `[:a]`, wrapped in a `:title` showing the shortcut. On click → `(dw/toggle-layout-flag :ai-panel)`. ✓
- [x] Button variant derives from `(contains? layout :ai-panel)` (the same flag the panel uses). ✓
- [x] Added `:toggle-ai-panel` to `base-shortcuts` (`{:tooltip (ds/alt "B") :command (ds/a-mod "b") :subsections [:panels] :fn #(st/emit! (toggle-layout-flag :ai-panel))}`), using the local origin-stamping helper. ✓
- [~] i18n: used a **literal** `:aria-label "All-In Penpot"` (consistent with the removed AI buttons, which also used literals) instead of a `tr` key — avoids an i18n regen for the prototype. Revisit if the feature ships. **Deviation from plan, intentional.**
- [x] Lint / format / typecheck: `clj-kondo` clean (pre-existing `collect-icons` macro warning only), `cljfmt` clean, `shadow-cljs compile main` → **Build completed, 0 warnings**. ✓
- [x] Preview review (live devenv): bot icon renders correctly next to View mode; click toggles 0↔360px; **Alt+B toggles both ways** (verified with real keypresses); button = **ghost** closed / **primary** open. ✓
- [ ] Human approval received
- [ ] Commit: `feat(workspace): all-in-penpot toolbar button and alt+b shortcut`

## After Finish

- [ ] Rename `todo-` → `done-`, update README link
- [ ] Note the final icon id + any sprite-build step in Notes

## Files

- `frontend/src/app/main/ui/ds/foundations/assets/icon.cljs` — register `bot` icon id (+ sprite SVG source)
- `frontend/src/app/main/ui/workspace/right_header.cljs` — single bot button next to View mode
- `frontend/src/app/main/data/workspace/shortcuts.cljs` — `:toggle-ai-panel` Alt+B entry
- `frontend/translations/en.po` — button/shortcut strings

## Notes

- No bot/robot/sparkles icon exists in the sprite today — it must be added; `i/` names are schema-validated against `icon-list`, so an unregistered name won't compile.
- The two old buttons' toggle *wiring* (`dwsk/toggle-panel`) is left importable for now; the relay is fully retired in Phase 06.
- Keyboard shortcut tooltips render from `(ds/alt "B")`; confirm it displays as `Alt B` consistently with the other panel toggles.
