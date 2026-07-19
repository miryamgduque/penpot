# Phase 06 — Parallel-call phrasing A/B + calls/round measurement

**Status:** todo

Kimi elevates parallel tool calls to "HIGHLY RECOMMENDED … very important to
your performance"; our equivalent is one sentence at the tail of
`native-tool-notes`. NYT session measured 1.24 calls/round BEFORE the
composition tools landed — this phase (a) writes the measurement recipe, (b)
takes a fresh baseline, and (c) strengthens the phrasing only if the baseline
still shows serial behavior. The measurement also gates phase 08.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Confirm transcripts are still readable from `profile_agent_chat`
      (`docker exec penpotdev-infra-postgres-1 psql -U penpot -d penpot`,
      transit-JSON `~:` keys — Kahoot-postmortem recipe)

## Checklist

- [ ] Write the measurement recipe as a doc section (in this phase file's Notes
      or a small `docs/measuring-agent-efficiency.md` beside the postmortems):
      how to pull a conversation's rounds, count tool_calls per assistant
      message, compute calls/round + rounds-per-deliverable; include a psql
      one-liner or a node snippet over the transit JSON
- [ ] Run a BASELINE build session (post-composition-tools, current phrasing) —
      a repeatable ask (e.g. re-run the NYT screen or a 4-card grid brief) on
      the demo file; record calls/round, rounds, $ from the meter
- [ ] Decide: if calls/round ≥ ~2 already, phrasing change is a no-op — record
      the numbers and close the phase (record WHY). Otherwise:
- [ ] Strengthen the batching line in `native-tool-notes` toward Kimi's
      emphasis: "You can emit ANY number of independent tool calls in one
      response — doing so is expected, and issuing them one at a time is the
      single most expensive habit you can have here."
- [ ] Re-run the same brief; record after-numbers next to before-numbers in
      the doc
- [ ] Lint + typecheck pass (if the prompt changed); prompt pin updated
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (e.g. `:zap: Stronger parallel-call
      doctrine, with before/after calls-per-round measurement`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Hand the numbers to phase 08 (its go/no-go input)
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — one line in
  `native-tool-notes` (conditional on baseline)
- `__piweek/feature-ai-skills-prototype/docs/` — measurement recipe + numbers

## Notes

- Needs the user's Anthropic key and a devenv session — schedule with Santi;
  the two live runs cost real money (budget ~$1–2 each on Sonnet).
- Keep the brief IDENTICAL between runs and pin the model (Sonnet 5), or the
  comparison is noise. One run each is weak evidence — say so in the doc; the
  point is direction, not significance.
- Anthropic-only, per the demo constraint.
