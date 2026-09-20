---
name: triaging-strategic-feedback
description: Turn a piece of strategic or product feedback (from a coworker, stakeholder, or review) into something actionable — split the mechanism from the prioritization, date-check the framing, decide pillar-vs-primitive, and record it so it survives the session. Triggers: "triage this feedback", "a coworker gave feedback on the pitch/product", "is this idea worth pursuing", "someone said this is the differentiator".
---

<!-- TRANSIENT dev tooling — committed only so collaborators on this branch can
     see it. DELETE the whole `.claude/skills/` entry before the PR merges; it is
     not part of the Penpot product. -->

# Triaging strategic / product feedback

How to take a piece of strategic feedback (from a coworker, a stakeholder, a review)
and turn it into something you can act on — instead of either over-reacting to a
confident pitch or letting a good idea evaporate.

## Why this exists
Strategic feedback usually arrives as **one message that mixes two different things**:
a concrete, testable idea, and a broader claim about priority or direction. They need
different treatment. Conflating them is how you either build the wrong thing eagerly or
dismiss a cheap, high-leverage idea because its framing felt oversold.

## The steps

**1. Split the mechanism from the prioritization.**
Pull out the *concrete, buildable* idea and state it in one plain sentence ("click a
shape → inject its UUID/JSON into chat"). Separately, write down the *strategic claim*
("this is the differentiator"). From here on, treat them as two items.

**2. Judge the mechanism on its own engineering merits.**
Ignore how strongly it was pitched. Ask: what does it actually cost, what does it
actually buy, and why. Find the real insight and name it in your own words (e.g. "the
JSON is cheap *and* carries the UUIDs it points to — so it's grounding + navigation in
one"). If the idea survives this, it's worth pursuing regardless of the surrounding
claim.

**3. Date-check the framing.**
Ask when the argument was formed and what's changed since. A doc that argues "X beats
Y and Z" is only as good as the option set it assumed. If the project has pivoted, the
*comparison* may be stale even when the *mechanism* is still good. Read stale docs for
the mechanism, not the verdict.

**4. Map it onto what already exists — is it a pillar or a primitive?**
Check the idea against the current architecture before treating it as a new direction.
Often the "big new thing" is really a **primitive that makes existing things better**,
not a competing pillar. Framing it as a primitive sidesteps "new-thing vs. current-plan"
turf debates and makes it cheap to say yes to. Name the smallest shippable cut.

**5. Find the missing input before deciding.**
Strategic feedback usually points at something you don't have yet (a doc, prior art,
an annotation). Identify it and make *getting it* the first action — a real design pass
is blocked until you do. Don't design against the summary when the detail exists.

**6. Record it so it survives the session.**
- A **project memory** — the one-screen durable version, linked to related memories.
- A working note only if it's a live strand someone will continue soon — decoded idea,
  analysis, open items, next steps.
- Keep the durable version in memory, not in a repo file — a note in a git branch
  is easy to lose track of once the branch merges or is abandoned.

## Anti-patterns
- **Answering the pitch's confidence instead of its content** — warmth (or overclaim)
  is not evidence; re-derive the merit yourself.
- **Building the maximal version** because the pitch was maximal — ship the minimal cut
  first, note where scope grows.
- **Accepting a stale comparison** — always re-ask what the option set is *now*.
- **Losing it to the transcript** — if it isn't recorded in memory, it didn't happen.
