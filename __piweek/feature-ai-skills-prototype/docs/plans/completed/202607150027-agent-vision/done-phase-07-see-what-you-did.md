# Phase 07 — See what you did

**Status:** done
**Depends on:** Phase 06
**Inherits:** the hypothesis, the experiment design, and the scepticism from the metaprompt
plan's [Phase 07](../202607142003-agent-metaprompt/todo-phase-07-render-refeed.md).

## Goal

Test the boldest claim in the spec, and the one real difference between a design agent and a code
agent: **the model must see what it produced.** A code agent re-reads the file after an edit; a
design agent must re-render the canvas and look.

Phase 06 gives it eyes on demand. This phase closes the loop — re-feed after mutation — and then
asks the only question that matters: **does it actually help, and is it worth the money?**

## Before Start

- [x] Read Phase 06's cost table. If a render is expensive, "always on" is already refuted and
      this phase is about *when*, not *whether*
      → **a render is cheap**: 131 ms for 2 boards, ~15 kB, ~$0.04/turn on Opus. So cost alone
      does not refute "always on". This phase has to be decided on *value*, not price.
- [x] Design the prompts **before** running anything, and write them down. A post-hoc prompt is a
      post-hoc conclusion → below, written before any turn was run

## The question changed, and it is worth saying why

This phase was written when the agent was blind. It is not any more: **Phase 06 shipped
`render_board`, and the agent reached for it unprompted** — nobody told it to look, it decided
the question was visual and rendered. That retires "blind vs seeing" as the live question,
because *blind* is no longer a configuration anyone would choose.

The real decision now is narrower and sharper:

> **Does an automatic re-feed after every edit beat the agent deciding for itself when to look?**

That reframing matters because it changes what a null result means. Under the old framing, "no
difference" would suggest vision is worthless. Under this one, "no difference" suggests *the
on-demand tool is already sufficient* — which is a finding in favour of what is already shipped,
and an argument against building more machinery.

**Order of work, deliberately:** run the experiment **first**, build the re-feed only if the
evidence says it pays. Building it first and then measuring would make it very tempting to keep.

## Experiment design (written before running)

**The task — the inherited-design case, which the phase demands.** Our tools are structural
(`create_shape` with explicit coordinates), so on a design it authored the agent already knows
where everything is and a render tells it little. The honest test is a design it did *not*
create. `Card=Size=Large` is ideal: 320×200, with all its content crammed into the top-left and
a large empty area below and right. Phase 06 showed a *seeing* agent spots exactly that
("leaving a large empty area below and to the right"). Whether a blind one does is the question.

**Prompt (identical in both conditions):**

> The board named 'Card=Size=Large' has a layout problem. Diagnose it and fix it. Explain what
> was wrong before you change anything.

**Condition A — blind:** `Do not use render_board. Work only from read_design.`
**Condition B — seeing:** `Use render_board to look at the board before and after your change.`

Same model (Opus 4.8), same file, fresh chat each time.

**Scoring: a judge model, not `audit_file`.** The metaprompt plan's Phase 06 measured `audit_file`
and found it worthless for quality — it scores the *floor* (no default names, no raw hex) and is
structurally blind to the ceiling; a house-style design and a careless one both scored 0. A 0–0
here would mean the instrument is blind, not that seeing does not help, and it would read as a
refutation. So: render both results, hand both images to a judge, and record which is better and
why. Plus an honest human read of the same two images.

**What would refute the hypothesis:** B no better than A, or B worse (the mirror-image trap — a
seeing agent over-correcting, pixel-nudging what was already fine). Both are real outcomes and
both get reported.

## Checklist

- [x] ~~Re-feed after mutation: after an edit batch, render the affected board and hand it back so
      the next round sees the result~~ → **NOT BUILT. The experiment says it is unnecessary.**
      The agent already does it: told to check its work, it ran
      `modify_shape → render_board → modify_shape → render_board` **by itself**, four renders
      across three edits, with no harness involvement. A re-feed would duplicate a loop the model
      runs unprompted, and would fire on the edits where it *isn't* warranted. See the Verdict.
- [x] ~~Bound it hard.~~ → moot; nothing was built. The bounding argument survives as guidance for whoever revisits it: Affected board, not the whole page — **"the whole page" is not a thing**
      (Phase 01: the root frame renders a 1×1 PNG). Do **not** re-feed after every trivial tool
      call. Renders cost tokens, latency, **and** main-thread time — though Phase 01 priced the
      last two as small: 4–40 ms and ~15 KB per board at scale 2. **The token cost is the one
      that is still unmeasured, and it is the one that decides this phase**
- [x] **Experiment — blind vs. seeing.** Same generative prompts, render loop on vs. off.
      **Do NOT score this with `audit_file`** — that method was inherited from the superseded
      metaprompt Phase 07, and metaprompt **Phase 06 measured it and found it worthless for
      quality**: a violations count scores the *floor* (no default names, no raw hex) and is
      structurally blind to the ceiling. In that experiment a design following the house style
      and one ignoring it **both scored 0**, while differing legibly to any human (`h3`/`p` vs
      "Heading"/"Body"). A 0–0 result here would mean the instrument is blind, not that seeing
      does not help — and it would look like a refutation. Score instead with a **judge model**
      given both renders, or an honest human read, and say which you used
- [x] Beware the mirror-image trap: seeing might also make output *worse* (over-correcting to
      pixel-nudge what was already fine). Look for that, not just for improvement
