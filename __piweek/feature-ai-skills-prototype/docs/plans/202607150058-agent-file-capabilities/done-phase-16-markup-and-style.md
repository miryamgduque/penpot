# Phase 16 — Markup and style

**Status:** done

`generateMarkup()` (12 mentions) and `generateStyle()` (13) are used by exactly one skill —
`penpot-design-to-code-review` — but they are that skill's *entire method*. Narrow reach,
total centrality: without them the skill has nothing to review, since its whole premise is
comparing Penpot's generated markup against the code you actually shipped.

Ranked last because it serves one skill, not because it's optional for that skill.

## Before Start

- [x] Read `generate-markup` / `generate-style` in the plugin API — thin shells, as suspected
- [x] **Find the internal fns** — `cg/generate-formatted-markup-code (objects type shapes)` and `cg/generate-style-code (objects type root-shapes all-shapes options)` in `app.util.code-gen`. Called directly; the plugin proxy layer is not involved
- [x] Re-read `penpot-design-to-code-review`'s usage — **its script calls BOTH and returns `{markup, style, perNode}`**, so this is one tool, not two (see Notes)

## Checklist

- [x] Write tests
- [x] Add to `tool-specs` — **one `generate_code` returning both**, per the skill's own usage
- [x] Implement, wire into dispatch
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: real markup + CSS out of the file, round-tripping Phase 14's shadow
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Notes — what execution found

**One tool, not two — the skill's own usage decided it.** The plan left this open ("or one tool
with a `type` param — decide from the skill's usage"). `penpot-design-to-code-review`'s script
calls `generateMarkup` **and** `generateStyle` together and returns `{markup, style, perNode}`.
Shipping two tools would have made the agent pair them up on every review for no reason.

**The internal fns are reachable, so the plugin proxy is not involved.** `cg/generate-formatted-markup-code`
and `cg/generate-style-code` (`app.util.code-gen`) are what the plugin API itself wraps. The
resolution steps are copied from it: `cfh/clean-loops` then `cfh/get-children-with-self`, and
`includeChildren` defaults **true** as the plugin's does — a component's own styles without its
children is rarely what a review wants.

**The budget worry the phase was written under is already handled.** Its notes said "do not let
it silently truncate" — Phase 15 fixed that at the transport layer for *every* tool: an
oversized result is now refused as valid JSON naming the limit and the way out. So this tool
does not need its own truncation logic; it just names the narrowing levers (fewer shapes,
`includeChildren: false`) in its description and note, so the refusal is actionable when it
fires.

**Verified live, and it closed a loop across phases.** Generating code for the `HoverCard` that
Phase 14 styled:

```css
box-shadow: 0px 4px 12px 0px #00000040
```

That is exactly what Phase 14 wrote — `offsetY 4, blur 12, spread 0, #000000 @ 0.25` — read
back as CSS (`0x40` = 64/255 = 0.25). **Phase 14 writes the design, Phase 16 reads it as code**:
the agent can now set a shadow and then see the rule it produced, which is the whole
design-to-code review in one round trip. `svg` works; `includeChildren: false` works; a bad type
and an unknown shape are both rejected naming the fix.

**Noted, not chased:** `border-radius` does not appear in the generated CSS for a frame even
though `r1..r4` are set. That is Penpot's codegen, identical through the plugin API, and not
something this tool introduces — but worth knowing before trusting a review's silence about
radius.

## Notes — from planning

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
