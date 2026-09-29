---
name: creating-taiga-cards
description: Create Taiga kanban user-story cards in the real, logged-in Taiga board (e.g. from a roadmap slide) by driving the actual Chrome tab with claude-in-chrome. Triggers: "create Taiga cards", "add a user story to the kanban board", "add these roadmap items to Taiga".
---

<!-- TRANSIENT dev tooling — committed only so collaborators on this branch can
     see it. DELETE THIS SKILL'S OWN DIRECTORY before the PR merges; it is not
     part of the Penpot product. Do NOT delete `.agents/skills/` or the
     `.claude/skills` symlink: those are upstream's and hold real project
     skills. The branch's transient ones are the directories carrying this
     banner. -->

# Creating Taiga kanban cards via Claude-in-Chrome

Add user-story cards to your real, logged-in Taiga board (e.g. from a roadmap slide) by
driving your actual Chrome tab with the `claude-in-chrome` tools. Taiga is an AngularJS app,
which changes how you must type.

## Setup
- Load the tools first (one call): `ToolSearch "select:mcp__claude-in-chrome__tabs_context_mcp,mcp__claude-in-chrome__navigate,mcp__claude-in-chrome__computer,mcp__claude-in-chrome__find"` (add `browser_batch` for speed).
- Confirm you're on the kanban tab and note its `tabId`.

## Reliable flow — repeat per card
1. **Re-find a FRESH ref** for the "+" (Add new user story) button in the target column **before every card**:
   `find {query:"Add new user story plus button in the NEW column header"}`. The board re-renders after each card is created, so a ref reused from the previous card points at a dead element and the form silently won't open.
2. **Open**: click the ref → `wait ~1.5s` → **screenshot to confirm** the "New user story" lightbox is actually open (it fades in over ~1s).
3. **Fill by coordinates** (only after the form is confirmed open): click the Subject field, type; click the Description field, type. **Type real keystrokes** — `form_input` can be ignored by AngularJS `ng-model`, leaving the field visually filled but empty on submit.
4. **Create**: click CREATE → `wait ~1.3s` → **screenshot to verify**: the "Everything is ok" toast, the new card in the column, and the column count incremented.

Batch steps with `browser_batch` where there's no dependency; keep a screenshot checkpoint after opening and after creating.

## Gotchas
- **Never reuse a ref across cards** — re-`find` each time.
- **Coordinates drift if the viewport resizes** mid-flow (the pane can resize between calls); if a field looks unfilled, re-screenshot and re-read positions before retrying.
- **Fill only after the lightbox is confirmed open** — a subject click during the fade lands on nothing, and CREATE then fails silently (subject is required), leaving no card and no error.
- If a stray character sneaks into a typed field, focus it and send `Backspace` keys, then re-verify with a `zoom` on that region.
- The `claude-in-chrome` MCP must be connected for this session; if it isn't, the tools won't resolve.
