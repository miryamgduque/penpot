# Phase 13 — Place a component

**Status:** done

`instance()` is 17 mentions across the corpus and `detach()` is 13 — together the highest-demand
component operations after creation. The agent can `create_component` but never place one, so
every design system it builds is **write-only**. `penpot-build-from-code` says outright to
prefer `component.instance()` for any part with a library match — a step it cannot take, which
means it redraws from primitives every time and the library it just authored goes unused.

## Before Start

- [x] Re-read `instantiate-component` — takes an `id-ref`, reset **synchronously** in the watch, so the id comes back without a selection-diff (unlike Phase 04)
- [x] Note `position` must be a `gpt/point` and all three args are asserted — validated before calling
- [x] Re-read `detach-component` / `detach-components` — **deliberately not shipped** (see Notes)
- [x] Check `origin` — an analytics tag on the `use-library-component` event; passes `"agent:create_instance"`
- [x] Confirm how to reach **connected** libraries — `dsh/lookup-libraries` returns local + connected keyed by file-id; `read_design` now names the library on each foreign component

## Checklist

- [x] Write tests for validation
- [x] Add `create_instance` to `tool-specs`
- [x] Implement, wire into dispatch
- [x] **Add the `components` section to `read_design`** — the tool is unusable without it (see Notes)
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: placed two instances, renamed the **main**, and the instance followed
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Notes — what execution found

**The plan's own warning was right: `read_design` didn't list components, so the tool had
nothing to name.** It shipped as part of this phase rather than deferred — a `create_instance`
whose only usable argument comes from a section that doesn't exist is not a shipped capability.

**Variant members are excluded from `components`, and it matters more than expected.** They are
already under `:variants` with their component ids, so listing them twice doubles the payload.
Measured live: the file has **9 variant sets** (~25 member components) and 3 non-variant ones —
`components` lists 3, not 28.

**Connected libraries work and are named.** `dsh/lookup-libraries` returns the local file *and*
every connected library keyed by file-id, so `instantiate-component`'s `file-id` parameter is
genuinely reachable. A foreign component carries `fileId` + `library`; a local one carries
neither, since the local file is the default target and saying so on every entry is payload for
nothing.

**`0` is a legitimate coordinate** — `x`/`y` are checked with `nil?`, not truthiness. Third time
this exact trap has appeared (Phase 07's `absolute false`, Phase 08's `"0"` spacing token); it
is now a reflex, and each one has a test.

**The note's claim was tested, not trusted** — the `duplicate_shape` lesson from Phase 11.
`create_instance` says *"editing the main updates it"*, so: place an instance → rename the
**main** → the instance's name follows (`Card` → `Card RENAMED BY MAIN`). It does. The test
rename was reverted afterwards.

**Detach deliberately not shipped**, as planned. It is high-demand (13 mentions) but the skills
fence it hard — *"never detach a variant instance (gotchas #12)"* — and a detach the agent
reaches for casually destroys the link that makes a component a component. Placement first; add
detach only when a real task needs it, with the variant guard in the validation.

**Payload now 66% of the 20k budget** (12.6k chars), up from 53% at Phase 10 — this phase added
a section. `read_design` is now shaped by six phases (02, 10, 13, and the pending 15, 17, 18).
Phase 15's note to stop and redesign it as a whole is now overdue rather than hypothetical.

## Notes — from planning

**~~`read_design` doesn't list components~~ — fixed in this phase.** It reports shapes, tokens, skills and violations —
so the agent has no way to discover what components exist to instantiate. This phase probably
needs a `components` section in `read_design` (name + id, from `ctkl/components-seq` on the file
data) or the tool is unusable except on a component the agent created in the same turn. Check
this at Before Start; it may deserve its own small phase alongside the `read_design` work in
Phases 10 and 15.

**Detach is deliberately not in this phase.** It's high-demand (13) but the skills fence it
heavily — *"never detach a variant instance (gotchas #12)"* — and a detach the agent reaches for
casually destroys the link that makes a component a component. Ship placement first; add detach
only if a real task needs it, with the variant guard in the validation.

**Variant instances are a special case.** Instantiating a variant component should probably
place the primary variant (`get-primary-variant`, `files/variant.cljc:65`) and then let
`variants-switch` (`variants.cljs:778`) change it by property. That interaction is unexplored —
if Wave 1 landed, test it here and record what happens.
