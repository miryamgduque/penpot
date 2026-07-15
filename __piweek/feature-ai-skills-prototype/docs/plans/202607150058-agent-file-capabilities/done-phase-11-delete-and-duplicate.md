# Phase 11 — Undo the agent's own mess

**Status:** done

The agent can create a shape and has no way to remove it. In the transcript it made five
frames it could not delete; the only cleanup path was the user doing it by hand.

The skills barely ask for this — `remove()` is 2 mentions, the lowest in the corpus. Rank it
higher than that number anyway. Low demand here means the skills assume it, the way they assume
undo exists. `penpot-rename-layers` ships as `mode: autofix`, which presumes mutation someone
can walk back, and an agent that cannot correct its own mistakes can only add to them.

## Before Start

- [x] Re-read `delete-shapes` — `(page-id ids options)`; `ids` must be a set
- [x] Find the duplicate event — **`dws/duplicate-shapes` (selection.cljs:451) is not selection-bound and takes a `return-ref`**; no need for `duplicate-selected`
- [x] Check what `delete-shapes` refuses — nothing relevant; `duplicate-shapes` is the one that filters (see Notes)
- [x] Confirm both are covered by an undo transaction — **verified live: ⌘Z restores a deleted shape as one step**

## Checklist

- [x] Write tests for validation
- [x] Add `delete_shape` and `duplicate_shape` to `tool-specs`
- [x] Implement, wire into dispatch
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: created 3, deleted 1, ⌘Z restored it, siblings untouched
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

## Notes — what execution found

**The open question, decided: option 1, shipped plain.** No policy, no session scoping. It is
genuinely undoable (verified), the enforcement layer exists for real rules, and option 2 —
"only delete what you created" — would have made the tool unable to do the job it exists for
("clean up these layers" is about *existing* shapes). The result note says
`deleted — undo with ⌘Z (it is one undo step)` so the user's escape hatch is stated, not
assumed. Option 3 (a `no-delete` rule beside `token-only-colors`) remains the escape hatch if
review surfaces nerves.

**⌘Z verified, not assumed** — the note makes a promise, so it was tested: create 3 → delete 1
→ `emit! dwu/undo` → the shape is back, siblings untouched. (`dwu/undo` is a `def`, a ptk value,
not a fn — calling it as a function is a quiet trap.)

**`duplicate-shapes` filters silently too**, exactly like the variant events: it drops anything
`ctc/allow-duplicate?` refuses (shapes inside a component copy, whose structure the main owns)
and then `(when (seq ids) …)` — an empty set does nothing and reads as success. Rejected up
front, naming the shapes and the two ways out (duplicate the main, or detach the copy).

**The tool caught itself lying, which is the phase's best moment.** The first draft's note said
*"duplicated, offset from the original"* — reasonable, since `move-delta? true` is what ⌘D
passes. Live, the copy landed at exactly the original's x/y. `calc-duplicate-delta`
(`selection.cljs:439`) says why, in Penpot's own comment:

> The default is leave normal shapes in place, but put new frames to the right of the original.

`move?` is `(and (frame-shape? obj) (not (instance-head? obj)))` — **only boards are offset**.
Measured: board 3600 → 3770 (width 120 + 50), rect 3600 → 3600. So the note now tells the truth,
and the truth is the useful part: a duplicated rect is *invisible* until moved, and the agent has
to be told or it will duplicate, see nothing, and duplicate again. A test pins the description
against the source's behaviour.

That is the same failure this plan exists to prevent, one level up: not a silent no-op, but a
**note that confidently describes something that did not happen**. Worth remembering that a
result message is an assertion, and assertions get verified.

**`return-ref` holds one id, not a collection** (`selection.cljs:537` resets it to
`id-duplicated`), and it is set from an `rx/tap` on the emitted stream, so it can still be nil
when the tool returns. The id is reported only when present *and* unambiguous (a single input);
never guessed. Same discipline as Phase 04's selection-diff.

## Notes — from planning

Set-vs-vector matters: `delete-shapes` asserts a set, `combine-as-variants` wants a vector for
ordering. Two conventions live side by side in this file now. Convert at the tool boundary and
say which you're passing.
