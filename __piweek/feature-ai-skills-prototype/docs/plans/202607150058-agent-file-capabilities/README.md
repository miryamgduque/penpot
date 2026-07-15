# Agent file capabilities — widen the agent's reach over the file

**Status:** doing
**Created:** 2026-07-15
**Apps:** `frontend`
**Dependencies:** None. Export/render is owned by [agent-vision](../completed/202607150027-agent-vision/README.md) — cross-referenced, not duplicated.

## Context

Asked to "create penpot variants from the selection", the agent produced five frames named
`Card=Size=Compact`, `Card=State=Hover` … and then correctly reported it could not finish:

> Penpot's variant system (as exposed through my available tools) requires converting frames
> INTO a component with variant children. My `create_component` tool creates a simple
> component, not a variant set.

That report was accurate. Chasing it produced a broader finding, and this plan is the result.

**Nothing is missing from Penpot.** The gap is the agent's tool registry: `tool-specs`
([agent_tools.cljs:47](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs))
lists ten tools, and the internals they *don't* reach are complete, shipped, and in most cases
already generic.

### The shape of the gap

Demand (what the 15 aikit skill playbooks instruct the agent to do, tallied by mentions inside
code fences) against supply (the ten registered tools):

| Capability | Demand in skills | Agent tools today | Wave |
|---|---|---|---|
| Layout (flex/grid) | 76 mentions, 8 skills | **none** | 2 |
| Tokens: author + apply | 49 + 48 mentions, 9 skills | color only; fill/stroke only | 3 |
| Component instances | `instance()` ×17, `detach()` ×13 | **none** | 5 |
| Export / render | 55 mentions, 13 of 15 skills | **none** | → agent-vision |
| Tree + token discovery | `shapeStructure` ×33, `tokenOverview` ×36 | `read_design`, shallow | 7 |
| Styling: radius/shadow/opacity | 40 mentions, 11 skills | fill + stroke only | 6 |
| Grouping / delete / selection | 18 / 2 / 20 | **none** | 4 |
| Handoff codegen | 25 mentions, 1 skill (its whole method) | **none** | 8 |
| Variants | `createVariantFromComponents` ×12 | **none** | 1 |

Three patterns explain the transcript better than any single missing tool does:

**The agent can create, barely modify, and cannot delete.** `create_shape` has no counterpart —
in the transcript it made five frames it had no way to remove. `modify_shape` covers
name/x/y/w/h/fill/stroke and nothing else: no radius, shadow, or opacity.
`penpot-rename-layers` ships as `mode: autofix`, which presumes mutation it can walk back.

**Everything is color-shaped.** `create_color_token` hardcodes `{:type :color}`; `apply_tokens`
maps only to fill and stroke-color. Penpot supports twenty token types
([token.cljc:129](../../../../../common/src/app/common/types/token.cljc)) and the skills demand
spacing (19) and radius (15). So `penpot-component-factory`'s One Rule — *"every value is a
token"* — is currently unsatisfiable. Even the enforcement rule is named `token-only-colors`,
which reads less like a policy than an accurate description of the ceiling.

**Absolute positioning is the only positioning.** The central method of `build-screen`,
`build-from-code`, `migrate` and `component-factory` is: create a board → `addFlexLayout()` →
apply spacing tokens to gap/padding. The agent can do step one. That is why it hand-placed
every card at (900,800), (900,1000), (1300,600) — not laziness, the only tool it had.

### The good news

The internals are already generic; the agent layer narrowed them. `dwta/toggle-token` takes an
arbitrary `attrs` set and `dwtl/create-token` an arbitrary `:type` — the color-only ceiling
lives entirely in the `attr-set` helper at
[agent_tools.cljs:406](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs)
and one hardcoded keyword at `:401`. Wave 3 is mostly deletion of narrowing. Likewise
`combine-as-variants`, `create-layout-from-id`, `delete-shapes`, `group-shapes` and
`instantiate-component` are existing, tested events with a UI caller each.

## Waves

Ordered by demand and unblocking power. **Detail is proportional to proximity** — Waves 1–3 are
specified to implement; later phases carry the finding, the entry point and the constraints,
and get fleshed out at their Before Start rather than guessed at now.

### Wave 1 — Variants (the thread that started this)

1. [Phase 01 — Create variant](./done-phase-01-create-variant.md) — `create_variant` combines 2+ main components into a real set, with validation that names the fix
2. [Phase 02 — See the set](./done-phase-02-see-the-set.md) — `read_design` surfaces containers, members and properties
3. [Phase 03 — Name the axes](./todo-phase-03-name-the-axes.md) — `set_variant_property` renames the placeholder axis `Property 1` to `Size` (values already come from the component names — see Phase 01 notes)
4. [Phase 04 — Grow the set](./todo-phase-04-grow-the-set.md) — `add_variant` adds a member to an existing set
5. [Phase 05 — Does the skill drive it?](./todo-phase-05-does-the-skill-drive-it.md) — verify `penpot-component-factory` reaches the new tools; patch only if it doesn't

