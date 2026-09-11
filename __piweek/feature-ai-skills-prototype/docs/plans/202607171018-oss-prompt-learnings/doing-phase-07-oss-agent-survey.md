# Phase 07 — OSS agent survey (beyond Kimi CLI / opencode)

**Status:** todo

Santi's ask: mine more OSS agent projects the way we mined Kimi CLI and
opencode. Research-only phase — deliverable is a findings doc ranked by
leverage (postmortem style), feeding a follow-up backlog. No code changes.

## Survey targets

**Coding agents (prompt + architecture patterns):**

- **Cline** (VS Code agent, Apache-2.0) — plan/act mode split, checkpoints,
  `.clinerules`, focus-chain. Their plan-mode is the closest OSS analogue to
  our suggest/apply-with-review governance.
- **Roo Code** (Cline fork, Apache-2.0) — the *modes* system (custom persona +
  toolset bundles per mode) — compare against our skills/playbooks axis.
- **Aider** (Apache-2.0) — the **repo map** (ranked, token-budgeted codebase
  summary — direct analogue to what read_design/explore_design feed the model),
  edit formats (diff vs whole-file), architect/editor two-model split
  (compare: our orchestrator/builder idea from the NYT strategy list),
  `CONVENTIONS.md`.
- **OpenHands** (ex-OpenDevin, MIT) — **microagents**: trigger-word-gated
  knowledge injection (their version of our playbook injection — compare
  trigger mechanics), plus their context condenser (compare our
  stub→compact→trim ladder).
- **Gemini CLI** (Google, Apache-2.0) — GEMINI.md hierarchy, their published
  system prompt's tone/structure, checkpointing.
- **Codex CLI** (OpenAI, Apache-2.0) — AGENTS.md layering, sandbox/approval
  model (compare our tool-boundary enforcement), their prompting guide.
- **Goose** (Block, Apache-2.0) — recipes (shareable task bundles ≈ skills),
  extension model.
- **Zed agent** (GPL) — profile system (write/ask/minimal tool profiles ≈ our
  side-turn read-only allowlist), their rules library.

**Design-adjacent (highest expected signal for US):**

- **Onlook** ("Cursor for designers", Apache-2.0) — an AI agent editing visual
  canvases directly; how they prompt for design edits, element selection
  (their chat references canvas elements — compare phase 05), style-system
  awareness.
- **bolt.diy** (StackBlitz's open bolt, MIT) — their design-quality doctrine
  and holistic-artifact rules; strong opinions on producing non-generic UI in
  one shot.
- **Dyad** (open-source AI app builder, Apache-2.0) — prompt structure for
  visual/app generation, review loops.

**Survey aids (use with care):** the community system-prompt collections on
GitHub aggregate many of the above; provenance is murky for non-OSS entries —
cite only prompts from the projects' own repos.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Skim the phases 01–06 outcomes (if done) so findings don't re-propose
      what already shipped

## Checklist

- [x] For each target: pull the ACTUAL prompt/config files from the repo (not
      blog posts), note license + commit/date pulled
- [x] Per project, extract: (a) anything that maps to a logged Penpot-agent
      incident, (b) anything structural we lack (à la microagents/repo-map),
      (c) explicit non-adoptions with reasons
- [x] Special attention: Onlook's canvas-element referencing & design prompts;
      Aider's repo-map budgeting vs our context/explore_design; OpenHands
      condenser vs our compaction ladder; Cline plan/act vs our governance
- [x] Write `__piweek/feature-ai-skills-prototype/docs/oss-agent-survey.md`:
      per-project sections + a single ranked adopt-list (leverage × cost, the
      postmortem format)
- [ ] Review findings with Santi; promote accepted items to a new plan (do NOT
      widen this one)
- [ ] Committed with a gitmoji commit (`:memo: OSS agent survey — findings and
      ranked adoptions`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `__piweek/feature-ai-skills-prototype/docs/oss-agent-survey.md` — new

## Notes

- Can run any time — no dependency on phases 01–06; it's also the natural
  background task while waiting on devenv/key availability for 04–06.
- Timebox: this can absorb infinite hours; aim for ~half a day of reading,
  breadth over depth, and let the ranked list decide where to go deep later.

- Executed 2026-07-19 as three parallel research subagents (coding agents / platform CLIs / design-adjacent); all 11 targets fetched from real repo files, caveats recorded (Cline monorepo rewrite, OpenHands microagents moved to agent-sdk). Findings doc committed; review-with-Santi pending — the ranked list ends in a plan-tool GO recommendation for phase 08.
