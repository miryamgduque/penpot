# Phase 05 — Per-skill load policy

**Status:** done — **dropped, with reasons.** No code shipped. This phase was conditional from
the start ("if the two mechanisms overlap, collapse them rather than shipping both… **Decide
with the evidence, not the plan**"), and the evidence says every job the `load` field was
invented to do is already done by something else.

## What the field was for

The [2026-07-13 analysis](../../skill-loading-strategy.md) proposed one authored field —
`always | index | gated | off` — because loading was then decided by a skill's *scope* rather
than by *what the skill is for*. It named four concrete problems. All four are now fixed, none
of them by this field.

| Proposed value | Original motivation | Verdict now |
|---|---|---|
| `always` | Small universal governance/conventions inlined every turn | **Already shipped as a layer.** Phase 03's `inner-knowledge` *is* the always-on tier, and it is deliberately not toggleable — a user must not be able to switch off "ask before destructive changes". Expressing that as a per-skill field would make it look negotiable. |
| `index` | Large task-specific playbooks; name+blurb now, body on demand | **The default, and measured working.** Phase 04: the agent fetches unprompted and first-try correct. A field to select the behaviour everything already has is not a feature. |
| `gated` | "fetch X before a specific action" — the motivating case was `penpot-plugin-api-gotchas` before the first mutating `execute_code` | **The motivating case no longer exists.** Phase 03 dropped that doc (it describes an API we never call) and replaced it with always-on native-tool notes. Nothing else asked to be gated. |
| `off` | External-only material, e.g. `penpot-mcp-tool-reference` | **Expressible twice over already.** Phase 03 removed it from the embedded corpus at generation time, and US #8 gives users a real per-skill on/off with account/file resolution. A third mechanism would just disagree with those two. |

The analysis's four problems, re-checked:

1. **Noise** (`mcp-tool-reference` sitting in the prompt) — gone; Phase 03 does not carry it.
2. **Redundancy** (operating-modes distilled inline *and* fetchable) — gone; `inner-knowledge`
   is the single source, and the body generator strips the duplicated sections out of every
   playbook so the same text is never paid for twice.
3. **Missed defaults** (`naming-conventions` only indexed) — gone; naming is always-on.
4. **Bloat risk** (local skills inlined in full) — moot natively: there are no org/file-authored
   skills, only the built-in catalog. If authored skills ever land, revisit *then*, with a real
   case instead of a hypothetical one.

## Why dropping is the right call, not laziness

Shipping `load` now would add a second, competing way to say things the codebase already says —
the exact outcome this phase was told to avoid. Worse, two of its values would actively lie:
`always` would imply inner knowledge is per-skill and optional, and `off` would compete with the
user-facing toggle US #8 just shipped. A field whose four values are respectively redundant,
default, moot, and duplicated is not an abstraction; it is a maintenance liability with a
config UI attached.

## The one real question Phase 04 raised — and why it is not this field

Phase 04 found that **body value is uneven**: fetching the accessibility playbook cost ~1,250
tokens and changed nothing measurable (Opus knows WCAG; Phase 03 supplies mode and naming),
while the foundations playbook alone produced the phased workflow and checkpoints. So there *is*
a real question — "does this skill's body say anything the model does not already know?"

But that is a question about **whether a body should exist**, answered by curating the corpus at
generation time, not by a runtime load field. And it must not be answered from a single
observation of a stochastic model: n=1 on one prompt is an anecdote. Recorded as a follow-up
below rather than acted on now.

## Follow-ups this phase hands on

- [ ] **Measure per-skill body value properly** (≥3 runs × 2 conditions × a prompt that actually
      exercises the skill), then drop the bodies that earn nothing from the generated corpus.
      Cheaper and more honest than a policy field: if a body adds nothing, do not ship it.
      Belongs with Phase 06's methodology, which already has the multi-run harness.
- [ ] **Revisit `load` only if authored skills land.** If teams can write their own skills, the
      "bloat risk" problem returns for real and an authored field may finally earn its place.
      The original analysis is preserved for that day.

## Files

None. The value of this phase is the decision and its record.
