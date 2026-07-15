# Phase 13 — Place a component

**Status:** todo

`instance()` is 17 mentions across the corpus and `detach()` is 13 — together the highest-demand
component operations after creation. The agent can `create_component` but never place one, so
every design system it builds is **write-only**. `penpot-build-from-code` says outright to
prefer `component.instance()` for any part with a library match — a step it cannot take, which
means it redraws from primitives every time and the library it just authored goes unused.

## Before Start

- [ ] Re-read `instantiate-component` ([libraries.cljs:612](../../../../../frontend/src/app/main/data/workspace/libraries.cljs)) — `(file-id component-id position {:keys [start-move? initial-point id-ref origin]})`. It takes an **`id-ref`**, so the new instance's id is returnable (unlike `add-new-variant` in Phase 04)
- [ ] Note `position` must be a `gpt/point`, and the fn asserts on all three args — validate before calling or the assert throws rather than returning a usable message
- [ ] Re-read `detach-component` (:662) / `detach-components` (:681)
- [ ] Check `origin` — what it's used for, and what an agent-originated instantiation should pass
- [ ] Confirm how to reach components from **connected** libraries, not just the local one: `file-id` is a parameter, so cross-library instantiation looks possible. Check what `read_design` would need to surface for the agent to name a component in another library

## Checklist

- [ ] Write tests for validation
- [ ] Add `create_instance` to `tool-specs`
- [ ] Implement, wire into dispatch
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: create a component, place two instances, edit the main, confirm both update
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Notes

**`read_design` doesn't list components.** It reports shapes, tokens, skills and violations —
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
