# Phase 27 — Drive the copy

**Status:** done (worktree caps-23-32; suite+live at merge)

The sharpest finding of the supply-side sweep: **an instance, once placed, is frozen.** Wave 1
built variant sets and Wave 5 lets the agent place instances — but `create_instance` is where
the story ends. The agent cannot switch a placed instance to another variant (the *entire
point* of the sets it can now build), cannot reset a drifted copy back to its main, and cannot
swap a copy for a different component. Asked to "make this button show its hover state", the
agent's only real move today is delete + re-instantiate + re-position — or worse, restyle the
copy by hand, manufacturing exactly the override drift the audit skill hunts.

All three are one-event wraps:

- `dwv/variants-switch` — switch a copy to the member matching new property values (the plugin's
  `switchVariant`, [shape.cljs:1704](../../../../../frontend/src/app/plugins/shape.cljs))
- `dwl/reset-component` ([libraries.cljs:843](../../../../../frontend/src/app/main/data/workspace/libraries.cljs)) — discard a copy's overrides
- `dwl/component-swap` ([libraries.cljs:1044](../../../../../frontend/src/app/main/data/workspace/libraries.cljs)) — replace with another component, keeping position

**Push-to-main (`dwl/update-component`, :886) is deliberately OUT.** The corpus already states
the governance: a main-component edit "renames the component for **every** instance across the
file — a shared-asset edit, never auto-fix". Propagating a copy's overrides into the main is
that, squared. If it ever ships it ships alone, approval-shaped.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Read `variants-switch` end to end: exact input shape (property/value pairs vs target component id), what happens on a value combination with no member — silent? error? nearest match?
- [ ] Read `reset-component` and `component-swap` for their silent filters (non-copies, variant members, nested copies) — mirror them in problem-checkers with the fix named
- [ ] Check what `component-swap` preserves (position, size, layout-child props?) and say so in the result — the agent needs to know whether to re-apply anything
- [ ] Surface: three tools (`switch_variant`, `reset_overrides`, `swap_component`) vs one `modify_instance`. Lean three — each has a different mental model and different guards
- [ ] Read side: a copy's current variant values are already visible via read_design's variant sections — verify a *copy* (not just the main) exposes enough to know what it currently shows

## Checklist

- [ ] Write tests: switch to an existing member; switch to a nonexistent combination rejected with the set's real values listed; reset a drifted copy; swap preserves placement; every non-copy input rejected with the fix named
- [ ] Add the three tools, implement, wire into dispatch
- [ ] `create_instance`'s description gains a pointer: "switch its variant with switch_variant"
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: place a Button instance, switch it to Hover, drift it, reset it, swap it for Chip
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — three specs, guards, dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs`

## Notes

This phase is what makes Waves 1 + 5 compose: sets exist so instances can *switch*. Until it
lands, every state demo (default/hover/pressed) is N parallel instances instead of one instance
driven through its states.
