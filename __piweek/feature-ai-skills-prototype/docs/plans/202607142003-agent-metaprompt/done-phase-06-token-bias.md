# Phase 06 — Token/component-first bias

**Status:** done — hypothesis refuted as measured; the instrument, not the layer, is the finding

## Goal

Test the "target the semantic layer, not raw values" claim: that an always-on rules layer
actually biases the agent toward design tokens and component instances rather than raw hex and
detached shapes. We have the instrument — `audit_file` counts exactly the violations a
generated design introduces.

This is the one hypothesis where we can score the agent's *output quality* objectively rather
than reading transcripts and forming an impression.

## Before Start

- [x] Re-read `agent-tools/audit-violations` — `token-only-colors` and `layer-naming` are the
      auditable rules; violations are the score
- [x] Re-read the enforcement path (`color-violation`, `rule-enforced?`) — note the difference
      between **bias** (prompt says prefer tokens) and **enforcement** (tool rejects raw hex).
      This phase measures bias; enforcement is the backstop
- [x] Confirm Phase 03 landed `tokens-schema` as inner knowledge — that layer is the treatment
- [x] Prepare a fixture file with a real token set, so "use a token" is actually possible

## Checklist

- [~] Define a small, fixed set of generative prompts — **one** prompt used, not three; see Caveats: ("build a card", "style this board",
      "add a CTA") — same prompts across all conditions
- [x] **Experiment F — bias off vs on.** Run each prompt with the token-first inner knowledge
      absent vs present; enforcement **off** in both (so the tool does not mask the bias).
      Score with `audit_file`: violations introduced per generated design.
- [~] **Experiment G — NOT RUN; F made it moot (no raw hex was ever attempted, so nothing could be rejected). See Findings: — bias vs enforcement.** Bias on + enforcement on. Score: does the agent
      still get rejected? A good bias layer should make rejections rare — enforcement is the
      backstop, not the mechanism.
- [~] Run each prompt ≥3× — **n=2 reached**, not 3; stated in Caveats rather than glossed: per condition — one sample of a stochastic model is an anecdote, not
      a measurement
- [ ] **NOT DONE — carried forward. Inherited from Phase 05: measure per-skill body value while the harness is up.** Phase 04
      showed body value is wildly uneven (the accessibility body cost ~1,250 tokens and changed
      nothing measurable; the foundations body alone produced the phased workflow) — but that was
      n=1. Same shape as Experiment F: ≥3 runs × body-on/body-off, per skill. Any body that earns
      nothing should be dropped from the generated corpus — cheaper and more honest than the
      `load` field Phase 05 declined to build.
- [~] If bias is weak, strengthen the rules layer and re-measure — declined: bias is not weak, the *measurement* is. Strengthening prose to move a number the instrument cannot read would be cargo-culting: (measure-then-fix)
- [x] Human approval; commit `:memo: Measure token-first bias of the agent rules layer`
      (plus a `:sparkles:`/`:recycle:` commit if the rules layer changes)

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] Record the headline number — "N→M violations per generated design" is the most concrete
      evidence the whole skills thesis works, and belongs in the pitch

## Files

- `frontend/src/app/main/data/workspace/agent_skills.cljs` — the rules layer under test
- this file — the Findings table

## Findings

**Method.** Fixture: 6 semantic tokens created for the agent to prefer (`color.bg.surface`,
`color.bg.app`, `color.text.default`, `color.text.muted`, `color.action.primary.bg`,
`color.border.subtle`). Prompt: *"Build a small card: a container board with a heading and one
line of body text."* Model `claude-opus-4-8`. Enforcement **off** during generation in both
conditions. Because `audit-violations` gates on `rule-enforced?`, the same flag controls
scoring — so rules are switched on **only to read the count**, then off again. The page carries
22 pre-existing violations, so the score is the **delta** (violations introduced), not the total.
Treatment is surgical: only the `## Naming conventions` block (1,320 chars) is stripped from the
prompt, leaving governance and native-tool notes intact, so the variable is isolated.