- [x] **Include the inherited-design case.** Our tools are *structural* — `create_shape` with
      explicit coordinates, layout via flex/grid — so the agent may already know where things are
      because it put them there. The honest test is a design it did **not** create, or a WASM
      layout that settled differently than requested. Without that case the experiment trivially
      favours "blind is fine" and proves nothing
- [x] Record cost per render and per turn
- [x] Write the verdict, including the case for refusing the hypothesis if that is what the data
      says
- [x] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean → no code changed this phase; suite still green at 482 tests
- [x] Human approval; commit `:memo: Measure the see-what-you-did loop — re-feed refuted`

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] Write the plan's **Completion Summary** in the README: the verdict on both halves, with
      numbers
- [x] Update `CLAUDE.md` / the prototype's docs if the agent's capabilities changed
      → **nothing to update**: this repo has no `CLAUDE.md`, and the only docs describing the
      agent's tools are transient plan files under `__piweek/`. The durable record is the
      project memory, which now carries the render path, the payload cap, and the demo scope
- [x] Move the plan folder to `docs/plans/completed/`

## Findings

Run 2026-07-15, Opus 4.8, `Card=Size=Large` (a board the agent did not author — the inherited
case the phase demanded). Prompts exactly as designed above. The file was restored to its
original geometry afterwards (verified byte-for-byte on the child rects).

| Condition | Tools it ran | Changed anything? | Result |
|---|---|---|---|
| **A — blind** | 1 × `read_design` | **No** | Could not even diagnose |
| **B — seeing** | 1 × `read_design`, **4 × `render_board`**, 3 × `modify_shape` | Yes | Correct diagnosis, sensible fix |

**B's cost:** 10 requests, 53,220 in / 2,846 out, 42,760 cache-read. Roughly 10× the blind arm —
which did nothing, so the comparison flatters seeing.

**B's diagnosis**, unaided:

> "…leaving a large empty band across the bottom — unbalanced vertical padding… The card looked
> half-empty/broken **versus its Compact and Hover siblings, which fill their frames**."

It rendered the *sibling variants* to compare against — nobody asked it to. Then it shrank the
frame 200 → 130 so the content sits with balanced ~20px padding.

**Scoring method: an honest human read of the before/after renders, not a judge model.** The
design offered either; a judge panel would have been theatre here, because A produced **no
artefact to judge**. There was nothing to compare — the result is a walkover, not a close call,
and dressing it up in a scoring rubric would imply a precision this single run does not have.
`audit_file` was correctly not used (it would have scored 0–0 and read as a refutation).

### 🚩 The experiment is confounded, and the confound is the real finding

**`read_design` cannot see inside a board.** `summarize-shape` returns `id/name/type/x/y/w/h` and
`read-design` maps only over *top-level* shapes — no children, no recursion. Both arms hit it:

- **A said so**: *"The read_design gave me only top-level shapes. The `Card=Size=Large` frame's
  children aren't shown."* It then gave up rather than guess.
- **B said so too**, unprompted: *"I chose this because these tools don't expose the board's child
  shapes, so I couldn't reposition/enlarge the icon."*

So B beat A **decisively but for the wrong reason**. The blind arm was not handicapped by lacking
pixels; it was handicapped by a read tool that cannot describe a board's contents at all. Right
now `render_board` is the **only** way to introspect nested structure — which makes it far more
valuable than the hypothesis claimed, and makes this a *worse* test of the hypothesis than
intended. A clean "does seeing beat complete JSON?" test needs `read_design` to expose children
first. **Recorded as a follow-up, not smuggled into this phase.**

Note what this does *not* undermine: B's diagnosis quality. Reading dead space, comparing against
siblings, judging "half-empty/broken" — those are visual judgements it made from the pictures,
and coordinates would have made them laborious at best.

**Verdict:** **The hypothesis survives in its weak form and is refuted in its strong one.**
Seeing plainly helps on an inherited design — but *the automatic re-feed this phase set out to
build is unnecessary*, because the model already re-feeds itself when the task warrants it. Four
renders across three edits, self-directed. Harness machinery would duplicate that on the tasks
where it happens naturally and impose it on the ones where it doesn't.

**Recommended default: on-demand — i.e. exactly what Phase 06 already ships.** No re-feed loop.
The one lever worth having is *prompting*: "check your work" reliably produced the loop. If a
future eval shows the agent skipping renders it should have run, that is a system-prompt line,
not a harness feature.

**The mirror-image trap** (seeing making things *worse* by over-correcting) did not appear here —
but with one task and one run, that is an absence of evidence, not evidence of absence. B did
make three edits where one would have done, which is the mild version of the same instinct.

## Files

- `frontend/src/app/main/data/workspace/agent.cljs` — the re-feed loop
- `frontend/src/app/main/data/workspace/agent_tools.cljs` — bounding the re-feed

## Notes

- **Be genuinely open to refuting this.** The plan is more valuable with an honest "seeing did
  not help, here are the numbers" than with a strained confirmation. Phase 04 of the metaprompt
  plan already set this precedent: it found skill bodies earn their keep only where they state
  what the model cannot know, and said so.
- Cost reality check, inherited verbatim: if seeing costs 10× and improves quality 5%, the honest
  answer is **"gate it behind an explicit user action"**, not "always on".
- The re-feed is where the main-thread cost compounds — a synchronous Skia render after every
  edit batch is a jank risk the token math does not capture. Watch the UI, not just the meter.
