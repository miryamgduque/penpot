# Phase 05 — Does the skill drive it?

**Status:** done — **no patch needed**

A verification phase, and the verdict is the plan's happiest outcome: with the tools in place,
the agent reaches them unaided. Nothing was changed.

## The test

The transcript's own prompt, verbatim, in the real panel, against a fresh board named "Panel":

> can you create component from current selection? once you do it, can you create a few variants?

Model: **claude-haiku-4-5** — the small one. 13 calls, 182.5k in (27% cached), 2.2k out.

## What happened

```
read_design                 → sees the selection
create_component            → Panel is a component
modify_shape                → renames it "Panel / Default"   ← learned the path convention
add_variant ×3              → ✗ REJECTED ×3:
    "add_variant: "Panel / Default" (…) is not part of a variant set —
     a set needs at least two main components; build one with create_variant"
                            → "I see! I need to create at least two main components
                               first, then use create_variant to combine them into a set."
create_shape ×3             → three more panels, variant-style names
create_component ×3         → four main components
create_variant              → ✓ real set
read_design                 → verifies
                            → offers to rename Property 1 to "State" or "Size"
```

Result, verified in the data: a real variant container named **`Panel`** with **four** members —
`Property 1 = Default | Hover | Compact | Extended`. **Zero frames with `=` in the name.**

## The three rejections are the finding

They are not a failure — they are the entire plan working, in one screenshot. The agent reached
for the wrong tool, the message named the corrective action, and it recovered on the next turn.
That is precisely the moment where the original transcript went the other way and invented
`Card=Size=Compact`.

It also **learned the path convention from the tool description alone** — nothing else in its
context knows it. It renamed the board to `Panel / Default` *before* combining, which is why the
set is called "Panel" and not "Component". The `namingHint` never had to fire.

## Was a patch needed? No — and here is why the plan expected one

The plan's three routes (extend `body-preamble`, override the skill at a more specific scope,
upstream it) all stay unused. But the concern behind them was real, and worth recording:

**The skill is a plausible source of the fake variants.** `penpot-component-factory` contains,
in its Brief Contract:

> **Constraints** — all values tokenized; all required states present; naming `Property=Value`.

That line means *"a property and value are written `Size=Medium`"*. An agent with no variant
tool reads it as *"name your layers `Size=Medium`"* — which is exactly what the original
transcript produced. The format was handed to it; only the tool was missing. With
`create_variant` present, the ambiguity stops mattering: the agent has somewhere real to put a
property, so it never reaches for the layer name.

**The skill does not know the path convention** (`path` appears once in 7.5k chars) and teaches
the manual route instead — `createVariantFromComponents` → `addProperty()` → `renameProperty` →
`setVariantProperty(pos, value)`. That is the plugin API's shape. Our tool descriptions carry
the Penpot-native convention the skill lacks, and the agent preferred them. This is the
`body-preamble`'s "translate onto your own tools" working as designed
(`agent_skills.cljs:177`).

**A warning in the skill that does *not* apply to us.** The same section says variant mutation

> has **corrupted files and hung all subsequent saves** in live sessions (the backend rejects
> every save with an unsurfaced HTTP 400)

That is the plugin-API path. Our tools go through the same internal events the Penpot UI uses.
Checked directly after dozens of agent variant mutations: `persistence :saved`, 0 pending ops,
file at **revn 59**, and every reload round-trips the sets back through the backend. No
corruption. Worth knowing before the demo, since the skill's own text would suggest otherwise.

## Checklist

- [x] Run the transcript's own prompt end-to-end
- [x] Observe: does the agent reach `create_variant`, or improvise again? — **it reaches it**
- [x] Record the transcript — above
- [x] **If it drives the tools correctly:** close as a no-op. Do not edit the skill — **done**
- [ ] Human approval received
- [x] Commit only if something changed — nothing did; this file is the deliverable

## Notes — the environment blocker, and a bug worth fixing

The panel would not open: it calls the `get-skills` RPC, which 404'd. `::get-skills` exists
(`profile_skills.clj:42`) and is registered (`rpc.clj:355`) — the **running backend predated
it**, and `::methods` is built once by `sv/scan-ns` at init, so a new RPC needs a real restart.
Fixed with `(in-ns 'user) (restart)` over the nREPL on **6064** (integrant restart inside the
JVM; no process kill). Confirmed by `get-skills` going 404 → **401**.

**The bug worth filing:** that 404 did not fail locally. It cascaded into Penpot's generic
not-found handler and replaced the whole workspace with **"You don't have access to this
file."** The file was fine and open; a panel's failed RPC hijacked the route. On stage that
reads as data loss. A panel fetch failing should degrade the panel, not the document. Not
caused by anything in this plan — pre-existing, and the kind of thing that only shows up when
the backend is a few commits behind, which is exactly the state a demo machine drifts into.
