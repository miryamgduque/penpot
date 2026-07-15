# Phase 21 — Detach an instance

**Status:** todo

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

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Read `detach-component` and `generate-detach-component` — what it accepts (copy root vs nested shape), what it silently skips
- [ ] **Reproduce or refute gotcha #12 natively.** The corruption report is from the *plugin* path; test detaching a variant member through `dwl/detach-component` on a scratch file. If the native path is also unsafe, the guard is a hard refusal; if safe, the guard is still a refusal — variant members are the set's structure — but the message can say "delete the member or restructure the set" instead of "this corrupts files"
- [ ] Enumerate the guard cases: not-an-instance-copy, main instance (detaching a *main* is nonsense — the message should say what they probably meant), variant member, nested shape inside a copy (detach the copy root, and say which id that is)
- [ ] Check what the result should carry: detach makes shapes local — the agent needs the resulting root id to keep working on it

## Checklist

- [ ] Write tests: each guard case rejects with a message naming the fix; a plain copy detaches and reports the root id
- [ ] Add `detach_instance` to `tool-specs`, implement, wire into dispatch
- [ ] The description states the governance the skills already teach: prefer editing the main; detaching is a last resort and must be reported to the user
- [ ] Cross-check the `"…or detach the copy first"` messages in duplicate/group now that the tool exists (they finally point at something real)
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: place an instance, detach it, edit what came out; confirm a variant member is refused
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec, guards, dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — guard-case tests

## Notes

This is the one tool in the plan whose *description* is mostly policy. The capability is a
one-liner over `dwl/detach-component`; the value is refusing the dangerous shape of it and
teaching the safe one. Cheap to build, and it closes every "detach the copy first" message we
already emit.

Component **swap** (`dwl/component-swap`,
[libraries.cljs:1044](../../../../../frontend/src/app/main/data/workspace/libraries.cljs)) was
checked and deliberately left out: every playbook "swap" is a *token* swap. If instance-swapping
demand appears, it slots here as a sibling phase.
