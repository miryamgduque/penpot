# Phase 04 — Skill bodies on demand

**Status:** done

## Goal

Close the progressive-disclosure gap. Today the index says a skill exists but there is nothing
to load — `get_design_skills` returns metadata only, so "the full playbook loads when relevant"
is a claim we have never actually made true. Ship real bodies, then measure whether the
mechanism works: does the index cause a fetch, and does the body change behaviour?

## Before Start

- [x] Re-read `ai-skills/scripts/import-aikit.mjs` — it already generates a body corpus for the
      MCP server (`skills-core/src/aikit.gen.ts`); this mirrors that for CLJS
- [x] Re-read `agent-skills/catalog-manifest` and `skill-manifest` (metadata-only today)
- [x] Re-read `agent-tools/get-design-skills` — the tool that must start returning bodies
- [x] Confirm Phase 03's split — bodies are only needed for what stayed **user-facing**
      (the 10 catalog skills). Phase 03 also handed this phase two extra jobs: the
      **fill-policy** and **token-modes** sections carved out of `modes-and-policies`, which are
      procedural and belong in a body — but are written against `applyToken()` / `clone()` /
      `createComponent()` / `theme.addSet()` and **must be reworked onto our native tool names
      before shipping**, or the agent will try to call plugin methods it does not have.
- [x] **Budget for the rework, not just the import.** Every workflow body carries plugin-API and
      MCP references too (measured: 1–16 `mcp` and 1–7 `plugin` mentions *per skill*, e.g.
      `penpot-migrate` has 16 MCP references). A verbatim `aikit.gen.cljs` import would inject
      exactly the wrong instructions into the agent that Phase 03 just finished stripping. Decide
      early: rework at import time, or filter at fetch time.
- [x] Check body sizes in the corpus: a playbook is thousands of tokens, which is exactly why
      it must not be always-on

## Checklist

- [x] Generate a CLJS body corpus (`aikit.gen.cljs` or equivalent) from `skills-core`, mirroring
      the existing mjs importer rather than hand-copying — it must stay regenerable
- [x] `get_design_skills` returns the **body** when asked for a named skill, and metadata when
      listing. Listing must stay cheap; only a named fetch pays for a body.
- [x] Cap / truncate defensively — done at GENERATION time (build fails loudly) rather than runtime (silent truncation): — one oversized body must not blow the turn's budget
- [x] **Experiment D — does the index cause a fetch?** Ask something a skill covers (e.g. an
      accessibility audit) and observe whether the agent calls `get_design_skills` before
      acting, or free-styles. Run with the skill enabled vs disabled.
- [x] **Experiment E — does the body change behaviour?** Same prompt, body available vs
      metadata-only. Compare: does it follow the playbook's actual steps?
- [x] Record token cost of a fetch — the whole point of disclosure is that it is paid only when
      relevant
- [x] `clj-kondo` 0/0, `cljfmt` clean, `shadow-cljs compile main` → 0 warnings
- [x] Human approval; commit `:sparkles: Serve skill bodies on demand via get_design_skills`

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] Close the corresponding item in the porting plan's
      [Phase 10 deferrals](../202607132113-port-ai-skills-to-cljs/todo-phase-10-deferred-followups.md)
- [x] Feed the fetch-rate finding into Phase 05 — if the agent never fetches, `index` is the
      wrong default and the load policy matters more than expected

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — body corpus + lookup
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `get_design_skills` returns bodies
- `ai-skills/scripts/import-aikit.mjs` — emit the CLJS corpus alongside the TS one

## Findings

### The corpus is uniformly templated — so the rework is mechanical, not prose surgery

Every kit skill uses the same 16-section template, and the wrong-for-us content is confined to
whole sections. A deterministic section strip (`import-aikit-cljs.mjs`) removes **36%** of the
corpus: the MCP/plugin-API tooling sections, plus **Modes & Policies** and **Naming Conventions**
— which are duplicated verbatim in every skill *and* already carried always-on by Phase 03's
`inner-knowledge`. Stripping them removes redundancy and gives governance one source of truth.
131,064 → 83,948 chars across 10 skills; kept: the method (workflow, critical rules,
checkpoints, anti-rationalization).

