# Phase 05 — Chat entry point

**Status:** todo

## Goal

Let a user start creation **from Chat** by describing it naturally ("create a skill that checks my
copy's tone"). This **routes into the Skills-view guided flow** (Phase 04), carrying the description
over as the "what". Creation never happens inline in the main Chat conversation.

## Before Start

- [ ] Re-read how the agent turn runs (`agent.cljs run-turn`, `agent_tools.cljs` tool specs +
      `execute-tool`) and how the panel switches view (`ai-panel*` `open-skills` / `skill*`)
- [ ] Decide the mechanism (below) with the team before building

## Design decision (confirm first)

Two viable mechanisms — pick one:

- **A · Agent tool `create_skill`.** Add a tool the model calls when the user asks to create a skill;
  its result signals the panel to switch to `:skills` + open the create flow seeded with the user's
  description. Most "agentic", but needs a UI-side effect from a tool result (a channel from the turn
  runner to the panel) — more plumbing.
- **B · Lightweight intent route (recommended for the prototype).** Before/independent of the agent
  turn, detect a create-a-skill intent in the composer submit (a simple leading-phrase / keyword
  match, e.g. "create a skill…", "make a skill that…") and route to the Skills-view create flow with
  the text seeded — no model round-trip to start. Simpler, deterministic, no tool-result channel.

Default to **B** unless the team wants the tool. Record the choice here.

## Checklist

- [ ] Implement the chosen route: on a create-a-skill message, switch `ai-panel*` to `:skills` +
      `:create` and **seed the "what"** with the user's description (strip the leading "create a
      skill…" so only the substance seeds).
- [ ] The main Chat transcript is **not** used for the interview — the user lands in the Skills flow;
      optionally leave a small chat note ("Taking you to Skills to set that up") — confirm in review.
- [ ] Edge: intent detected but no provider connected → same guard as Phase 04 (explain, link to
      Integrations).
- [ ] `make lint` + frontend build, 0 warnings
- [ ] Verify live: typing "create a skill that checks my copy's tone of voice" in Chat lands in the
      Skills create flow with "check my copy's tone of voice" pre-filled; finishing yields the card.
- [ ] Human approval received
- [ ] Committed (`:sparkles:`)

## After Finish

- [ ] Rename `todo-` → `done-`; update README links
- [ ] Full-story acceptance pass (README) + completion summary; move plan to `docs/plans/completed/`

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` (+ `data/workspace/ai_panel.cljs` if the
  detection/route lives in the send path)

## Notes

- **Final phase of the story:** on completion run the plan-completion protocol (docs update +
  completion summary + move to `docs/plans/completed/`).
- Keeps the story's rule that **creation always happens in the Skills view**, regardless of entry.
