# Phase 03 — Inner knowledge vs. user-facing skills

**Status:** todo

## Goal

Apply the always-loaded ↔ load-on-demand axis to our own corpus. Some of what we inherited is
not a *skill a user chooses* — it is knowledge the agent must always have to be correct at all.
That becomes **inner knowledge**: reworked for the native agent (no MCP, no plugin API), folded
into the metaprompt as always-on, and **absent from the enable/disable UI**. The rest stay
user-facing skills the user can toggle.

## Before Start

- [ ] Re-read the aikit corpus split: six shared docs (`modes-and-policies`,
      `naming-conventions`, `tokens-schema`, `state-management`, `penpot-mcp-tool-reference`,
      `plugin-api-gotchas`) vs. the ten workflow skills in `agent-skills/catalog`
- [ ] Re-read `agent/build-system-prompt` — the operating modes are already distilled inline;
      this phase generalises that one-off into a real layer
- [ ] Re-read `agent-skills/system-prompt-section` (routing index) and `catalog-manifest`
- [ ] **Coordinate with Miryam** — inner knowledge disappearing from the Skills tab is a
      visible change to her US #7 surface

## Proposed split (confirm before implementing)

| Item | Disposition | Why |
|---|---|---|
| `modes-and-policies` | **Inner knowledge** | Governance must always hold; a user must not be able to switch off "ask before destructive changes". Already inline — formalise it. |
| `naming-conventions` | **Inner knowledge** | Shapes every response; cheap. |
| `tokens-schema` | **Inner knowledge** | The token-first bias depends on it (see Phase 06). |
| `state-management` | **Inner knowledge**, if it survives rework | Verify it still describes something true of the native agent. |
| `penpot-mcp-tool-reference` | **Drop from the embedded path** | Describes the MCP door the embedded chat does not use. Pure noise in our prompt. Stays for external MCP agents. |
| `plugin-api-gotchas` | **Rework or drop** | Describes the *plugin* API. Our native tools go through `pcb`/`commit-changes` and never touch it — this is now actively misleading. Replace with native-tool gotchas only if there are real ones worth carrying. |
| The 10 workflow skills | **User-facing** | Genuine choices: audits, build playbooks, auto-fix. |

## Checklist

- [ ] Confirm the split above with the user (the `state-management` and `plugin-api-gotchas`
      rows are the uncertain ones)
- [ ] Introduce an **inner-knowledge layer** in `agent-skills` distinct from `catalog`: always
      loaded, no `:enabled` flag, never returned by `catalog-manifest`
- [ ] Rework each retained doc for the native agent: **no MCP mentions, no plugin-API mentions**
- [ ] `build-system-prompt` composes: persona → operating modes → inner knowledge → skills
      routing index. Stable content only (Phase 02 moved context out).
- [ ] Verify the Skills tab shows only user-facing skills — inner knowledge is invisible and
      untoggleable
- [ ] **Measure the cost of the layer.** Always-on means always paid (albeit cached). Record
      the system-prompt token count before/after; if it balloons, that is an argument for
      Phase 05's `gated` policy rather than for cutting the knowledge.
- [ ] **Check whether growth pushes us past the cacheable minimum** — see the note below; if it
      does, record the before/after `% cached` on **haiku** as well as opus, because that is
      where the win would show up.
- [ ] Confirm `% cached` is unaffected — inner knowledge is static, so it belongs in the cached
      prefix and should cost ~10% on re-read
- [ ] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [ ] Human approval; commit `:recycle: Split agent inner knowledge from user-facing skills`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] Note the final split — Phase 04 only generates bodies for what stayed user-facing

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — inner-knowledge layer + catalog
- `frontend/src/app/main/data/workspace/agent.cljs` — `build-system-prompt` composition
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — Skills tab (should need no change if
  it renders `catalog`; verify)

## Cost of the always-on layer

| | Tokens | Note |
|---|---|---|
| System prompt before | | |
| System prompt after | | |
| Cached re-read cost | | ~10% of the above |

## Notes

- The interesting tension: inner knowledge is the *most* valuable content and the only content
  the user cannot turn off, so it is also the easiest place to quietly bloat every single
  request. The discipline is that it must earn "always" — anything procedural belongs in a
  skill body (Phase 04), not here.
- **Phase 01 inverted one assumption here, and it is worth stating plainly: on the caching
  axis, a *bigger* always-on layer is better.** Anthropic's minimum cacheable prefix is ~4096
  tokens for Opus 4.8 / Haiku 4.5; our tools+system prefix is only ~2.5k, and haiku
  consequently **never caches at all** (measured: 3 identical turns, `cache-read 0` every
  time). Growing the stable layer pushes the prefix clear of that floor, so the knowledge added
  here could turn caching on for models that get none today — and cached tokens re-read at
  0.1×. Do not reflexively minimise this layer to "save tokens"; below the floor you pay
  **full price for everything, every turn**. Measure, don't assume.
- Dropping `penpot-mcp-tool-reference` from the embedded path is not deleting it: the MCP
  server still serves external agents and still needs it. This is about which corpus the
  *embedded* agent's prompt is built from.
