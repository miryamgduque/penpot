# Phase 05 — Per-skill load policy

**Status:** todo
**Informed by:** ✅ Phase 04 measured it. The agent **does** reliably fetch on hint (unprompted,
first-try correct once the index carries the `name`), so this phase is a refinement, not a fix —
`index` is a safe default. But Phase 04 also found the thing that gives this phase real work:
**the body's value is wildly uneven across skills.** On a domain the model knows (accessibility)
the fetched body changed nothing measurable; on Penpot-specific procedure (foundations) it alone
produced the phased workflow and checkpoints. So the interesting policy question is no longer
"how does a skill load" but **"does this skill's body say anything the model doesn't already
know?"** — a skill that is only convention is already covered by `inner-knowledge` and may
deserve `off`/`index`; a skill with real procedure earns the fetch. Decide per skill, with
evidence, rather than assigning a field by taste.

## Goal

Stop hardcoding *how* a skill loads. Today the decision (routing index vs. full body vs.
always-on) is made by code and by scope. Give each skill a `load` field so authors — and
eventually the dashboard — control disclosure:

- `always` — inlined every turn (reserve for short, universal rules)
- `index` — name + blurb in the routing index, body on demand (the default for playbooks)
- `gated` — body must be fetched before a related action (e.g. before the first mutation)
- `off` — not offered to the embedded agent at all

This is the design-decision captured back on 2026-07-13 in `skill-loading-strategy.md`, now
with Phase 04's evidence behind it.

## Before Start

- [ ] Re-read `__piweek/feature-ai-skills-prototype/skill-loading-strategy.md` — the original
      analysis and the suggested per-skill defaults
- [ ] Re-read Phase 04's Findings — the fetch rate decides whether `index` is a safe default
- [ ] Re-read Phase 03's split — `always` largely overlaps with inner knowledge; make sure this
      field does not become a second, competing mechanism for the same thing

## Checklist

- [ ] Add `:load` to the catalog entries, defaulting to `index`
- [ ] `build-system-prompt` honours `always`; `get_design_skills` honours `gated`/`off`
- [ ] Assign an initial policy per skill and **write down the reasoning** — the defaults are the
      real deliverable here, not the field
- [ ] Verify interaction with Phase 03: inner knowledge is `always` **by construction** and not
      a per-skill choice — if the two mechanisms overlap, collapse them rather than shipping both
- [ ] Measure the always-on cost again (Phase 03's table) — `always` is where bloat re-enters
- [ ] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [ ] Human approval; commit `:sparkles: Per-skill load policy for the agent metaprompt`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] Note whether `load` should cascade/override per scope once team/file scopes return — the
      open question from the original analysis, still unanswered

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — `:load` field + composition
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `get_design_skills` honours policy

## Notes

- Risk of over-engineering: if Phase 03 gives us a clean inner-knowledge layer and Phase 04
  shows the agent fetches reliably, then `always` and `off` are already expressible (inner
  knowledge / not in catalog) and only `gated` is genuinely new. In that case this phase should
  shrink to just `gated`, or be dropped. **Decide with the evidence, not the plan.**
