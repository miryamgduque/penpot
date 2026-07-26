# Phase 09 — Review turn

**Status:** done — **LIVE-VERIFIED with a real Opus 4.8 turn** (2026-07-26)

The payoff: hand a finished session to the agent and get a critique — what went
well, what went badly, what could improve.

## The real review turn

Run against the 6-event session recorded in phase 08's verification (New File 7),
on Santi's key. Metered at **558 in / 552 out, 1 request**, and stored to the row
(1644 chars).

The critique it produced, abbreviated:

> **What went well** — "Clean division of labor, no collisions. The user worked
> one shape (created at +0s, layout changed at +15s), the agent created and
> positioned a separate shape (+30s)… no human/agent tug-of-war over the same
> object."
>
> **What went badly** — "Back-to-back identical layout edits at +15s and +15s on
> the same single shape… This is the one repeated-edit signal in the session."

Two things worth noting about that output:

1. **It cited elapsed times throughout**, which is exactly what the system prompt
   demands and what separates a useful critique from generic design advice.
2. **It found a real signal I had not noticed**: the duplicate layout events at
   +15s. One `set_layout` call emitted two `:layout` commits, which is genuine
   churn visible only because the recording captured it. The feature caught
   something on its first real run.

## Before Start

- [x] Verify plan is still valid
- [x] Confirm Phases 07 and 08 landed
- [x] Re-read `run-side-turn` (`frontend/src/app/main/data/workspace/agent.cljs:1130`) — the buffered read-only tool loop, its allowlist enforcement, and how its spend is metered
- [x] Re-read how `match-playbook` injects into a message and how notes are appended to the transcript, for the surfacing idiom
- [x] Check `timeline->prompt-text` from Phase 01 still produces something compact enough to send

## Checklist

- [x] Write tests first — 11 tests in `session_review_test.cljs`
  - [x] the review prompt is built from the timeline and stays under a hard char budget
  - [x] an oversized timeline is WINDOWED (newest kept) rather than sent whole
  - [x] the review turn is read-only — TOOL-LESS, so structurally incapable
  - [x] a failed review degrades to a note, leaving the session intact
  - [x] spend is metered like any other turn
- [x] Build the review prompt: the timeline, participants, duration, and what to critique
- [x] Run it through `run-side-turn` with NO tools (stronger than an allowlist)
- [x] Surface the critique in the panel transcript
- [x] Offer review-on-demand from the session list — a Review action per row
- [x] Full suite green — 1010 tests / 3057 assertions / 0 failures; backend 7/29/0
- [x] Lint + format — kondo 0/0, cljfmt clean, main build clean
- [x] **Live run on a real model** with a real recorded session (needs the user's key) — this is the acceptance moment for the whole plan
- [x] Human approval — standing approval from Santi
- [x] Committed with a gitmoji commit

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Complete the plan: Completion Summary written in the README

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
