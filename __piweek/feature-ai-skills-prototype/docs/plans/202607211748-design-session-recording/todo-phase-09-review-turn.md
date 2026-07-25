# Phase 09 — Review turn

**Status:** todo

The payoff: hand a finished session to the agent and get a critique — what went
well, what went badly, what could improve. Reuses the existing side-turn
machinery rather than inventing a second agent path.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Confirm Phase 07 (and ideally 08) landed
- [ ] Re-read `run-side-turn` (`frontend/src/app/main/data/workspace/agent.cljs:1130`) — the buffered read-only tool loop, its allowlist enforcement, and how its spend is metered
- [ ] Re-read how `match-playbook` injects into a message and how notes are appended to the transcript, for the surfacing idiom
- [ ] Check `timeline->prompt-text` from Phase 01 still produces something compact enough to send

## Checklist

- [ ] Write tests first
  - [ ] the review prompt is built from the timeline and stays under a hard char budget
  - [ ] an oversized timeline is summarized/windowed rather than sent whole
  - [ ] the review turn is read-only — it must not be able to mutate the file
  - [ ] a failed review degrades to a note, leaving the session intact
  - [ ] spend is metered like any other turn
- [ ] Build the review prompt: the timeline, participants, duration, and what to critique
- [ ] Run it through `run-side-turn` with a read-only allowlist
- [ ] Surface the critique in the panel transcript as a distinct row
- [ ] Offer review-on-demand from the session list (not only at Stop)
- [ ] Full suite green
- [ ] Lint + format
- [ ] **Live run on a real model** with a real recorded session (needs the user's key) — this is the acceptance moment for the whole plan
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Complete the plan: update docs, write the Completion Summary, move the folder to `completed/`

## Files

- `frontend/src/app/main/data/workspace/session_review.cljs` — new; prompt construction + turn
- `frontend/src/app/main/data/workspace/agent.cljs` — only if the side-turn signature needs widening
- `frontend/test/frontend_tests/data/session_review_test.cljs` — new

## Notes

**Read-only is a hard constraint.** A reviewing agent with mutation tools will
"helpfully" fix what it criticizes, which corrupts the very file under review
and makes the critique unfalsifiable. The allowlist is enforced at construction
AND per call in `run-side-turn` — keep both.

Budget is the practical risk. A long session's timeline can be large, and the
4M payload cap has aborted turns on this branch twice. Window or summarize
before sending, and say in the output when the review saw only part of the
session — a critique of a truncated record presented as complete is exactly the
failure mode this plan exists to catch in *humans*.

Worth being honest about in the output: the critique is one model's opinion of a
compressed record of what happened. Useful, not authoritative. The prompt should
ask for specific, evidence-cited observations ("at 4:12 the same shape was moved
six times") rather than generic design advice, which is what makes it worth
reading at all.

Consider whether the critique should be stored back onto the session (a
`review` column) so it can be re-read without re-spending. Cheap to add here,
annoying to retrofit.
