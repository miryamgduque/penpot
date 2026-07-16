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

### Second sweep (2026-07-15)

A re-survey of the playbooks against the now-21-tool registry, done while Wave 5 was in
flight. Four gaps hold up under context-reading (raw symbol counts mislead here — see the
non-findings below):

| Capability | Demand evidence | Agent tools today | Phase |
|---|---|---|---|
| Text: edit content + typography | `heading` ×8, `label` ×10; design-to-code audits family/size/weight/line-height/letter-spacing; foundations' type scale | `create_text` only — no edit, no font anything | 19 |
| Token sets + themes (dark mode) | foundations' whole method: `modes/light`/`modes/dark` + `addTheme` (`theme` ×16, `dark` ×15) | `create_token` targets the library's *first* set, always | 20 |
| Detach instance | `detach()` ×5, always governance-wrapped; our own rejections say "detach the copy first" | **none** — we recommend a tool that doesn't exist | 21 |
| Grid layout | "flex/**grid** Board" is the skills' prescribed alternative to x/y; `addGridLayout` ×1 | `set_layout` is flex-only | 22 |
| Vectors: icons via SVG import | `icon` in the naming taxonomy + severity rubrics; today an icon is faked from rects | **none** — no path create, edit or read | 23 |
| Undo/redo own edits | recovery asymmetry: `delete_shape` covers creations only; our results tell the user "⌘Z" for what the agent broke | **none** — a bad `modify_shape` is unrecoverable | 24 |
| Mask / unmask | zero corpus demand — added by request; natural demand (clipped images, avatar crops) arrives with Phase 17 | **none** — `group_shapes` makes only plain groups | 25 |

Checked and deliberately **not** phases — the counts collapse on reading context:
prototyping/interactions (every `flow` is "workflow"/"reflow"), component swap (every `swap`
is a *token* swap — noted in Phase 21 as a sibling if demand appears), boolean ops, pages,
rotation/flip, and z-order (`nest_shape`'s `index` already covers it, and layout order is
append order). Stroke depth (width/style/alignment) has near-zero mentions; if it surfaces,
it belongs in Phase 14's widening, not its own phase. Point-level **path editing** is also
out: demand is zero (every playbook `path` is prose), and the internals are an interactive
editor state machine with no headless entry — Phase 23 covers the icon need through the
shipped SVG-import pipeline instead. **Mask/unmask** started in this list for the same
zero-demand reason, then moved to Phase 25 by explicit request — the honest accounting is in
that phase's own header.

### Third sweep (2026-07-16) — supply side

The first two sweeps asked *what do the skills demand*; this one asked *what can the
application do* — the full plugin-API surface and the workspace event layer, cataloged and
diffed against the registry (24 tools by now) and phases 20–25. The mask/undo lesson applied
systematically. Three findings became phases:

| Capability | Why it matters | Internals | Phase |
|---|---|---|---|
| Hide / lock, read AND write | a hidden shape currently reads as visible — the agent reasons about shapes that aren't on screen; locked shapes aren't respected by any tool | `update-shape-flags` (shapes.cljs:444) | 26 |
| Drive a placed copy: switch variant, reset overrides, swap | **an instance, once placed, is frozen** — the sets Wave 1 builds cannot be switched on the instances Wave 5 places; push-to-main deliberately excluded ("shared-asset edit, never auto-fix") | `dwv/variants-switch`, `dwl/reset-component` (:843), `dwl/component-swap` (:1044) | 27 |
| Version snapshots | gotcha #12's defense ("duplicate the file first") made real; pairs with Phase 24 — undo covers the last step, a snapshot covers the next hundred; restore deliberately excluded | `create-version-from-plugins` (versions.cljs:343), purpose-built headless | 28 |

This supersedes two second-sweep judgments: *component swap* was dismissed because every
playbook "swap" is a token swap — true, but the frozen-instance framing (switch/reset/swap as
one capability) is demand the tally couldn't see. *Z-order "covered by nest_shape"* still
holds for reordering, but the hidden/locked read gap sat in the same "layer state" territory
and was real.

Four more were borderline — reachable, zero corpus demand — and became phases 29–32 by
explicit decision (rotation/flip/stroke depth; comments; pages; booleans), each carrying that
accounting in its header.

