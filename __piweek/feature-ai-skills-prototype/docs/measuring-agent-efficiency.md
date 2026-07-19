# Measuring agent efficiency — calls/round and rounds-per-deliverable

**Created:** 2026-07-19 (phase 06 of the
[oss-prompt-learnings plan](plans/202607171018-oss-prompt-learnings/README.md)).

## Why these two numbers

- **calls/round** — tool calls per assistant message that called tools. The
  NYT postmortem measured **1.24** (near-serial); every extra round re-reads
  the whole cached prefix and history, so serial behavior is the dominant
  waste multiplier.
- **rounds-per-deliverable** — rounds for one comparable brief. This is what
  the user feels (wall clock) and what the phase-08 plan-tool decision turns
  on.

## Recipe

Conversations persist in `profile_agent_chat` (`data` jsonb: `~:usage` +
`~:history`, transit-verbose keys). Extract with
[scripts/measure-agent-efficiency.mjs](scripts/measure-agent-efficiency.mjs):

```bash
docker exec penpotdev-infra-postgres-1 psql -U penpot -d penpot -At -F $'\x1f' \
  -c "select id, title, data from profile_agent_chat order by updated_at desc limit 5" \
  | node __piweek/feature-ai-skills-prototype/docs/scripts/measure-agent-efficiency.mjs
```

Outputs per conversation: rounds, calls, calls/round, tool mix, token usage,
and an estimated cost (`MODEL=claude-sonnet-5` env var picks the price table —
the meter's own estimate in the panel is authoritative when models were mixed).

Caveats: one row = one conversation, which may span several user asks; the
estimated cost misprices mixed-model conversations; `requests` in usage counts
side-turns (playbook match, scout, compaction) that add no rounds.

## Historical baseline (all 15 stored chats, 2026-07-19)

Weighted mean across the 13 conversations with tool rounds:
**524 calls / 311 rounds ≈ 1.68 calls/round** (range 1.0–2.63; the 2.63
outlier is a token-heavy session where `create_token×58` was still issued
mostly one-per-round — its successor `create_tokens` batch tool landed after).
Notable rows:

| rounds | calls | c/r | est $ | brief (truncated) |
|---|---|---|---|---|
| 59 | 70 | 1.19 | 2.65 | vibes + home page (the $4 Haiku incident, pre-composition-tools) |
| 67 | 102 | 1.52 | 4.24 | screens inside flex board |
| 48 | 90 | 1.88 | 4.15 | pet-dating vibes + build |
| 49 | 129 | 2.63 | 1.62 | screens, token-heavy (pre create_tokens) |
| 16 | 31 | 1.94 | 2.14 | button component from URL |

Reading: composition tools moved the fleet from ~1.2 to ~1.5–1.9, but the
model still splits independent calls across rounds. Below the ≥2 bar the
phase-06 decision rule sets — **the A/B proceeds**.

## A/B protocol

- **Model pinned:** `claude-sonnet-5`. **File:** a fresh draft file per run
  (no foundations, no components — identical starting state).
- **Brief (identical, both runs):**
  > Build a pricing section for a productivity app: a heading, a one-line
  > subtitle, and a row of four pricing cards (plan name, monthly price,
  > three feature lines, a CTA button). Rough values are fine; don't ask me
  > questions, just build it.
- **A = current phrasing** (the one batching sentence at the tail of
  native-tool-notes). **B = survey best-of-breed** (Cline's emit-all-now +
  batching examples, Zed's maximize-parallel mandate — see the
  [OSS survey](oss-agent-survey.md), tier 1 item 1).
- Record: rounds, calls, calls/round, meter cost, wall clock, and whether the
  result actually renders as a plausible pricing section (a fast wrong build
  doesn't count).
- One run per arm is direction, not significance — say so wherever these
  numbers are quoted.

## Results

_(filled as runs complete)_

| run | phrasing | rounds | calls | c/r | $ | notes |
|---|---|---|---|---|---|---|
| A | current | — | — | — | — | — |
| B | upgraded | — | — | — | — | — |