### Wave 2 — Layout (the biggest gap)

6. [Phase 06 — Give boards a layout](./todo-phase-06-give-boards-a-layout.md) — `set_layout`: flex on a board (dir, gaps, padding, align/justify)
7. [Phase 07 — Place the children](./todo-phase-07-place-the-children.md) — `set_layout_child`: grow, align-self, margins, absolute, min/max

### Wave 3 — Tokens beyond color (mostly un-narrowing)

8. [Phase 08 — Any token type](./todo-phase-08-any-token-type.md) — `create_token` generalizes `create_color_token` to all 20 types
9. [Phase 09 — Any token attr](./todo-phase-09-any-token-attr.md) — `apply_tokens` reaches spacing, radius, sizing, typography
10. [Phase 10 — Show every token](./todo-phase-10-show-every-token.md) — `read_design` stops reporting only `colorTokens`

### Wave 4 — Editing primitives (close the create/modify/delete asymmetry)

11. [Phase 11 — Undo the agent's own mess](./todo-phase-11-delete-and-duplicate.md) — `delete_shape`, `duplicate_shape`
12. [Phase 12 — Group and ungroup](./todo-phase-12-group-and-ungroup.md) — `group_shapes`, `ungroup_shapes`

### Wave 5 — Components as a system

13. [Phase 13 — Place a component](./todo-phase-13-place-a-component.md) — `create_instance`, so a built library is no longer write-only

### Wave 6 — Styling depth

14. [Phase 14 — Radius, shadow, opacity](./todo-phase-14-radius-shadow-opacity.md) — widen `modify_shape` past fill/stroke

> Export / render (55 mentions, 13 of 15 skills — the widest-reaching gap by skill count) is
> **not** a phase here. It is [agent-vision Phase 06](../completed/202607150027-agent-vision/done-phase-06-render-board-tool.md).
> That plan is well aimed; do not duplicate it.

### Wave 7 — Seeing the file

15. [Phase 15 — Deeper than top level](./todo-phase-15-deeper-than-top-level.md) — `read_design` walks nested shapes; `find_shapes` queries them

### Wave 8 — Handoff

16. [Phase 16 — Markup and style](./todo-phase-16-markup-and-style.md) — `generate_markup` / `generate_style`, the whole method of `penpot-design-to-code-review`

## Sequencing notes

- **Waves 1–3 are the spine.** Variants first: small, planned, closes a demo-able loop end to
  end. Layout second: unblocks the most skill-prescribed behavior, and is the largest single
  piece of work here. Tokens third, because layout without spacing tokens just relocates the
  hardcoded values from x/y into gap/padding.
- **Phases 08 and 09 are near-free and may jump the queue.** Both are small edits to existing
  helpers. If a demo needs "every value is a token" to be true before layout lands, pull them
  forward — they do not depend on Wave 2. Their headline use (gap/padding) does.
- **Wave 4 is small, unglamorous, and the first thing to pull forward if the agent starts making
  messes it cannot clean up.** Watch for that during Wave 2 review: `set_layout` on the wrong
  board is exactly the mistake that wants a delete.
- **Waves 5–8 are ranked but not scheduled.** Re-read the demand table before starting one — the
  tally is a snapshot of the current aikit import and can move.

## Acceptance Criteria

Per-phase criteria live in the phase files. Plan-level, the agent should be able to do what the
skills already tell it to do:

- Build a component as a **flex board** with tokenized gap and padding — the literal method of
  `build-screen` / `component-factory` — rather than hand-placed absolute coordinates.
- Make "every value is a token" **true**: author and apply spacing, radius, sizing and
  typography tokens, not only color.
- Combine main components into a **real variant set** with named axes.
- **Place** an instance of a component it built.
- **Remove** a shape it created.
- Fail loudly on invalid input with a message naming the corrective action — never silently, and
  never by emulating a capability in layer names.

## A standing rule for every tool in this plan

The `Property=Value` frames were not a bug in the model. They are what happens when a tool is
absent or silently no-ops: the agent improvises something that *looks* like the capability. Two
consequences bind every phase here.

1. **Validate before emitting, and name the fix in the message.** Not `invalid input` but
   `shape X is a frame, not a main component — call create_component on it first`. The internal
   events filter invalid input silently (`combine-as-variants`
   [:659-663](../../../../../frontend/src/app/main/data/workspace/variants.cljs)), so a thin
   passthrough leaves the agent unable to tell success from silence.
2. **Never ship a tool that emulates.** No encoding structure in names, no faking a capability
   the file model lacks. If we cannot do it properly, the agent saying "I can't do that" is the
   correct outcome — `body-preamble`
   ([agent_skills.cljs:177](../../../../../frontend/src/app/main/data/workspace/agent_skills.cljs))
   already instructs exactly that, and in the transcript the agent obeyed.
