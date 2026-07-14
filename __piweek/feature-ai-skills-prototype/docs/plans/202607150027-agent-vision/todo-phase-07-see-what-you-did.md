# Phase 07 — See what you did

**Status:** todo
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

- [ ] Read Phase 06's cost table. If a render is expensive, "always on" is already refuted and
      this phase is about *when*, not *whether*
- [ ] Design the prompts **before** running anything, and write them down. A post-hoc prompt is a
      post-hoc conclusion

## Checklist

- [ ] Re-feed after mutation: after an edit batch, render the affected board and hand it back so
      the next round sees the result
- [ ] Bound it hard. Affected board, not the whole page. Do **not** re-feed after every trivial
      tool call. Renders cost tokens, latency, **and** main-thread time
- [ ] **Experiment — blind vs. seeing.** Same generative prompts, render loop on vs. off.
      **Do NOT score this with `audit_file`** — that method was inherited from the superseded
      metaprompt Phase 07, and metaprompt **Phase 06 measured it and found it worthless for
      quality**: a violations count scores the *floor* (no default names, no raw hex) and is
      structurally blind to the ceiling. In that experiment a design following the house style
      and one ignoring it **both scored 0**, while differing legibly to any human (`h3`/`p` vs
      "Heading"/"Body"). A 0–0 result here would mean the instrument is blind, not that seeing
      does not help — and it would look like a refutation. Score instead with a **judge model**
      given both renders, or an honest human read, and say which you used
- [ ] Beware the mirror-image trap: seeing might also make output *worse* (over-correcting to
      pixel-nudge what was already fine). Look for that, not just for improvement
- [ ] **Include the inherited-design case.** Our tools are *structural* — `create_shape` with
      explicit coordinates, layout via flex/grid — so the agent may already know where things are
      because it put them there. The honest test is a design it did **not** create, or a WASM
      layout that settled differently than requested. Without that case the experiment trivially
      favours "blind is fine" and proves nothing
- [ ] Record cost per render and per turn
- [ ] Write the verdict, including the case for refusing the hypothesis if that is what the data
      says
- [ ] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [ ] Human approval; commit `:sparkles: Re-feed the render so the agent sees what it did`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] Write the plan's **Completion Summary** in the README: the verdict on both halves, with
      numbers
- [ ] Update `CLAUDE.md` / the prototype's docs if the agent's capabilities changed
- [ ] Move the plan folder to `docs/plans/completed/`

## Findings

| Condition | Prompt | Design authored by | Violations | Layout correct? | Tokens | $ |
|---|---|---|---|---|---|---|
| blind | | agent | | | | |
| seeing | | agent | | | | |
| blind | | someone else | | | | |
| seeing | | someone else | | | | |

**Verdict:**
**Recommended default (always / on-demand / off):**

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
