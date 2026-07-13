<!-- TRANSIENT — delete before the PR is finalized. Working analysis for the team. -->

# Which skills are default (meta prompt) vs invoked on demand

Question raised on the branch: of the skills we ship, **which should always sit in the agent's
system prompt**, and **which should be fetched only when a task needs them?** Right now the split is
*hardcoded by scope/kind* in `ai-skills/src/ui/agent.ts` → `buildSystemPrompt`. This note proposes
making it an explicit, author-controlled property instead, and categorizes the current ai-kit set.

## How it works today

`buildSystemPrompt` injects, on **every** turn:
- **Platform skills** → a routing **index** (name + one-line description). Bodies fetched on demand via `get_design_skills({name})`.
- **Rules** (any scope) → a **manifest** (name / enforcement / mandatory / scope / description).
- **Local (org/project/file) skills** → **full body, inlined**.
- **Operating modes** → distilled inline (hardcoded text, not read from the skill).
- **Gated exception:** the prompt tells the agent to fetch `penpot-plugin-api-gotchas` before its first mutating `execute_code`.

So "default vs invoked" is currently decided by *where a skill lives* (platform = indexed, local = inlined), not by *what the skill is for*. That's the thing to fix.

## The problem with scope-decides-loading

- **Noise:** `penpot-mcp-tool-reference` documents the four **external** MCP tools. The embedded chat has its own tools — this skill is dead weight in its prompt (it belongs only on the MCP path).
- **Redundancy:** `penpot-operating-modes` is distilled inline **and** listed as a fetchable platform skill. `penpot-router`'s job (pick the right skill) is largely what the system prompt already does.
- **Missed defaults:** `penpot-naming-conventions` is small and applies to almost every create/edit, but it's only indexed — the agent has to know to fetch it. It's a better *always-on* than most.
- **Bloat risk:** local skills are inlined in full regardless of size. A team that authors ten org skills silently balloons every prompt.

## Proposal: a per-skill `load` strategy

Add one field to the skill model (frontmatter + DB column + dashboard control), independent of scope/kind:

| `load` | Meaning | Use for |
|--------|---------|---------|
| `always` | Full body injected into the system prompt every turn | Small, universal governance/conventions |
| `index` (default) | Name + description in the routing manifest; body fetched on demand | Large, task-specific playbooks |
| `gated` | Not injected; the agent is told to fetch it before a specific action | Big-but-critical, needed only at a moment |
| `off` | Never offered to the embedded chat (may still apply on the MCP path) | External-only material |

`buildSystemPrompt` then reads `load` instead of branching on scope. Authors/teams decide per skill in
the dashboard — e.g. a team can promote its house style to `always`, or demote a rarely-used playbook
to `index`.

## Suggested defaults for the current ai-kit set

**`always` — belongs in the meta prompt (small, universal):**
- `penpot-operating-modes` — governance (suggest / review / auto-fix); already effectively always-on, make it authoritative and stop double-listing it.
- `penpot-naming-conventions` — token/component/layer naming; short, applies to nearly every task.
- `a11y-contrast` (rule, mandatory) — the contrast floor; tiny, always relevant.
- File-scope conventions the file declares (e.g. `token-only-colors`, `layer-naming`) — already surfaced via the rules manifest; keep.

**`index` — routing manifest, fetch on demand (large playbooks):**
- `penpot-foundations`, `penpot-component-factory`, `penpot-build-screen`, `penpot-build-from-code`,
  `penpot-document-handoff`, `penpot-audit-accessibility`, `penpot-audit-tokens`,
  `penpot-design-to-code-review`, `penpot-migrate`, `penpot-rename-layers`.
- `penpot-router` — keep as an index entry or drop; the embedded prompt already routes.

**`gated` — fetch at the right moment:**
- `penpot-plugin-api-gotchas` — before the first mutating `execute_code` (already done; formalize it as `load: gated` with a trigger of `first-mutation`).

**`off` for the embedded chat:**
- `penpot-mcp-tool-reference` — external MCP agents only.

## Why this matters

- **Token budget:** the meta prompt stays lean and predictable; big playbooks load only when their task is active.
- **Control:** teams tune what's always-on per skill from the dashboard, instead of us guessing in code.
- **Correctness:** MCP-only material stops polluting the embedded agent, and universal conventions stop depending on the agent remembering to fetch them.

## Smallest useful first step

1. Add `load` to the skill frontmatter + `design_skill` table + the dashboard form (a select).
2. Have `buildSystemPrompt` respect it (fall back to `index`).
3. Set the defaults above on the seed. Mark `penpot-mcp-tool-reference` as `off`, promote
   `penpot-naming-conventions`/`penpot-operating-modes` to `always`.

Open question for the team: should `load` be **overridable per scope** (a team forces a platform skill
to `always` for itself) — i.e. does it cascade like enforcement? Leaning yes, same mechanism as the
enable/disable overrides.
