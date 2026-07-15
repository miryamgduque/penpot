# Phase 11 — Undo the agent's own mess

**Status:** todo

The agent can create a shape and has no way to remove it. In the transcript it made five
frames it could not delete; the only cleanup path was the user doing it by hand.

The skills barely ask for this — `remove()` is 2 mentions, the lowest in the corpus. Rank it
higher than that number anyway. Low demand here means the skills assume it, the way they assume
undo exists. `penpot-rename-layers` ships as `mode: autofix`, which presumes mutation someone
can walk back, and an agent that cannot correct its own mistakes can only add to them.

## Before Start

- [ ] Re-read `delete-shapes` ([shapes.cljs:304](../../../../../frontend/src/app/main/data/workspace/shapes.cljs)) — `(page-id ids options)`; **`ids` must be a set** (`sm/check-set-of-uuid` at :308)
- [ ] Find the duplicate event — there is no `duplicate-shapes` in `shapes.cljs`; check `app.main.data.workspace.selection` for `duplicate-selected`, and see whether a non-selection-based arity exists. If duplication only works through the selection, decide whether to set selection first or drop duplicate from this phase
- [ ] Check what `delete-shapes` refuses: component copies? variant members? A member deletion that guts a variant set needs its own message
- [ ] Confirm both are covered by an undo transaction — the user's ⌘Z must reverse an agent delete

## Checklist

- [ ] Write tests for validation
- [ ] Add `delete_shape` (and `duplicate_shape`, if the event supports it) to `tool-specs`
- [ ] Implement, wire into dispatch
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: agent creates 3 shapes, deletes 1, ⌘Z restores it
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## Open question — should delete be guarded?

Every other tool in this plan is guarded by *validation*. Delete is the first that might want
a *policy*. It is the only irreversible-feeling action the agent can take (it isn't actually
irreversible — undo covers it — but it will feel that way to a user watching their work vanish).

Options, in rough order of preference:

1. **Ship it plain.** It is undoable, the panel is a prototype, and the enforcement layer
   already exists for real rules. Simplest, and probably right.
2. **Scope it to the session.** Only delete shapes this agent created (track ids per turn).
   Safe, but useless for the actual ask — "clean up these layers" is about *existing* shapes.
3. **Route it through the enforcement layer.** A `no-delete` rule alongside
   `token-only-colors`, off by default (`rule-enforced?`, agent_tools.cljs:250). Fits the
   prototype's existing shape and costs little.

Recommend 1, with 3 as the escape hatch if review surfaces nerves. **Do not build 2** — it
sounds prudent and quietly makes the tool unable to do the job it exists for.

Whatever is chosen, the result message should say what went and how to get it back:
`deleted 3 shapes — undo with ⌘Z`.

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — specs, impls, dispatch entries
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — validation tests

## Notes

Set-vs-vector matters: `delete-shapes` asserts a set, `combine-as-variants` wants a vector for
ordering. Two conventions live side by side in this file now. Convert at the tool boundary and
say which you're passing.
