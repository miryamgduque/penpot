# Phase 16 — Markup and style

**Status:** todo

`generateMarkup()` (12 mentions) and `generateStyle()` (13) are used by exactly one skill —
`penpot-design-to-code-review` — but they are that skill's *entire method*. Narrow reach,
total centrality: without them the skill has nothing to review, since its whole premise is
comparing Penpot's generated markup against the code you actually shipped.

Ranked last because it serves one skill, not because it's optional for that skill.

## Before Start

- [ ] Read `generate-markup` ([api.cljs:488](../../../../../frontend/src/app/plugins/api.cljs)) — `(shapes {:type "html"|"svg"})`
- [ ] Read `generate-style` (:522) — `(shapes {:type "css", :withPrelude, :includeChildren})`
- [ ] Read `generate-font-faces` (:572)
- [ ] **Find the internal fns these wrap.** The plugin API is a thin shell over something in `app.main.render` or `app.util.code-gen` — the agent tool should call that directly, not reach through the plugin proxy layer
- [ ] Re-read `penpot-design-to-code-review`'s body in `aikit_bodies.cljs` and check what it actually does with the output — it's the only consumer, so its usage defines the tool's shape

## Checklist

- [ ] Write tests
- [ ] Add `generate_markup` / `generate_style` to `tool-specs` (or one tool with a `type` param — decide from the skill's usage)
- [ ] Implement, wire into dispatch
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: run `penpot-design-to-code-review` end to end against a real component
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Notes

**Budget is the design constraint.** Generated CSS + HTML for a non-trivial board is large, and
tool results truncate at 20k chars ([agent.cljs:350](../../../../../frontend/src/app/main/data/workspace/agent.cljs)).
Truncated CSS is not degraded output — it's *wrong* output, and the skill's method is
comparison, so half a stylesheet produces confidently false findings. Either scope the tool to
one shape at a time, or detect the overflow and refuse with a message telling the agent to
narrow its selection. Do not let it silently truncate.

**Check `includeChildren` carefully.** It's the difference between a component's own styles and
the full subtree, and it's the knob that decides whether output fits the budget.

This phase pairs naturally with export/render ([agent-vision Phase 06](../completed/202607150027-agent-vision/done-phase-06-render-board-tool.md)) —
`design-to-code-review` wants to *see* the design and *read* its markup. If agent-vision has
landed by the time this starts, run the skill with both and see whether they compose or fight
for the same budget.
