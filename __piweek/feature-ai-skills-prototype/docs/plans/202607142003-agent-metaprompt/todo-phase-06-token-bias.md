# Phase 06 — Token/component-first bias

**Status:** todo

## Goal

Test the "target the semantic layer, not raw values" claim: that an always-on rules layer
actually biases the agent toward design tokens and component instances rather than raw hex and
detached shapes. We have the instrument — `audit_file` counts exactly the violations a
generated design introduces.

This is the one hypothesis where we can score the agent's *output quality* objectively rather
than reading transcripts and forming an impression.

## Before Start

- [ ] Re-read `agent-tools/audit-violations` — `token-only-colors` and `layer-naming` are the
      auditable rules; violations are the score
- [ ] Re-read the enforcement path (`color-violation`, `rule-enforced?`) — note the difference
      between **bias** (prompt says prefer tokens) and **enforcement** (tool rejects raw hex).
      This phase measures bias; enforcement is the backstop
- [ ] Confirm Phase 03 landed `tokens-schema` as inner knowledge — that layer is the treatment
- [ ] Prepare a fixture file with a real token set, so "use a token" is actually possible

## Checklist

- [ ] Define a small, fixed set of generative prompts ("build a card", "style this board",
      "add a CTA") — same prompts across all conditions
- [ ] **Experiment F — bias off vs on.** Run each prompt with the token-first inner knowledge
      absent vs present; enforcement **off** in both (so the tool does not mask the bias).
      Score with `audit_file`: violations introduced per generated design.
- [ ] **Experiment G — bias vs enforcement.** Bias on + enforcement on. Score: does the agent
      still get rejected? A good bias layer should make rejections rare — enforcement is the
      backstop, not the mechanism.
- [ ] Run each prompt ≥3× per condition — one sample of a stochastic model is an anecdote, not
      a measurement
- [ ] **Inherited from Phase 05: measure per-skill body value while the harness is up.** Phase 04
      showed body value is wildly uneven (the accessibility body cost ~1,250 tokens and changed
      nothing measurable; the foundations body alone produced the phased workflow) — but that was
      n=1. Same shape as Experiment F: ≥3 runs × body-on/body-off, per skill. Any body that earns
      nothing should be dropped from the generated corpus — cheaper and more honest than the
      `load` field Phase 05 declined to build.
- [ ] If bias is weak, strengthen the rules layer and re-measure (measure-then-fix)
- [ ] Human approval; commit `:memo: Measure token-first bias of the agent rules layer`
      (plus a `:sparkles:`/`:recycle:` commit if the rules layer changes)

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] Record the headline number — "N→M violations per generated design" is the most concrete
      evidence the whole skills thesis works, and belongs in the pitch

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — the rules layer under test
- this file — the Findings table

## Findings

| Experiment | Condition | Prompt | Run | Violations | Rejections | Note |
|---|---|---|---|---|---|---|
| F | bias off, enforce off | build a card | 1 | | n/a | |
| F | bias on, enforce off | build a card | 1 | | n/a | |
| G | bias on, enforce on | build a card | 1 | | | |

**Headline:** _(to fill)_ violations per generated design, bias off → bias on.

## Notes

- Confounder to control: `read_design` already returns `:colorTokens`, so the agent can see the
  tokens exist even with no rules layer. That means the "off" condition is not a true zero — it
  is "tokens visible but unadvocated". Worth stating plainly in the writeup rather than
  overclaiming the rules layer's contribution.
- The distinction this phase makes precise: enforcement makes violations *impossible*, bias
  makes them *unlikely*. Only bias scales to rules we cannot mechanically check (naming
  quality, component reuse, layout sanity) — which is the real argument for the skills layer.
