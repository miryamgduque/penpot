# Phase 05 — Chat entry point

**Status:** done

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

**Chosen: B (lightweight intent route).** Deterministic, no tool-result channel from the turn runner
to the panel; matches the story's "typed naturally" without a model round-trip to *start*.

## Checklist

- [x] Implemented route B: `skill-create-intent` matches a leading "create/make/build/add/set up
      [a] [new] skill [that/to/…]" and returns the trailing "what" (possibly empty). `chat-tab*`'s
      `send` hands off via a new `on-create-skill` prop instead of sending to the agent; `ai-panel*`
      sets `seed*`, opens `:skills` + `:create`, and passes `seed` to `skill-create*`.
- [x] The main Chat transcript is **not** used — the user lands in the Skills flow. No chat note
      (kept minimal; the view switch is the feedback).
- [x] Edge: intent routes regardless of provider; the create flow's own no-provider guard (Phase 04)
      then explains + links to Integrations. Anchored `^` regex avoids false positives on questions
      ("how do I create a skill?" → normal chat).
- [x] `clj-kondo` 0 errors; `:main` build 0 warnings
- [x] Verify live (confirmed in the preview: button-created skill renders in the list; broadened intent matcher + refetch-before-close fix): typing "create a skill that checks my copy's tone of voice" in Chat lands in the
      Skills create flow with "checks my copy's tone of voice" pre-filled — **user's browser check**
- [x] Human approval received
- [x] Committed (`:sparkles:`)

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
