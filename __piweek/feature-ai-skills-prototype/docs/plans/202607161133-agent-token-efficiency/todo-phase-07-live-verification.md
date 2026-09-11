# Phase 07 — Live verification + docs

**Status:** todo

Before/after measurement of the whole stack in devenv, on a scripted scenario, with
the spend meter as the instrument. Every phase self-verifies with unit tests; this
phase proves the COMBINED claim (roughly an order of magnitude on a heavy session)
and closes the plan with the mandatory docs pass.

## Before Start

- [ ] Verify plan is still valid; note which phases actually landed (the plan
      allows the user to defer any of them — verify what exists)
- [ ] Devenv running (`/opt/homebrew/bin/bash ./manage.sh run-devenv`, check
      Docker memory first per CLAUDE/memory notes); demo login; Anthropic key in
      profile settings

## Checklist

- [ ] Define the scripted scenario ONCE, reusable pre/post: (a) "build a pricing
      screen" (multi-round build turn), (b) two follow-up turns touching the same
      shapes, (c) an audit sweep of a large file (exercises the scout), (d) let a
      turn run past the checkpoint threshold on purpose
- [ ] Baseline: run the scenario on the pre-plan commit, record the meter after
      each turn (requests · input · cached% · output · $)
- [ ] Post: same scenario on the final commit; record the same figures
- [ ] Assert: cached% ≥90% by round 3 of a turn (phase 01); turn 2+ input
      baseline shrinks vs pre (phase 02); checkpoint fires and Continue/Stop both
      behave (phase 03); a compacted session keeps working and the note renders
      (phase 04); the audit sweep arrives as one scout digest (phase 06)
- [ ] Watch for the scout over-delegation risk (phase 06 note) during (b)
- [ ] Write the numbers into this file's Notes — they are the demo/PR material
- [ ] Docs: update `ai-skills/README.md` (or wherever the agent architecture is
      documented on this branch) with the token-efficiency architecture: the
      caching layout, hygiene thresholds, checkpoint, compaction, scout; update
      `BRANCH_NOTES.md` if reviewer-relevant
- [ ] Completion summary in the plan README; statuses to done; move folder to
      `completed/`
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:memo:`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `ai-skills/README.md` / branch docs — architecture section
- This plan's `README.md` — completion summary

## Notes

- Meter caveat: it accumulates per SESSION (panel lifetime), so reset (Clear or
  reload) between scenario runs to keep figures comparable.
- If only a subset of phases landed, still run this phase for that subset and
  record which levers remain unpulled.

### Worktree verification pass (2026-07-16, phases 01–06)

Run in the devenv container against the `feature/token-efficiency` worktree
before merging back:

- `shadow-cljs compile test` — green, 0 warnings (882 files).
- `node target/tests/test.js` — 755 tests / 2508 assertions; **all
  token-efficiency tests pass**. 2 failures, both PRE-EXISTING in
  `workspace-skill-gen-test` (`clamp-category` "auto"→"Audits",
  `parse-generation` `:mode` nil) — files untouched by this plan, almost
  certainly fallout of the US #14 mode→reactive rename merge. Flagged
  separately; not this plan's to fix.
- `shadow-cljs compile main` — green, 0 warnings (1190 files).
- clj-kondo over the six changed files — 0 errors, 0 warnings.
- cljfmt — 2 files auto-fixed (whitespace), committed as `:art:`.

STILL OPEN for this phase: the live before/after meter measurement (needs the
user's Anthropic key + a live panel session) and the checkpoint/compaction/
scout preview review. `build-app-assets.js` must run once after merge for the
new SCSS classes (checkpoint row, message-note).
