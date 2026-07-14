# Phase 08 — `audit_file` tool

**Status:** todo

## Goal

Port the `audit_file` tool: a native scan of the current page against the file's active rules, returning the open violations (rule, shape, reason). It grounds fix-up tasks and lets the agent verify its fixes cleared the list.

## Before Start

- [ ] Re-read the plugin's `audit-file` (`ai-skills/src/plugin.ts:575-600`): walks `currentPage.root.children`, checks `token-only-colors` (via core `findDisallowedColors`) and `layer-naming` (`DEFAULT_NAME_RE`), caps ~1000 shapes / depth 6
- [ ] Reuse the CLJS allowed-colors/disallowed logic from Phase 06 (`agent_guard.cljs`)
- [ ] Confirm page traversal natively: `refs/workspace-page-objects`, root `uuid/zero`, walk `:shapes`; default-name regex for `layer-naming`
- [ ] Confirm the active rules come from the Phase 07 resolved manifest (which rules are `triggered`/`enforced` and apply to audit)

## Checklist

- [ ] `audit_file` tool (no input): walk the current page's shapes (cap depth/count like the source), apply the active auditable rules:
  - `token-only-colors` → `find-disallowed-colors` on each shape's fills/strokes
  - `layer-naming` → shapes still carrying a default/auto name
- [ ] Return `[{rule, shapeId, shapeName, reason}]` (open violations only); feed the **open-violation count** back into `read_design` (Phase 02 seam).
- [ ] No persistent ledger/watcher in this phase (that's a deferred story) — `audit_file` is a fresh on-demand scan.
- [ ] `make lint/frontend`, `make typecheck/frontend`
- [ ] Preview: create a shape with a raw fill + a default-named layer, "audit this page" → both violations listed; apply a token + rename, re-audit → list clears.
- [ ] Human approval; commit `feat(workspace): native audit_file tool`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; note which rules are audited and the traversal caps used

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `audit_file`
- `frontend/src/app/main/data/workspace/agent.cljs` — register the tool; wire violation count into `read_design`

## Notes

- Auditable rules are a fixed set for the prototype (`token-only-colors`, `layer-naming`), same as the source. Generic rule-body-driven auditing is out of scope.
- The live change-watcher + persistent violations ledger + `skill-triggered` toasts (from `plugin.ts`) are a **separate later plan** — this phase only ports the on-demand scan.