| Run | Condition | Violations introduced | New layer names |
|---|---|---|---|
| 1 | bias **on** | **0** | `card-container`, `h3`, `p` |
| 1 | bias **off** | **0** | "Card board", "Heading", "Body" |
| 2 | bias **on** | 0 | `card-container`, `h3`, `p` |
| 2 | bias **off** | 0 | `info-card`, "Heading", "Body" |

**Headline: 0 → 0. The hypothesis is REFUTED as measured — and the measurement is the problem.**

### Why the count says nothing

Both conditions used tokens throughout and introduced **zero** violations. Bias-off run 2 even
volunteered *"All colors are semantic tokens — no raw hex."* The confounder the plan predicted
turned out to dominate completely: **`read_design` already returns `:colorTokens`**, so the "off"
condition was never "no tokens advocated" — it was "tokens sitting in plain sight". A capable
model uses a token it can see. The rules layer is not what makes it reach for one.

### What the layer actually does — and why `audit_file` is blind to it

The conditions differ **consistently and legibly**, just not in a way the instrument can score:

- **With** the layer: text layers are named `h3` and `p` — the semantic HTML elements the
  convention prescribes. 2/2 runs.
- **Without** it: `Heading` and `Body` — perfectly reasonable names that no rule forbids. 2/2 runs.

`layer-naming` only flags **default** names (`Rectangle 12`). "Heading" is not a default name, so
it scores clean. `token-only-colors` only flags **raw hex**, which neither condition produced. The
audit measures *absence of the two worst mistakes*; the layer's real effect is *which* semantic
vocabulary gets used — a quality the audit was never designed to see.

So the phase's premise — "we can score output quality objectively with `audit_file`" — is only
half true. It scores the floor, not the ceiling. Both designs clear the floor; only one follows
the house style.

### Consequence for the enforcement-vs-bias distinction

Experiment G (bias on + enforcement on, counting rejections) was **not run**, because F makes its
result a foregone conclusion: the agent never attempted a raw hex in any of the four runs, so
there is nothing for the tool boundary to reject. That is itself the answer to G's question —
**enforcement is a backstop that, with tokens visible, is never reached.** Worth re-running if a
future fixture has no tokens (where the agent must invent a colour) — that is the case where
enforcement would actually fire.

### Caveats — stated plainly

- **n=2 per condition, not the ≥3 the checklist asked for**, and **one prompt**, not the three
  planned. The naming difference was perfectly consistent across the runs done (4/4), but the
  violation count is a null result and a null result at n=2 is weak evidence of *no effect* —
  it is strong evidence only that no *large* effect exists on this prompt.
- The prompt is small and unambiguous. A brief with genuine colour choices to make (a new
  accent, a state colour with no token) is where bias would plausibly matter, and is untested.

## Notes

- The confounder listed here in advance (`read_design` exposes `:colorTokens`, so "off" is
  "tokens visible but unadvocated", not a true zero) turned out not to be a caveat on the result
  — it **is** the result. Flagging it before running is what made the null interpretable instead
  of confusing.
- **The closing line of this phase's original note turned out to be the finding, inverted.** It
  said: *"only bias scales to rules we cannot mechanically check (naming quality, component
  reuse, layout sanity)"*. Exactly so — and that is precisely why this phase could not measure
  it. We chose `audit_file` as the instrument *because* it is mechanical, then used it to look
  for an effect that lives in the part of quality no mechanical check reaches. The layer's win
  (`h3`/`p` over "Heading"/"Body") is real, visible, consistent — and unscoreable by the tool we
  picked. If we want to score house-style adherence, that needs a different instrument (a judge
  model, or a convention-specific linter), not a violations count.
- This also retires a line from the US #26 story with better information. The story implies the
  rules layer is what drives token-first output; on this evidence, **visibility drives it and the
  layer refines it**. Both matter, but they are not the same claim, and only the second is ours.
