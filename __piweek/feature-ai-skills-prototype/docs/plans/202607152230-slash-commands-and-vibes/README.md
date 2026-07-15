# Slash commands + Project vibes interview

**Status:** doing
**Created:** 2026-07-15
**Apps:** `frontend`
**Dependencies:** None (builds on the shipped agent panel + skills catalog)

## Context

Two connected capabilities for the Agents panel:

1. **Slash commands in the chat composer.** Typing `/` opens an autocomplete
   menu listing every enabled skill (built-in + user-created) plus special
   commands. Picking a skill inserts/sends its trigger phrase; picking a
   command runs its flow. Today skills are only discoverable via the Skills
   view — the composer gives no hint they exist.

2. **A "project vibes" skill** (`/vibes`) that runs a structured interview —
   option chips, multi-select, free-text, "Decide for me", like a designer's
   kickoff questionnaire — and distills the answers into a `design.md`-style
   vibes document stored **on the design file** (plugin-data, shared with
   collaborators, synced, undoable) and **inlined into every system prompt**
   for that file. Vibes shape every response, so they live in the always-on
   layer, not behind a `get_design_skills` fetch.

The interview UI is deliberately generic: a new `ask_user` agent tool takes a
question schema and the transcript renders it as an interactive form. Any
skill can interview the user — vibes is just the first customer.

Decisions from the discovery interview (2026-07-15):
- **Interview UI:** generic in-chat form widget driven by an `ask_user` tool
  (not a hardcoded form, not plain conversational Q&A).
- **Vibes storage:** file-scoped via plugin-data, always inlined in the
  system prompt.
- **Slash menu:** all enabled skills + special commands.
- **Scope:** no demo pressure — build the full lifecycle (create, view,
  edit, re-run, delete) properly.

### Revised execution mode (user direction, 2026-07-15)

Executed in worktree `feature/vibes-slash-commands` (branched from
`feature/ai-skills-prototype`) while other sessions work the main checkout.
**No unit tests** — test checklist items are dropped. Each phase still gets a
container compile check (`shadow-cljs compile main` pointed at the worktree)
and a per-phase commit **without** a human-approval pause. When all phases
are done: stop, confirm with the user that no other session is mid-flight,
merge into `feature/ai-skills-prototype`, then live-verify everything in the
devenv (the deferred "test it" step — includes the Phase 05 end-to-end run
and the preview reviews skipped per phase).

### Key architecture facts (from codebase exploration)

- Composer: [`chat-tab*`](../../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs)
  holds `input*` locally; `skill-create-intent` already shows the
  intercept-before-send pattern the slash layer extends.
- Skill entries already carry `:example` (a natural trigger phrase) —
  exactly what picking a skill from the menu should insert
  ([agent_skills.cljs](../../../../../frontend/src/app/main/data/workspace/agent_skills.cljs)).
- The turn loop ([agent.cljs `run-turn`](../../../../../frontend/src/app/main/data/workspace/agent.cljs))
  executes tools as rx observables. `ask_user` publishes a pending form into
  app state and completes only when the user submits — **no turn-loop
  changes needed**. A cancel unsubscribes the tool observable; its teardown
  clears the form.
- File plugin-data write path: [`dp/set-plugin-data`](../../../../../frontend/src/app/main/data/plugins.cljs)
  (`:file` type) → changes pipeline → lands at
  `[:files file-id :data :plugin-data <ns> <key>]`. Values must be strings.
- System prompt ([`build-system-prompt`](../../../../../frontend/src/app/main/data/workspace/agent.cljs))
  is the cached prefix — stable content only. The vibes doc changes rarely
  (a cache rewrite per edit is fine), so it can be inlined.
- Generation pattern to copy: [skill_gen.cljs](../../../../../frontend/src/app/main/data/workspace/skill_gen.cljs)
  (one non-streaming `:ai-agent-round` completion, JSON envelope, pure
  parser) — though for vibes the agent itself synthesizes the doc inside
  the turn and saves it via a tool, so no separate generation call.

## Phases

1. [Phase 01 — Slash-command menu](./done-phase-01-slash-menu.md) — `/` autocomplete in the composer: enabled skills + command registry.
2. [Phase 02 — ask_user tool plumbing](./done-phase-02-ask-user-tool.md) — the elicitation tool: schema, pending-form state, submit/cancel resolution.
3. [Phase 03 — Elicitation form UI](./done-phase-03-elicitation-form-ui.md) — the in-transcript form: chips, multi-select, "Other…", "Decide for me", free text.
4. [Phase 04 — Vibes doc storage + prompt inlining](./done-phase-04-vibes-storage.md) — design-doc ns over plugin-data, `set_design_doc` tool, system-prompt section.
5. [Phase 05 — The vibes skill + /vibes](./done-phase-05-vibes-skill.md) — built-in skill that drives the interview and writes the doc; wire `/vibes`; live end-to-end verify.
6. [Phase 06 — Vibes lifecycle UI](./todo-phase-06-vibes-lifecycle.md) — view/edit/re-run/delete the doc from the panel; docs update.

## Acceptance Criteria

- Typing `/` in the composer opens a filterable menu of enabled skills and
  special commands, keyboard-navigable (↑↓/Enter/Esc), matching Penpot's DS.
- Picking a skill inserts its trigger phrase; picking `/vibes` starts the
  interview.
- The agent can call `ask_user` with a question schema; the transcript
  renders an interactive form (single/multi choice chips, "Other…" free
  text, "Decide for me", optional textareas); submitting resumes the turn
  with the answers as the tool result; cancelling the turn dismisses the
  form cleanly.
- Completing the vibes interview produces a design.md-style document saved
  on the file (visible to collaborators), inlined into the system prompt,
  and demonstrably shaping the agent's design output on Claude.
- The doc can be viewed, edited manually, regenerated (re-run interview)
  and deleted from the panel.
- Lint + typecheck green; new pure logic unit-tested; UI verified in the
  devenv preview.