### Experiment D — does the index cause a fetch? **YES — and it exposed a real defect**

Asked *"Check this screen for accessibility problems"* (never naming a skill). The agent called
`get_design_skills` unprompted. But the first attempt was:

```
get_design_skills {"name": "Accessibility audit"}       → {"error": ...}
get_design_skills {"name": "penpot-audit-accessibility"} → ok
```

The routing index rendered `:label` and never the `:name` the tool keys on — **the index hinted
at a fetch without saying what to fetch by**. The agent recovered by intelligence, not design,
burning a whole round. Fixed: the index now leads with `` `name` `` and the tool's error returns
the valid names. Re-ran: **3 requests → 2, zero errors, correct name first try.**

### Experiment E — does the body change behaviour? **Yes, but only where it says something the model cannot know**

Two prompts, body available vs suppressed:

| Marker | accessibility audit | | token foundations | |
|---|---|---|---|---|
| | with body | no body | with body | no body |
| cites mode / read-only | ✓ | ✓ | ✓ | ✓ |
| WCAG specifics | ✓ | ✓ | — | — |
| **phased workflow** | — | — | **✓** | **✗** |
| **checkpoints** | — | — | **✓** | **✗** |
| `modes/light`+`dark` | — | — | ✓ | ✓ |

- **On accessibility the body added nothing measurable** (2314 vs 2135 chars, same substance).
  Opus knows WCAG cold, and Phase 03 already supplies mode + naming. An honest null result.
- **On foundations the body earned its keep**: it alone produced the phased workflow and the
  checkpoints. The conventions came from inner knowledge in *both* conditions.

**The lesson: Phase 03 stole the bodies' thunder, and that is fine.** By making governance,
naming and the token-set architecture always-on, we removed exactly the content that used to
make bodies valuable. What remains uniquely theirs is **procedure** — the order to work in and
where to stop. That is an argument for Phase 05's `load` policy having real work to do: skills
whose body is only convention may deserve `index`-only or `off`; skills with real procedure earn
the fetch.

### The preamble bug I introduced, and the fix

The first preamble said *"ignore any instruction to call … any plugin-API method"*. Too blunt:
the body's most valuable, least-knowable fact is **written as** an API call —

> *"Create the `primitives` set **and activate it** (`set.toggleActive()`; sets are created
> inactive)"* … *"the referenced set must be active or references fail validation"*

so the agent discarded a genuine Penpot ordering constraint along with the stale syntax
(`activationOrdering: false` even *with* the body). Rewrote the preamble to **separate the call
from the constraint** — "the call is stale; the fact is not". Re-ran:

> *"Sets are created inactive — each must be activated before anything references it."*

Constraint recovered, and **no leaked plugin call** (`toggleActive()` / `execute_code` absent
from the reply). This is the single most load-bearing sentence in the phase: a filter can drop a
section, but only the preamble can stop the model throwing out the baby with the bathwater.

### Cost

Listing stays metadata-only (the cheap menu). A named fetch is ~1,250 tokens for the
accessibility body, ~2,280 for foundations — paid only on the turn that fetches. Generation-time
guard fails the build if any body would exceed 85% of the 20,000-char tool-result cap, so a
playbook can never be silently truncated mid-method.

## Notes

- This is the phase where the hypothesis is most likely to bite back. Progressive disclosure
  assumes the model *reliably* fetches when the index hints. If Experiment D shows it usually
  does not bother, the honest conclusions are either "make the routing instruction stronger" or
  "some skills must be `always`, not `index`" — which is precisely Phase 05.
- Bodies are a big lever on quality and a big lever on cost. Measure both; do not assume a
  fetched body is free value.
