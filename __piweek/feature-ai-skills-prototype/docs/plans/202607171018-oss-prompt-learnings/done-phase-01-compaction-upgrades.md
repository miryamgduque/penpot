# Phase 01 — Compaction upgrades (Learned section + anchored re-compaction)

**Status:** done

Adopts Kimi CLI's compaction priority 2 ("Errors & Solutions — all encountered
errors and their resolutions; remove failed attempts, keep lessons learned") and
opencode's anchored summarization (`<previous-summary>` block: update the anchor,
don't re-describe it).

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Re-read `compact-system` (`agent.cljs:1206`) and `compaction-transcript`
      (`agent.cljs:989`) — confirm sections and the prior-summary passthrough
      are unchanged
- [ ] Confirm where compaction tests live (search `compact` in
      `frontend/test/frontend_tests/`) and that they run (runner vector trap)

## Checklist

- [x] Write/update tests: a transcript whose head starts with a
      `[Conversation compacted…]` message still round-trips through
      `compaction-transcript`/`compacted-history` (behavioral guard for the
      anchor path; prompt WORDING itself is not unit-testable — note the live
      check below)
- [x] Add `## Learned` section to `compact-system`: "tool calls that errored or
      were rejected and what resolved them; constraints discovered about this
      file or the tools (ordering rules, settle behavior, rule enforcement).
      Drop the failed attempts themselves — keep only the lesson."
- [x] Add anchor instruction to `compact-system`: "if the transcript opens with
      a bracketed summary of earlier messages, that is the previous compaction —
      UPDATE it (preserve still-true facts, drop stale ones, merge new ones)
      rather than describing it as part of the conversation."
- [x] Keep the whole prompt within its existing budget discipline (summary cap
      stays 3000 chars; raise ONLY if the Learned section demonstrably starves —
      decide with Santi, not unilaterally)
- [x] `summarize-history` shares `compact-system` — confirm the new sections
      read correctly for the fresh-chat handoff seed too (they should: handoff
      wants lessons even more)
- [x] Lint + typecheck pass (kondo via devenv container; `compile test` + run;
      also `compile main`)
- [ ] Live sanity (optional, needs user key): force a compaction
      (`store_history` console injection per memory recipe), read the summary,
      confirm a seeded tool-error lesson survives
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:brain:` or `:sparkles:` — e.g.
      `:sparkles: Compaction keeps learned constraints, updates prior anchor`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — `compact-system` string
  only (both `compact-history` and `summarize-history` consume it)
- `frontend/test/frontend_tests/data/…` — wherever the existing compaction tests
  live; extend, don't fork

## Notes

- The seam is deliberately narrow: `compaction-transcript` already carries a
  prior summary forward as USER text, so the anchor behavior is pure prompt.
  No plumbing, no schema, no migration.
- Haiku is the summarizer (`compact-model`) — keep instructions blunt and
  list-like; it follows structure better than nuance.
