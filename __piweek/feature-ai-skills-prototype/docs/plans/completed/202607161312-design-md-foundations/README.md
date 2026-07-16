# DESIGN.md Vibes + Per-file Foundations

**Status:** done
**Created:** 2026-07-16
**Apps:** `frontend`
**Dependencies:** None (builds on the shipped vibes feature from plan 202607152230)
**Story:** [Taiga US #38 — Per-file Foundations, standing design context the agent reads from](https://tree.taiga.io/project/miryam-all-in-penpot/us/38)

## Context

The project-vibes feature ships today as a single free-form markdown doc stored in
file plugin-data (`:penpot-vibes` / `"design-md"`, 4k cap), written by the
`penpot-project-vibes` interview skill via the `set_design_doc` tool, inlined into
the system prompt every turn, and edited through a raw textarea in the panel's
vibes view.

US #38 generalizes this into **Foundations**: per-file, open-ended named entries
of standing design context (Vibes, Tone of voice, …) reached from a compass icon
in the Agent panel header, each agent-authored and agent-edited.

This plan does both, in order:

1. **DESIGN.md format** (user's stated main goal): the vibe interview's output
   adopts the [google-labs-code/design.md](https://github.com/google-labs-code/design.md)
   format — YAML frontmatter carrying machine-readable design tokens (colors,
   typography, rounded, spacing, components) + a markdown body in the canonical
   sections (Overview, Colors, Typography, Layout, Elevation & Depth, Shapes,
   Components, Do's and Don'ts). Frontmatter becomes safely editable through a
   **structured form** (swatches / labeled fields), never raw YAML.
2. **Foundations UI** (the rest of US #38): vibes becomes the first entry in a
   per-file Foundations list (compass icon, list over chat, detail view with
   agent input, removal), with an open-ended set of further entries.

Decisions from discovery (Santi, 2026-07-16):
- Full google-labs spec fidelity for the format.
- Frontmatter edited via structured form; body stays a markdown textarea.
- Token values are **standalone** CSS values — no coupling to Penpot's design
  token system in this plan.
- One plan for both halves; DESIGN.md phases land first and are independently
  shippable for the Friday demo.

## Grounding facts (verified in code, 2026-07-16)

- `frontend/src/app/main/data/workspace/design_doc.cljs` — storage, 4k cap,
  `system-prompt-section`, `doc-ref`.
- `set_design_doc` tool: spec at `agent_tools.cljs:184`, impl `:2771`.
- Interview skill body: `agent_skills.cljs:40` (`vibes-body`).
- Vibes view UI: `ai_panel.cljs:1519` (`vibes-view*`), nav state in `ai-panel*`
  (`:2014` area) — view/back handling is local component state, one level per back.
- `js-yaml@4.2.0` present in `frontend/node_modules` but **transitive only** —
  must be added to `frontend/package.json` before requiring it.
- `markdown*` (marked.lexer) renders `---` frontmatter as hr/paragraph garbage —
  body must be split from frontmatter before rendering.
- No `compass` icon in the DS. Recipe: Lucide SVG into
  `frontend/resources/images/icons/compass.svg` + `(def ^:icon-id compass "compass")`
  in `ds/foundations/assets/icon.cljs` + rerun `build-app-assets.js` (sprite).
- SCSS changes always need `node ./scripts/build-app-assets.js` in the devenv.

## Phases

1. [Phase 01 — design-md core](./done-phase-01-design-md-core.md) — parse/serialize/validate DESIGN.md (frontmatter + body) with tests
2. [Phase 02 — interview emits DESIGN.md](./done-phase-02-interview-emits-design-md.md) — vibes skill + set_design_doc produce/validate the new format
3. [Phase 03 — frontmatter-aware vibes view](./done-phase-03-vibes-view-render.md) — token summary rendering (swatches, type, spacing) + body markdown
4. [Phase 04 — structured frontmatter editor](./done-phase-04-frontmatter-editor.md) — form-based token editing, body textarea, no raw YAML
5. [Phase 05 — foundations storage + tools](./done-phase-05-foundations-storage.md) — multi-entry plugin-data model, `set_foundation` tool, prompt assembly, legacy migration
6. [Phase 06 — Foundations list view](./done-phase-06-foundations-list.md) — compass icon, header button, list over chat, "Applies to this file"
7. [Phase 07 — foundation detail + agent-guided edit](./done-phase-07-foundation-detail.md) — detail view, agent input, remove, "Add a foundation" flow

## Acceptance Criteria

- Running the vibes interview produces a valid DESIGN.md: YAML frontmatter with
  named color/typography/rounded/spacing tokens and canonical `##` sections.
- The vibes view renders tokens visually (color swatches at minimum) and the
  body as markdown — the raw `---` fence is never shown.
- Editing touches frontmatter only through form fields; an invalid doc cannot
  be saved; save round-trips through parse → serialize without data loss.
- A file can hold multiple named foundations; every one is inlined into the
  agent's system prompt; the compass icon opens the list per the wireframes.
- Existing files with a legacy vibes doc keep working unmodified.
- Frontend compiles green (`shadow-cljs compile main` + `test`), kondo + cljfmt clean.

## Completion Summary

**Completed:** 2026-07-16

### What Shipped
- DESIGN.md core (`design_md.cljs`): parse / serialize / validate / display-model / edit-model for the google-labs format, js-yaml as a real dependency. 30+ unit tests.
- The vibes interview writes a full-spec DESIGN.md (token frontmatter + canonical sections); saves are validated at `doc-problem` (broken YAML and dangling `{token.refs}` rejected with readable reasons); cap 4000 → 6000.
- The panel renders frontmatter as a token summary (swatches, type lines, scale chips) and edits it through a structured form — raw YAML never reaches the user; legacy plain-markdown docs work unchanged everywhere.
- Per-file Foundations (US #38): multi-entry storage (vibes keeps the legacy `design-md` key; others under `foundation/<slug>`), `set_foundation` tool, a per-foundation "## Foundations" prompt section, compass header icon + list over chat per the wireframes, and a generalized detail with agent-guided editing (ask input seeds a scoped chat prompt; + Add seeds the create trigger).
- `set_design_doc` retired; the vibes skill saves via `set_foundation` (name "Vibes"). Retirement pinned by a test.

### What Changed from Original Plan
- Storage simplification: instead of the planned read-both/write-new migration, Vibes simply keeps the legacy key forever — no dual-key ambiguity, old clients just work.
- Phase 06 shipped a temporary read-only detail for non-vibes foundations; phase 07 replaced it with the full generalized `foundation-view*` as planned.
- The other-foundations authoring guidance lives in the `set_foundation` tool description rather than the vibes skill body.

### Lessons & Follow-ups
- `mf/deref` on a rumext `use-state` handle passed to a child component throws IWatchable and blanks the workspace — plain `deref`; the parent re-render carries the child.
- The shared working tree bit twice: 10 concurrent commits mid-phase-06 (no overlap), and phase 07's first commit swept the other session's STAGED ai-skills/mcp deletions — recovered via `reset --soft` + pathspec commit. Check `git status` immediately before every commit in this tree.
- Still owed: one live model turn (interview → valid DESIGN.md; chat-created foundation) with the user's API key, on Opus/Sonnet.
- Possible future refinements (story-flagged as out of scope): per-skill foundation selection, sharing/promoting foundations beyond the file, a frontmatter `icon` field for card glyphs, `components` token editing (read-only note in the form today).
