---
name: planning-workflow
description: Use when creating, executing, or managing implementation plans. Enforces phased planning with checklists, test-first workflow, human approval gates, and status tracking via file renames.
---

<!-- TRANSIENT dev tooling — committed only so collaborators on this branch can
     see it. DELETE the whole `.claude/skills/` entry before the PR merges; it is
     not part of the Penpot product. -->

# Planning Workflow

Enforces a consistent approach to planning and executing multi-phase implementation work. Plans live in `__piweek/feature-ai-skills-prototype/docs/plans/` as folders with phase files that track execution progress.

## When This Skill Applies

- Creating a new implementation plan
- Starting execution of a plan phase
- Reviewing or updating plan status
- Moving completed plans to archive

---

## Plan Structure

> **Plans root:** `__piweek/feature-ai-skills-prototype/docs/plans/` (relative to the repo
> root). This is the canonical location for all new plans on this branch — **not** the repo's
> top-level `docs/`, which is Penpot's published documentation site. Existing sibling plans
> already live here; place new plans alongside them. Note code links from a plan's README reach
> the repo root via five `../` segments (e.g. `../../../../../ai-skills/...`).

Every plan is a **folder** in `__piweek/feature-ai-skills-prototype/docs/plans/`:

```
__piweek/feature-ai-skills-prototype/docs/plans/{YYYYMMddHHmm}-{plan-name}/
  README.md                        # Overview, status, phase index
  todo-phase-01-{name}.md          # Phase files prefixed with status
  todo-phase-02-{name}.md
  ...
__piweek/feature-ai-skills-prototype/docs/plans/completed/              # Archived plans (entire folders)
```

**Naming:** Timestamp prefix uses creation time (e.g., `202604121430-host-dashboard-kpis`).

---

## Creating a New Plan

### Step 0: Discovery Interview

**Before writing any plan files**, conduct a discovery interview with the user. This is mandatory — never skip straight to creating folders.

**Ask about:**
- **Scope** — What exactly needs to be built? What's explicitly out of scope?
- **Motivation** — Why now? What problem does this solve? Who benefits?
- **Approach** — Does the user have a preferred approach or architecture in mind?
- **Constraints** — Timeline pressure? Dependencies on other work? Technical limitations?
- **Priorities** — If we can't do everything, what's the must-have vs. nice-to-have?
- **Risks** — What could go wrong? What's the hardest part?

Use AskUserQuestion to gather this efficiently. Adapt questions to context — don't ask what's already obvious.

### Step 1: Codebase Exploration

**Before drafting phases**, read the relevant source code to ground the plan in reality.

