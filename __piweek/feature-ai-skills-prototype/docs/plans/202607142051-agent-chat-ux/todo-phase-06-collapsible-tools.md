# Phase 06 — Collapsible tool groups

**Status:** todo
**Depends on:** Phase 05 (tool event payload)

## Before Start

- [ ] Verify plan is still valid
- [ ] Confirm Phase 05 landed (`:input`/`:result` on tool messages)
- [ ] Read `ui/workspace/ai_panel.cljs:150-169` (transcript render), post-Phase-01 shape

## Checklist

- [ ] Group consecutive `"tool"` messages at render time
- [ ] `tool-group*` disclosure component ("Ran 3 tools")
- [ ] Expanded rows show input + result
- [ ] Auto-expand groups containing an `error`/`rejected` chip
- [ ] `aria-expanded` + `aria-controls`
- [ ] SCSS for the group row + detail
- [ ] Compile + preview review with browser tools
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — `tool-group*`, grouped render
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — group + detail styles

## Notes

**Group at render time. Do not store groups in state.** The transcript is a flat vector and every
append is `(fnil conj [])` (`data/workspace/ai_panel.cljs:62-63,73-74`) — O(1), no reconciliation.
Storing groups would force `append-tool` to inspect and mutate the tail, and
`clear-chat`/rehydration would have to rebuild them. Grouping is a pure function of the vector:

```clojure
(mf/with-memo [messages]
  (->> messages
       (map-indexed vector)
       (partition-by (fn [[_ m]] (= "tool" (:role m))))))
```
`partition-by` on the role predicate yields alternating runs: render a `"tool"` run as one
`tool-group*`, everything else per-message. **Keep `(map-indexed vector)` before partitioning** so
`:key` stays the stable transcript index — the current `:key idx` (`:155`, `:162`) is already
positional and appends are tail-only, so this is safe.

**Collapsed state is ephemeral view state** — `(mf/use-state false)` inside `tool-group*`, keyed by
the group's first index. Default collapsed.

**Auto-expand groups containing an `error`/`rejected` chip.** A blocked write is the single thing
the user most needs to see; hiding it behind a click is the wrong default. (`tool-chip-error`
already keys off `#{"error" "rejected"}` at `ai_panel.cljs:154`.)

**Reuse the house disclosure shape.** The only precedent in the prototype is `SkillCard`
(`ai-skills/src/ui/Skills.tsx:98-147`): local `useState` per row, a `<button>` summary,
`{open && …}` body — but note it has **no `aria-expanded`**, so don't copy its a11y. The bar to
match is `ModelPicker` (`Chat.tsx:438-439`). In CLJS the count-badge grouping precedent is
`Audit.tsx:87-90` / `SkillsCatalog.tsx:83-89`. Use `mf/use-id` for the `aria-controls` target.

**Truncate for display.** Phase 05 caps the stored `:result` at ~2000 chars; still render it in a
scrollable `<pre>` so a long JSON blob can't blow out the panel width. `.toolchip` is already
`display:flex; flex-direction:column`, so a detail block slots in under the summary.
