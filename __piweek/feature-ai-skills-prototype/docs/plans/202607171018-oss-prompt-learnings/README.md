# OSS Prompt Learnings — adopt agent-prompt patterns from Kimi CLI / opencode

**Status:** doing
**Created:** 2026-07-17
**Apps:** `frontend`
**Dependencies:** None (phase 08 is gated on phase 06's measurement)

## Context

Santi pulled the published system prompts of two OSS coding agents — MoonshotAI's
Kimi CLI (Apache-2.0) and sst/opencode (MIT, both its Claude and Codex variants plus
both compaction prompts) — and asked what the Penpot native agent could adopt. The
gap analysis (session 2026-07-17) found our architecture already covers their big
structural ideas (scope-cascaded skills, load-on-demand bodies, structured
compaction, parallel-call nudges), but identified nine adoptable learnings, most
of which target incidents already logged in our postmortems
(`../../kahoot-session-postmortem.md`, `../../nyt-session-postmortem.md`):

1. **Compaction keeps errors + lessons** (Kimi priority 2) — our `compact-system`
   loses learned constraints; post-compaction the agent can re-hit walls it climbed.
2. **Anchored re-compaction** (opencode `<previous-summary>`) — chained compactions
   today re-summarize the prior summary undirected.
3. **Self-maintaining foundations** (Kimi's AGENTS.md rule) — nothing tells the agent
   to update DESIGN.md/foundations when its changes contradict them.
4. **Scope minimalism** ("never give more than what they want") — targets the $4
   unrequested-landing-page incident; governance covers ambiguity, not overdelivery.
5. **ask_user question policy** (codex prompt) — the elicitation tool shipped with
   no doctrine on when/how to use it.
6. **Concurrent-work hygiene** (codex dirty-worktree rules) — "never touch existing
   work you didn't create, even to clean up"; undo-ownership is disclosive-only.
7. **Anti-generic design nudge** (codex frontend section) — no-DESIGN.md build
   requests are exactly when output collapses into bland defaults.
8. **Clickable shape refs** (opencode `file:line` pattern) — transcript shape
   mentions become ⌖-select links like the Audit tab's.
9. **Parallel phrasing A/B + todo tool** (Kimi emphasis / opencode TodoWrite) —
   measure-first items; NYT session measured 1.24 calls/round pre-composition-tools.

Plus a **survey phase**: mine other OSS agent projects the same way (Cline, Roo Code,
Aider, OpenHands, Gemini CLI, Codex CLI, Goose, Zed — and design-adjacent Onlook,
bolt.diy, Dyad).

### Grounding (verified against code 2026-07-17)

- System prompt is a **cached prefix — stable content only** (`agent.cljs:523-554`
  comment block). Anything conditional/per-turn (the phase-04 nudge) must ride the
  user message's volatile slot (`user-content`, `:playbook` precedent at
  `agent.cljs:49-66`), never the system block.
- `compact-system` at `frontend/src/app/main/data/workspace/agent.cljs:1206`
  (sections: Task / Done so far / Decisions / Open). `compaction-transcript`
  already feeds a prior `[Conversation compacted…]` message back through as plain
  USER text — the anchor instruction is purely a prompt edit, no plumbing.
- Inner-knowledge layers in
  `frontend/src/app/main/data/workspace/agent_skills.cljs`: `governance` (:222),
  `naming-conventions` (:248), `layout-doctrine` (:261), `visual-self-review`
  (:270), `native-tool-notes` (:276). Bar for this layer is high (file's own
  comment :210-213) — it is the only non-toggleable content.
- Markdown renderer `frontend/src/app/main/ui/components/markdown.cljs`:
  `safe-href` allowlist admits only `https?://|mailto:` (:30) — a shape-ref scheme
  must be added deliberately there, plus a click handler.
- ⌖ select precedent: `dws/select-shape` emitted at
  `frontend/src/app/main/ui/workspace/ai_panel.cljs:843` (Audit tab).
- Playbook injection: `match-playbook` (`agent.cljs:1186`) + the `:playbook` slot
  in `user-content` — phase 04 rides this exact path.
- Design-doc presence: `dd/system-prompt-section` (`app.main.data.workspace.design-doc`)
  already distinguishes has-doc/no-doc for the prefix.

### Known hazards (from branch memory)

- `shadow compile test` does NOT compile `ai_panel.cljs` — always also
  `compile main` (or kondo) before committing UI changes.
- `test/frontend_tests/runner.cljs` has a `test-namespaces` vector SEPARATE from
  its `:require` — new test namespaces must appear in BOTH or they silently don't run.
- Shared tree: other sessions stage work concurrently — `git status` immediately
  before every commit; **never `git add -A`**, stage explicit paths.
- SCSS edits need `node ./scripts/build-app-assets.js` in the container (watch
  misses them).
- Prompt-layer changes are cheap at runtime (cache read ≈ 0.1×) but every added
  line is re-read forever — keep additions terse; measured prefix is ~11k tokens.

## Phases

1. [Phase 01 — Compaction upgrades](./done-phase-01-compaction-upgrades.md) — `## Learned` section + anchored re-compaction in `compact-system` (learnings 1+2)
2. [Phase 02 — Inner-knowledge doctrine](./done-phase-02-inner-knowledge-doctrine.md) — self-maintaining foundations, scope minimalism, concurrent-work hygiene (learnings 3+4+6)
3. [Phase 03 — ask_user question policy](./done-phase-03-ask-user-policy.md) — usage doctrine for the elicitation tool (learning 5)
4. [Phase 04 — Anti-generic design nudge](./done-phase-04-anti-generic-nudge.md) — no-DESIGN.md build requests get a visual-direction nudge via the volatile slot (learning 7)
5. [Phase 05 — Clickable shape refs](./done-phase-05-clickable-shape-refs.md) — transcript shape references become ⌖-select links (learning 8)
6. [Phase 06 — Parallel phrasing A/B + measurement](./todo-phase-06-parallel-ab-measurement.md) — stronger batching language + calls/round measurement recipe (learning 9a)
7. [Phase 07 — OSS agent survey](./todo-phase-07-oss-agent-survey.md) — mine Cline/Aider/OpenHands/Gemini CLI/Codex CLI/Onlook/bolt.diy etc.; findings doc + ranked follow-up backlog
8. [Phase 08 — Todo tool decision](./todo-phase-08-todo-tool-decision.md) — **gated on phase 06 data**: spec + go/no-go for an opencode-style plan/todo tool (learning 9b)

Phases 01–03 are independent prompt edits (any order). Phase 04 depends on nothing
but reads cleaner after 02. Phase 07 can run any time, even first. Phase 08 must
follow 06.

## Acceptance Criteria

- Compaction summaries preserve mid-conversation lessons (tool errors + fixes,
  discovered file constraints) and chained compactions update rather than nest.
- Inner-knowledge carries the three new doctrine blocks without bloating the
  prefix (target: ≤ ~40 added lines total across phases 01–03; measure tokens).
- A build request on a file with no DESIGN.md surfaces a visual-direction nudge
  exactly once per conversation, riding the user message (cache-safe).
- A shape reference emitted by the agent is clickable in the transcript and
  selects the shape on canvas; unknown/deleted ids fail soft.
- A written measurement recipe exists for calls/round and was run at least once
  before/after the phrasing change.
- A survey findings doc exists with per-project takeaways ranked by leverage,
  and phase 08 has an explicit go/no-go with the evidence cited.
- 900+ frontend tests stay green; kondo/cljfmt clean; every phase its own
  gitmoji commit, human-approved before committing.

## Out of Scope

- Executing any of this now (plan-only session, Santi 2026-07-17).
- The `<system>`/`<system-reminder>` dual-channel convention (our tool-boundary
  enforcement is stronger), the AGENTS.md file convention itself (skills +
  foundations are our version), Kimi's directory-tree-in-prompt (our
  context-on-user-message is more cache-correct).
- Building the todo tool (phase 08 decides; building it would be its own plan).
- Any non-Anthropic provider verification (demo constraint stands).