- Read the files that will be modified or extended
- Check for existing patterns, utilities, or components that can be reused
- Verify assumptions about current state (don't plan based on memory alone)
- Note any surprises or conflicts with the planned approach
- If the codebase contradicts the plan's assumptions, flag to the user before proceeding

This step prevents plans that look good on paper but clash with the actual code.

### Step 2: Create the plan folder and README.md

```markdown
# {Plan Title}

**Status:** todo
**Created:** YYYY-MM-DD
**Apps:** `app1`, `app2`
**Dependencies:** None | Plan X (reason)

## Context

{Why this change is needed — the problem, what prompted it, intended outcome}

## Phases

1. [Phase 01 — {Name}](./todo-phase-01-{name}.md) — {one-line summary}
2. [Phase 02 — {Name}](./todo-phase-02-{name}.md) — {one-line summary}

## Acceptance Criteria

- {High-level success conditions}
```

### Step 3: Create phase files with `todo-` prefix

Each phase file follows this template:

```markdown
# Phase NN — {Phase Name}

**Status:** todo

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Review dependencies are met
- [ ] Read relevant source files to confirm assumptions

## Checklist

- [ ] Write/update tests for {feature}
- [ ] Implement {specific task 1}
- [ ] Implement {specific task 2}
- [ ] Lint + typecheck pass (`make lint/{app}`, `make typecheck/{app}`)
- [ ] Preview review with MCP tools (if frontend changes)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `path/to/file.ts` — {what changes and why}

## Notes

{Implementation decisions, gotchas, references to prototype, discoveries during execution}
```

### Phase Size Guidance

Each phase should be **reviewable in one sitting** — roughly:
- 1-3 files changed (up to ~5 for small changes)
- A single coherent feature or capability
- Testable and demo-able independently
- Committable as one gitmoji commit

If a phase feels too large, split it. If it feels trivial, merge it with the next.

---

## Phase Execution Protocol

When starting work on a phase:

### Step 1: Before Start (validation)
- Run the **Before Start** checklist in the phase file
- Read the relevant source files — don't rely on memory from plan creation
- If the plan is stale or assumptions are wrong, update the phase file before proceeding
- If significant drift is found, flag to the user before continuing

### Step 2: Rename file to `doing-`
```bash
mv __piweek/feature-ai-skills-prototype/docs/plans/{plan}/todo-phase-NN-{name}.md __piweek/feature-ai-skills-prototype/docs/plans/{plan}/doing-phase-NN-{name}.md
```
Update the README.md link to match.

### Step 3: Execute the checklist
Follow this order strictly:

1. **Tests first** — Write or update tests that will validate the phase's functionality
2. **Implement** — Make the code changes, checking off items as completed
3. **Lint + typecheck** — Run `make lint/{app}` and `make typecheck/{app}` for affected apps
4. **Preview review** — If frontend changes, use MCP preview tools to verify visually
5. **Human approval** — Stop and ask the user to review before committing. Show what changed and any preview screenshots
6. **Commit** — Use gitmoji commits: `:emoji: Capitalized summary` (e.g., `:sparkles: Add dashboard KPI cards`)

Update checklist items in the phase file **as you go**, not in batch.

### Step 4: After Finish
- Rename file to `done-` prefix
- Update README.md links
- Record any discoveries or follow-up items in the Notes section
- Assess if the next phase needs adjustment based on what was learned

---

## Handling Blocked Phases

When a phase hits a wall (dependency missing, unexpected complexity, external blocker):

### 1. Don't power through — stop and assess
- Document what's blocking in the phase's Notes section
- Keep the file as `doing-` (don't rename back to `todo-`)

### 2. Choose a resolution strategy

| Situation | Action |
|-----------|--------|
| **Missing dependency** | Pause this phase, flag the dependency. Create or prioritize the blocking work |
| **Phase too complex** | Split into sub-phases. Rename current to `done-` for what's complete, create new `todo-` for remainder |
| **Approach doesn't work** | Update the phase file with what was learned. Discuss alternative approach with user before continuing |
| **External blocker** | Document the blocker, mark phase as paused in Notes. Move to next independent phase if possible |
| **Scope creep** | Move new requirements to a new phase or separate plan. Don't expand current phase mid-execution |

### 3. Always communicate
Flag the situation to the user with:
- What's blocked and why
- What you've tried
- Proposed resolution (with options if applicable)

---

## Plan Lifecycle

```
todo  →  doing  →  done
```

**Plan-level status** (in README.md):
- `todo` — Plan created, no phases started
- `doing` — At least one phase in progress
- `done` — All phases completed

**Phase-level status** (file prefix):
- `todo-phase-NN-{name}.md` — Not started
- `doing-phase-NN-{name}.md` — Currently executing
- `done-phase-NN-{name}.md` — Completed

### Completing a Plan

When all phases are `done`:

1. **Update documentation** — This is mandatory. Update relevant docs:
   - `CLAUDE.md` if architecture, commands, or conventions changed
   - API docs if endpoints were added/modified
   - `docs/` files if flows, dataset, or architecture changed
   - README if setup steps changed

2. **Write a completion summary** in the README.md:

```markdown
## Completion Summary

**Completed:** YYYY-MM-DD

### What Shipped
- {Bullet list of what was actually delivered}

### What Changed from Original Plan
- {Any deviations from the original phases — scope additions, removals, approach changes}

### Lessons & Follow-ups
- {Anything discovered that affects future work}
- {Any deferred items that should become their own plan}
```

3. Update README.md status to `done`
4. Move the entire folder to `__piweek/feature-ai-skills-prototype/docs/plans/completed/`
5. Update any cross-references in other plan files

---

## Rules

- **Discovery interview before planning** — never create a plan without understanding scope and approach from the user
- **Explore the codebase before drafting** — plans must be grounded in the actual code, not assumptions
- **One `doing` phase at a time** per plan — finish before starting the next
- **Never skip Before Start** — plans created in past sessions may be stale
- **Never commit without human approval** — always stop and ask
- **Update checklists on the go** — not after the fact
- **Tests before implementation** — write tests that define expected behavior first
- **Gitmoji commits** — `:emoji: Capitalized summary` matching the project's convention. Common prefixes: `:sparkles:` new feature, `:bug:` bug fix, `:recycle:` refactor, `:fire:` remove code/files, `:lipstick:` UI/style, `:memo:` docs, `:truck:` move/rename, `:wrench:` config/tooling, `:white_check_mark:` tests
- **Update docs at plan completion** — documentation changes are mandatory, not optional
- **Keep phases reviewable** — if it's too big to review in one sitting, split it
