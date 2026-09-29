# Built-in skills ship with the panel (US #7)

**Status:** ✅ **done** — Phases 01, 03, 04, 05 built; 02 closed as out-of-scope. React `ai-skills` catalog kept as a prototype (not retired). See Completion Summary.
**Created:** 2026-07-14
**Completed:** 2026-07-14
**Apps:** `frontend` (native workspace panel), `ai-skills` (React prototype), `skills-core`
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

- The Skills tab **already exists** ([`ai-skills/src/ui/App.tsx`](../../../../../../ai-skills/src/ui/App.tsx) →
  [`Skills.tsx`](../../../../../../ai-skills/src/ui/Skills.tsx)) but renders a **scope/enforcement**
  model (Effective / platform / org / project / file tabs, enforcement badges, inline
  toggles + editors) — *not* the story's catalog view.
- **Modes already live in frontmatter** of the bundled skills
  ([`skills-core/src/aikit.gen.ts`](../../../../../../skills-core/src/aikit.gen.ts)):
  `mode: suggest` (audits + router), `mode: review` (build), `mode: autofix`
  (rename-layers). But [`parse.ts`](../../../../../../skills-core/src/parse.ts) currently **ignores
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
2. [Phase 02 — Enabled state + payload](./done-phase-02-enabled-and-payload.md) — ✅ **closed as out-of-scope**: story defers toggling/persistence to #8; the static default is already satisfied by Phase 01/05 (no new code).
3. [Phase 03 — Catalog list UI (React prototype)](./done-phase-03-catalog-list-ui.md) — grouped cards in the standalone `ai-skills` panel. ✅ **done** (live-verified)
4. [Phase 04 — Read-only detail view](./done-phase-04-detail-view.md) — card → detail (category, name, mode, example, what it does) with back control, built in the **native CLJS panel**. ✅ **done** (live-verified in devenv)
5. [Phase 05 — Native CLJS catalog port](./done-phase-05-cljs-catalog-port.md) — the catalog in the real `frontend/` workspace Agents panel (replaces the Skills-tab placeholder). ✅ **done** (live-verified in devenv)

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

## Completion Summary

**Completed:** 2026-07-14

### What Shipped
- **Catalog model** (skills-core, Phase 01): parses `mode`, derives category / example / default-enabled,
  excludes router + shared docs; 31 unit tests. Consumed by the React panel.
- **React `ai-skills` catalog** (Phase 03): grouped cards + curated copy in the standalone panel —
  kept as a prototype.
- **Native CLJS catalog** in the real workspace Agents panel (Phase 05): Audits/Build/Auto-fix cards,
  colored mode badges, rename-layers off by default; replaces the Skills-tab placeholder.
- **Native CLJS read-only detail view** (Phase 04): card → detail (category overline, name, mode
  badge, example trigger phrase, what-it-does) with an "All skills" back control; in-place same-tab
  swap; strictly read-only.
- Catalog data shared with the agent via `frontend/.../agent_skills.cljs` (`ask/catalog`) — one
  source for the Skills tab and the agent's routing index / `get_design_skills`.

### What Changed from Original Plan
- **Phase 02 dropped as out-of-scope.** The plan assumed a persisted enabled-state layer, but the
  story defers on/off toggling and persistence to #8. Only the static default is in scope (already
  covered by Phase 01/05). No code.
- **Native CLJS became the real home.** The plan was drafted around the React `ai-skills` panel, but
  the devenv workspace uses the native cljs Agents panel (its Skills tab was a placeholder). Phases
  04/05 were built there; the React version (Phase 03) is kept, not retired (product-owner decision).
- **Resequenced** — 05 (native port) and 04 (detail) followed 03, sourcing the pure catalog directly.

### Lessons & Follow-ups
- **rumext compiles only *literal* hiccup** — a helper `defn` returning a hiccup vector crashes React
  ("Objects are not valid as a React child"); extract shared bits as `mf/defc` components. (One
  crash-and-fix in Phase 04.)
- **Devenv CSS glob is startup-bound** — a namespace scss added after `watch.js` started never lands
  in `main.css` until a full re-glob (touch a file under `resources/styles`, or a cold `run-devenv`).
- **Claude-in-Chrome can't open localhost/LAN** (blocks by resolved IP) — verify the devenv via the
  Preview tool instead. Saved to memory.
- **Follow-up (not blocking):** catalog copy is duplicated between skills-core (TS) and
  `agent_skills.cljs` (CLJS). A generated `aikit.gen.cljs` would give a single source. Deferred.
- **Follow-up:** bring full skill bodies into CLJS so `get_design_skills` returns more than metadata
  (noted in `agent_skills.cljs`).