Cataloged and still **not** phases, with reasons: interactions/prototyping and flows (full
API exists, zero corpus demand — the biggest deliberate omission; revisit if a prototyping
skill is ever imported); align/distribute (flex layout is the plan's answer to arrangement);
viewport/zoom and set-selection (presentation, not file content); ruler guides, page
background, `clipContent`, plugin-data storage, library publish/link/sync, clipboard,
`replaceColor` (bulk raw-color swaps fight token governance — `apply_tokens` + `audit_file`
is the governed path), library color/typography asset CRUD (tokens are this plan's asset
layer), stroke caps/alignment, `backgroundBlur`, event listeners, binary file export.

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
3. [Phase 03 — Name the axes](./done-phase-03-name-the-axes.md) — `set_variant_property` renames the placeholder axis `Property 1` to `Size` (values already come from the component names — see Phase 01 notes)
3b. [Phase 03b — Call it something](./done-phase-03b-call-it-something.md) — naming IS the matrix: a shared path prefix names the set, each further segment adds an axis (added during execution; its premise was refuted — see the file)
4. [Phase 04 — Grow the set](./done-phase-04-grow-the-set.md) — `add_variant` adds a member to an existing set
5. [Phase 05 — Does the skill drive it?](./done-phase-05-does-the-skill-drive-it.md) — **verified: it does.** haiku-4-5 self-corrected off a rejection message and built a real 4-member set. No patch needed

### Wave 2 — Layout (the biggest gap)

6. [Phase 06 — Give boards a layout](./done-phase-06-give-boards-a-layout.md) — `set_layout`: flex on a board (dir, gaps, padding, align/justify)
7. [Phase 07 — Place the children](./done-phase-07-place-the-children.md) — `set_layout_child`: grow, align-self, margins, absolute, min/max

### Wave 3 — Tokens beyond color (mostly un-narrowing)

8. [Phase 08 — Any token type](./done-phase-08-any-token-type.md) — `create_token` generalizes `create_color_token` to all 20 types
9. [Phase 09 — Any token attr](./done-phase-09-any-token-attr.md) — `apply_tokens` reaches spacing, radius, sizing, typography
10. [Phase 10 — Show every token](./done-phase-10-show-every-token.md) — `read_design` stops reporting only `colorTokens`

### Wave 4 — Editing primitives (close the create/modify/delete asymmetry)

11. [Phase 11 — Undo the agent's own mess](./done-phase-11-delete-and-duplicate.md) — `delete_shape`, `duplicate_shape`
12. [Phase 12 — Group and ungroup](./done-phase-12-group-and-ungroup.md) — `group_shapes`, `ungroup_shapes`
24. [Phase 24 — Take it back](./todo-phase-24-take-it-back.md) — `undo_change`/`redo_change` over the ⌘Z events; the phase is the ownership guard (never undo the *user's* edit), not the emit
25. [Phase 25 — Mask and unmask](./todo-phase-25-mask-and-unmask.md) — `mask_shapes`/`unmask_shapes`, clipping to any shape; the mask-child-by-order rule is the UX, and both events filter silently (Phase 12's sibling)
26. [Phase 26 — Hide and lock](./todo-phase-26-hide-and-lock.md) — the flags on `modify_shape` + `read_design`; the real content is the lock-respect decision, which touches every mutating tool
28. [Phase 28 — Save a version](./todo-phase-28-save-a-version.md) — `save_version` over the purpose-built headless snapshot event; gotcha #12's "duplicate the file first" made real (restore stays the user's move)

### Wave 5 — Components as a system

13. [Phase 13 — Place a component](./done-phase-13-place-a-component.md) — `create_instance`, so a built library is no longer write-only
21. [Phase 21 — Detach an instance](./todo-phase-21-detach-an-instance.md) — `detach_instance`, the move our own rejection messages already recommend; the guards (never a variant member) are the phase
27. [Phase 27 — Drive the copy](./todo-phase-27-drive-the-copy.md) — `switch_variant` / `reset_overrides` / `swap_component`: a placed instance stops being frozen, and Waves 1 + 5 finally compose

### Wave 6 — Styling depth

14. [Phase 14 — Radius, shadow, opacity](./done-phase-14-radius-shadow-opacity.md) — widen `modify_shape` past fill/stroke
29. [Phase 29 — Turn, mirror, stroke](./todo-phase-29-turn-mirror-stroke.md) — rotation, flip and stroke width/style, write AND read: the last attrs invisible to replicate-accurately

> Export / render (55 mentions, 13 of 15 skills — the widest-reaching gap by skill count) is
> **not** a phase here. It is [agent-vision Phase 06](../completed/202607150027-agent-vision/done-phase-06-render-board-tool.md).
> That plan is well aimed; do not duplicate it.

### Wave 7 — Seeing the file

15. [Phase 15 — Deeper than top level](./done-phase-15-deeper-than-top-level.md) — `read_design` walks nested shapes; `find_shapes` queries them

### Wave 8 — Handoff

16. [Phase 16 — Markup and style](./done-phase-16-markup-and-style.md) — `generate_markup` / `generate_style`, the whole method of `penpot-design-to-code-review`

### Wave 9 — Reading a shape's look (the replicate gap)

Asked to *"replicate the selected element as accurately as possible"*, the agent read the design,
rendered it (agent-vision) and reported two walls precisely: *"1. Fill/image content … 2.
Effects/filters"* — things it *"can see visually but cannot directly inspect through the API"*. Both
are accurate: `summarize-shape` ([agent_tools.cljs:186](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs))
returns geometry only — no fills, no effects. This is the **read/inspect** side of style: Wave 6
(Phase 14) *writes* radius/shadow/opacity, Wave 7 (Phase 15) reads *depth* — neither surfaces a
shape's paint or effects for the model to copy, and image fills are unreachable entirely.

17. [Phase 17 — Inspect fills and image content](./done-phase-17-inspect-fill-and-image.md) — `read_design` reports each shape's fills (solid / gradient / **image** with its ref); image-fill write is scope-gated
18. [Phase 18 — Inspect effects and filters](./done-phase-18-inspect-effects-and-filters.md) — `read_design` reports present shadow / blur / opacity / radius, mirroring Phase 14's write params so read → copy → write is a straight path

### Wave 10 — Text (from the second sweep)

19. [Phase 19 — Edit the words](./done-phase-19-edit-the-words.md) — `set_text` + a widened `create_text`: content and typography (size, family, weight, align), so a heading can be a heading

### Wave 11 — Sets and themes (dark mode)

20. [Phase 20 — Sets and themes](./todo-phase-20-sets-and-themes.md) — token sets as targets, themes as switches; the foundations skill's light/dark method becomes possible

### Wave 12 — Grid layout

22. [Phase 22 — Grid layout](./todo-phase-22-grid-layout.md) — `set_layout` learns grid tracks; the smaller half of Wave 2's escape from absolute positioning

### Wave 13 — Vectors, without the vector editor

23. [Phase 23 — Draw it in SVG](./todo-phase-23-draw-it-in-svg.md) — `create_from_svg` over the shipped import pipeline: icons the model draws, paths it never point-edits
32. [Phase 32 — Cut and combine](./todo-phase-32-cut-and-combine.md) — `boolean_shapes` (union/difference/intersection/exclusion); ungroup can already dissolve a bool the registry cannot begin

### Wave 14 — Collaboration

30. [Phase 30 — Leave a note](./todo-phase-30-leave-a-note.md) — positioned comment threads for review findings; the plan's first outward-facing tool, so attribution is the governance

### Wave 15 — Pages

31. [Phase 31 — Another page](./todo-phase-31-another-page.md) — create/rename/duplicate pages + a page list in `read_design`; switching is navigation and gets decided, not assumed

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
- **Of the second-sweep waves (10–13), text first.** Phase 19 blocks every screen-building
  skill in a way the model cannot route around (there is no workaround for "all text is the
  same size"); Phase 20 unlocks one skill's headline feature; Phase 21 is small and
  policy-shaped, and closes rejection messages that currently point at a missing tool; Phases
  22 and 23 wait for demand — 23 is near-free (a passthrough to a shipped pipeline) and may
  jump the queue the first time a demo wants an icon. None depend on each other.
- **Of the third-sweep phases, 27 first, then 28, then 26.** Phase 27 is the one users will
  hit ("show me the hover state" is unanswerable today); 28 is tiny and should land before any
  variant-heavy session (it is the gotcha-#12 insurance); 26's lock-respect decision, like 24's
  ownership plumbing, touches every mutating tool and gets dearer the longer it waits.
- **Phase 24 (undo) is Wave 4's missing half and rises with tool volume.** The more mutating
  tools ship, the more often the agent needs to take an edit back — pull it forward the first
  time a session leaves a wrong fill it cannot revert. Its ownership plumbing (tagging or
  watermarking agent edits) touches every mutating tool, so landing it *before* Waves 10–13
  add five more of them is cheaper than after.

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
- Give a screen real **typography** — a heading sized like one, and text it can rewrite later.
- Build the foundations skill's **light/dark** structure: tokens in mode sets, themes that
  switch them, and a bound shape that visibly changes.
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
