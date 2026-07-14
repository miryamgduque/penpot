# Phase 04 — Skill bodies on demand

**Status:** todo

## Goal

Close the progressive-disclosure gap. Today the index says a skill exists but there is nothing
to load — `get_design_skills` returns metadata only, so "the full playbook loads when relevant"
is a claim we have never actually made true. Ship real bodies, then measure whether the
mechanism works: does the index cause a fetch, and does the body change behaviour?

## Before Start

- [ ] Re-read `ai-skills/scripts/import-aikit.mjs` — it already generates a body corpus for the
      MCP server (`skills-core/src/aikit.gen.ts`); this mirrors that for CLJS
- [ ] Re-read `agent-skills/catalog-manifest` and `skill-manifest` (metadata-only today)
- [ ] Re-read `agent-tools/get-design-skills` — the tool that must start returning bodies
- [ ] Confirm Phase 03's split — bodies are only needed for what stayed **user-facing**
- [ ] Check body sizes in the corpus: a playbook is thousands of tokens, which is exactly why
      it must not be always-on

## Checklist

- [ ] Generate a CLJS body corpus (`aikit.gen.cljs` or equivalent) from `skills-core`, mirroring
      the existing mjs importer rather than hand-copying — it must stay regenerable
- [ ] `get_design_skills` returns the **body** when asked for a named skill, and metadata when
      listing. Listing must stay cheap; only a named fetch pays for a body.
- [ ] Cap / truncate defensively — one oversized body must not blow the turn's budget
- [ ] **Experiment D — does the index cause a fetch?** Ask something a skill covers (e.g. an
      accessibility audit) and observe whether the agent calls `get_design_skills` before
      acting, or free-styles. Run with the skill enabled vs disabled.
- [ ] **Experiment E — does the body change behaviour?** Same prompt, body available vs
      metadata-only. Compare: does it follow the playbook's actual steps?
- [ ] Record token cost of a fetch — the whole point of disclosure is that it is paid only when
      relevant
- [ ] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [ ] Human approval; commit `:sparkles: Serve skill bodies on demand via get_design_skills`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] Close the corresponding item in the porting plan's
      [Phase 10 deferrals](../202607132113-port-ai-skills-to-cljs/todo-phase-10-deferred-followups.md)
- [ ] Feed the fetch-rate finding into Phase 05 — if the agent never fetches, `index` is the
      wrong default and the load policy matters more than expected

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — body corpus + lookup
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `get_design_skills` returns bodies
- `ai-skills/scripts/import-aikit.mjs` — emit the CLJS corpus alongside the TS one

## Findings

| Experiment | Condition | Fetched? | Followed playbook? | Tokens | Note |
|---|---|---|---|---|---|
| D | skill enabled | | | | |
| D | skill disabled | | | | |
| E | body available | | | | |
| E | metadata only | | | | |

## Notes

- This is the phase where the hypothesis is most likely to bite back. Progressive disclosure
  assumes the model *reliably* fetches when the index hints. If Experiment D shows it usually
  does not bother, the honest conclusions are either "make the routing instruction stronger" or
  "some skills must be `always`, not `index`" — which is precisely Phase 05.
- Bodies are a big lever on quality and a big lever on cost. Measure both; do not assume a
  fetched body is free value.
