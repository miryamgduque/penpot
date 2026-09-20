# Phase 08 — `audit_file` tool

**Status:** done

## Goal

Port the `audit_file` tool: a native scan of the current page against the file's active rules, returning the open violations (rule, shape, reason). It grounds fix-up tasks and lets the agent verify its fixes cleared the list.

## Before Start

- [x] Re-read the plugin's `audit-file` (`ai-skills/src/plugin.ts:575-600`): walks `currentPage.root.children`, checks `token-only-colors` (via core `findDisallowedColors`) and `layer-naming` (`DEFAULT_NAME_RE`), caps ~1000 shapes / depth 6
- [x] Reuse the CLJS allowed-colors/disallowed logic from Phase 06 (in `agent_tools.cljs`: `allowed-colors`, `normalize-hex`, `rule-enforced?`)
- [x] Confirm page traversal natively: `dsh/lookup-page-objects`, drop root `uuid/zero`, iterate the flat objects map (no depth walk needed); default-name regex for `layer-naming`
- [x] Confirm the active rules come from `rule-enforced?` (`[:ai-panel <file-id> :enforced-rules]`) — the real per-file rules source is a Phase 10 deferral

## Checklist

- [x] `audit_file` tool (no input): scan the current page's shapes (cap at `max-audit-shapes` = 1000), apply the active auditable rules:
  - `token-only-colors` → `shape-raw-colors` on each shape's fills/strokes (raw hex not bound to a token ref and not in the allowed set)
  - `layer-naming` → shapes whose name matches `default-name-re`
- [x] Return `{:violations [{rule, shapeId, shapeName, reason}] :count :note}` (open violations only); feed the **open-violation count** back into `read_design` (`:openViolations` seam).
- [x] No persistent ledger/watcher in this phase (that's a deferred story) — `audit_file` is a fresh on-demand scan.
- [x] `clj-kondo` 0/0, `cljfmt` clean, `shadow-cljs compile main` → 0 warnings
- [x] Preview: rules off → 0 violations (no noise); enable both rules → 24 violations on the existing shapes (raw `#22c55e` fills + default "Rectangle" names), each with rule/shapeId/shapeName/reason; `read_design` `:openViolations` = 24 (matches). Live LLM turn: agent called `audit_file` and reported the 24-violation breakdown by rule.
- [x] Human approval; commit `:sparkles: Native audit_file tool`

## After Finish

- [x] Rename `todo-`→`done-`, update README link; note which rules are audited and the traversal caps used

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `audit_file`
- `frontend/src/app/main/data/workspace/agent.cljs` — register the tool; wire violation count into `read_design`

## Notes

- Auditable rules are a fixed set for the prototype (`token-only-colors`, `layer-naming`), same as the source. Generic rule-body-driven auditing is out of scope.
- The live change-watcher + persistent violations ledger + `skill-triggered` toasts (from `plugin.ts`) are a **separate later plan** — this phase only ports the on-demand scan.
