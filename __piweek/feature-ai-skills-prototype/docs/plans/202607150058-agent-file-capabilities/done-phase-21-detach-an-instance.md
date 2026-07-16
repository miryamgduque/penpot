# Phase 21 — Detach an instance

**Status:** done

The missing half of Wave 5. Phase 13 lets the agent place an instance; nothing lets it sever
one. The playbooks treat `detach()` as a real, governed move — mentioned ×5, always with rules
attached: *"Never `detach()` an instance without explicit approval; report it if you do"*, and
gotcha #12's hard prohibition — detaching a **variant** instance *"has corrupted files and hung
all subsequent saves in live sessions."* The audit skill also flags "detached parts that should
be component instances", so the concept crosses read and write.

Today the agent hits the wall from the other side: `duplicate_shape` and `group_shapes` both
reject shapes inside a component copy with *"…or detach the copy first"*
([agent_tools.cljs:858,930](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs))
— naming a corrective action that does not exist in the registry. A rejection message that
recommends a tool we never shipped is the plan's own standing rule pointed at itself.

`dwl/detach-component` / `detach-components` exist and are UI-called
([libraries.cljs:662,681](../../../../../frontend/src/app/main/data/workspace/libraries.cljs)).

## Before Start

- [x] Verify plan is still valid
- [x] Read `detach-component` / `generate-detach-component` — no variant guard of its own; it detaches whatever it is given
- [x] **Reproduce or refute gotcha #12 natively — REFUTED** (see Notes)
- [x] Enumerate the guard cases — not-a-copy, main, variant member, nested-inside-a-copy (names the root)
- [x] Check what the result carries — the copy's root id, which is what the agent keeps working on

## Checklist

- [x] Write tests: each guard rejects with a message naming the fix; a plain copy detaches
- [x] Add `detach_instance`, implement, wire into dispatch
- [x] The description states the governance the skills teach: prefer editing the main; last resort; report it
- [x] **Cross-check the "…or detach the copy first" messages** — all three now name `detach_instance` (see Notes)
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: placed an instance, detached it (linked → severed, shape intact); a variant member is refused
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec, guards, dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — guard-case tests

## Notes — what execution found

**Gotcha #12 does not reproduce natively. This is the phase's real finding.** The corpus's
scariest sentence — detaching a variant instance *"has corrupted files and hung all subsequent
saves in live sessions"* — is about the **plugin** path. Tested through `dwl/detach-component`
on a scratch file:

| check | result |
|---|---|
| saves hung? | no — revn **97 → 98 → 99 → 100** |
| subsequent edits persisted? | yes — two shapes made *after* the detach survived a reload |
| file corrupted? | no — reloaded clean, 145 objects |

So the variant guard refuses for the **honest** reason: a member is the set's *structure*, and
detaching it would gut the set. Repeating "this corrupts files" would have been a plausible,
frightening claim we could not reproduce — and the agent acts on these messages. A test asserts
the word "corrupt" never appears in that rejection, so nobody restores it from the playbook
later without re-testing.

**This does not mean detach-a-variant-instance is *wise*** — the skills fence it, and the tool
still says detaching anything is a last resort. It means the tool's stated reason is now the one
we verified rather than the one we inherited.

**The plan's standing rule, pointed at itself — now closed.** `duplicate_shape`,
`group_shapes` and `ungroup_shapes` all rejected with *"…or detach the copy first"*, naming a
corrective action that did not exist in the registry. All three now name `detach_instance`
explicitly, so the recommendation is actionable rather than aspirational.

**The description is mostly policy, as the phase predicted**, and it carries the governance the
skills already teach: prefer editing the main (every copy follows), a real difference is
probably a *variant*, and **tell the user** — a detach is not undone by editing the main.

**Guards, verified live:**

```
a variant member → "is a member of a variant set — a member is the set's structure, so
                    detaching it would gut the set. Detach a COPY of it instead…"
a plain rect     → "is not a component copy — it has no main to be severed from"
a main           → "is a component's MAIN — it IS the component… you probably meant a copy"
a piece of a copy→ "is a piece of a copy, not the copy itself — detach its root instead: <id>"
```

## Notes — from planning

This is the one tool in the plan whose *description* is mostly policy. The capability is a
one-liner over `dwl/detach-component`; the value is refusing the dangerous shape of it and
teaching the safe one. Cheap to build, and it closes every "detach the copy first" message we
already emit.

Component **swap** (`dwl/component-swap`,
[libraries.cljs:1044](../../../../../frontend/src/app/main/data/workspace/libraries.cljs)) was
checked and deliberately left out: every playbook "swap" is a *token* swap. If instance-swapping
demand appears, it slots here as a sibling phase.
