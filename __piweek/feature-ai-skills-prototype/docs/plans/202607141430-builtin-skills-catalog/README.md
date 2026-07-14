# Built-in skills ship with the panel (US #7)

**Status:** doing (Phase 01 done)
**Created:** 2026-07-14
**Apps:** `ai-skills` (panel), `skills-core`
**Source:** [Taiga US #7 — Built-in skills ship with the panel](https://tree.taiga.io/project/miryam-all-in-penpot/us/7)
**Dependencies:** None. Adjacent (out of scope here): #8 toggling, #9/#10 create/fork, #12/#13 team-published skills.

## Context

> As a Penpot user, I want a baseline set of skills to be available in the Agent
> without any setup, so I get useful, reliable behavior the first time I open the panel.

This story establishes the **Skills tab** first-run experience: the built-in catalog
rendered as a list of **category-grouped cards** (Audits / Build / Auto-fix), each with a
name, short description, and a fixed **mode** label (suggest / review / auto-fix). Clicking
a card opens a **read-only detail view** (name, category, mode, example trigger phrase,
what it does) that replaces the list within the same tab, with a back control.

It scopes only to *what ships by default and how it's presented* — not toggling (#8),
not creating/forking (#9/#10), not team-published skills (#12/#13).

### Current state (grounded in code)

- The Skills tab **already exists** ([`ai-skills/src/ui/App.tsx`](../../../../../ai-skills/src/ui/App.tsx) →
  [`Skills.tsx`](../../../../../ai-skills/src/ui/Skills.tsx)) but renders a **scope/enforcement**
  model (Effective / platform / org / project / file tabs, enforcement badges, inline
  toggles + editors) — *not* the story's catalog view.
- **Modes already live in frontmatter** of the bundled skills
  ([`skills-core/src/aikit.gen.ts`](../../../../../skills-core/src/aikit.gen.ts)):
  `mode: suggest` (audits + router), `mode: review` (build), `mode: autofix`
  (rename-layers). But [`parse.ts`](../../../../../skills-core/src/parse.ts) currently **ignores
  `mode`**, and there is no `category` or `example` field.
- The **router + 4 shared docs** (`penpot-router`, `penpot-plugin-api-gotchas`,
  `penpot-naming-conventions`, `penpot-operating-modes`, `penpot-mcp-tool-reference`) are
  the "core house rules" the story says must be **hidden** from the catalog.

### Decisions (from discovery)

1. **Build alongside** — the new catalog becomes the default Skills-tab view; the existing
   scope/enforcement UI stays reachable as an advanced/dev view rather than being deleted.
2. **Derive, don't re-author** — category is derived from `mode`
   (`suggest → Audits`, `review → Build`, `autofix → Auto-fix`); the example trigger phrase
   is derived from the existing `Triggers:` list in each skill's `description`.
3. **Include the enabled data model** — add a `defaultEnabled` resolution (`autofix` ⇒ off,
   everything else ⇒ on) plus a persisted enabled/disabled layer now, so #8 only adds the
   toggle control on top. No toggle UI ships in this story.

### Catalog membership (verified against `aikit.gen.ts`)

| Category  | Mode      | Skills | Default |
|-----------|-----------|--------|---------|
| Audits    | `suggest` | penpot-audit-accessibility, penpot-audit-tokens, penpot-design-to-code-review | on |
| Build     | `review`  | penpot-foundations, penpot-component-factory, penpot-build-screen, penpot-build-from-code, penpot-document-handoff, penpot-migrate | on |
| Auto-fix  | `autofix` | penpot-rename-layers | **off** |
| _(hidden)_ | —        | penpot-router + 4 shared docs (core house rules) | n/a |

Exclusion rule: include skills that carry a catalog `mode` **except** `penpot-router`;
the shared docs carry no `mode` and fall out naturally.

## Phases

1. [Phase 01 — Catalog model in skills-core](./done-phase-01-catalog-model.md) — parse `mode`, derive category/example/default, exclude core; unit-tested. ✅ **done** (31 tests green)
2. [Phase 02 — Enabled state + payload](./todo-phase-02-enabled-and-payload.md) — default-enabled resolution + persisted layer, exposed through the plugin→panel payload.
3. [Phase 03 — Catalog list UI](./todo-phase-03-catalog-list-ui.md) — grouped cards as the default Skills tab; keep the scope/enforcement view reachable.
4. [Phase 04 — Read-only detail view](./todo-phase-04-detail-view.md) — card → detail (name, category, mode, example, what it does) with back control.

## Acceptance Criteria

- Opening the Skills tab on first run shows the built-in catalog grouped into
  **Audits / Build / Auto-fix**; the router and shared/core docs are **absent**.
- Each card shows name, short description, and a fixed mode label.
- Audit + build cards resolve to **on** by default; the auto-fix card
  (`penpot-rename-layers`) resolves to **off** by default.
- Clicking a card opens a **read-only** detail (name, category, mode, example trigger
  phrase, what-it-does) that replaces the list in the same tab (no modal, no new tab);
  a back control returns to the list.
- No toggling, editing, or skill-triggering is possible from the catalog or detail.
- The existing scope/enforcement view remains reachable.
- `make lint` / typecheck pass; skills-core unit tests cover derivation, exclusions, and
  default state.
