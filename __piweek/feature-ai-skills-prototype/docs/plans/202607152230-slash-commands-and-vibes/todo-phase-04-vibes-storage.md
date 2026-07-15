# Phase 04 — Vibes doc storage + prompt inlining

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Review dependencies are met (independent of 01–03)
- [ ] Read relevant source files to confirm assumptions (`dp/set-plugin-data`,
      `build-system-prompt`, changes pipeline behavior for `:file` type)

## Checklist

- [ ] Write/update tests: read/write round-trip against a state map, prompt
      section rendering (present/absent/size-capped)
- [ ] New ns `design_doc.cljs`: `get-doc state` (reads
      `[:files file-id :data :plugin-data :penpot-vibes "design-md"]`),
      `set-doc` / `clear-doc` events wrapping `dp/set-plugin-data`
      (`:file` type, string value; nil clears)
- [ ] Size guard: cap the stored doc (~4k chars) at the write boundary with a
      clear error — it is inlined into EVERY prompt; an unbounded doc is a
      standing tax on every turn
- [ ] `agent-tools`: `set_design_doc` tool (writes the full markdown doc;
      description says when to call it — at the end of a vibes interview or
      when the user asks to update the project's design direction) and
      include the current doc in `read_design`'s output so the agent can
      read it back
- [ ] `build-system-prompt`: inline the doc under a `## Project vibes
      (design.md)` heading with a line explaining it is the user's chosen
      direction and every design decision should honor it; empty/absent →
      section omitted. Note: this is the cached prefix — the doc is stable
      per file, so a rewrite per edit is acceptable (same deal as toggling
      a skill)
- [ ] Lint + typecheck pass
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/design_doc.cljs` — NEW: read/write/clear
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `set_design_doc`
  tool + `read_design` addition
- `frontend/src/app/main/data/workspace/agent.cljs` — system-prompt section
- `frontend/test/frontend_tests/data/design_doc_test.cljs` — NEW (runner
  `test-namespaces` vector!)

## Notes

- Plugin-data chosen over a backend table on purpose: it travels with the
  file (export/import), is shared with every collaborator, goes through the
  changes pipeline (undoable, synced live), and the external MCP door can
  read it too. A per-user backend table (like skill-state) would be the
  wrong sharing semantics for a project-level document.
- Namespace `:penpot-vibes`, key `"design-md"` — distinct from the old
  plugin's `penpot-skills` namespace so the two prototypes don't collide.
- `set-plugin-data` values must be strings (asserted) — store the raw
  markdown, no JSON envelope needed for a single doc.
