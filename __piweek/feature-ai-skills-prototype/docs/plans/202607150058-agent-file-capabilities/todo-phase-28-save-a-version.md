# Phase 28 — Save a version

**Status:** todo

The corpus's scariest sentence is gotcha #12: mutating variants through the plugin *"has
corrupted files and hung all subsequent saves in live sessions."* Its prescribed defense is
manual: *"duplicate the file before Phase 3, verify saves after the first mutation."* Penpot
has a first-class answer the agent cannot reach — file version snapshots — and the internals
even ship a purpose-built headless entry: `create-version-from-plugins`
([versions.cljs:343](../../../../../frontend/src/app/main/data/workspace/versions.cljs))
takes a file-id and a label, force-persists, snapshots via the backend, and resolves with the
result. This is the cheapest insurance in the whole plan.

`save_version` also pairs with Phase 24 exactly where undo is weakest: undo covers the last
step; a snapshot covers *the next hundred*. "About to run a 40-tool build-screen session —
snapshot first" is the skill-shaped use, and several playbooks already gate risky sections
with checkpoints a snapshot would make real.

**Restore is deliberately OUT.** `restore-version-from-plugin` exists (:377), but restoring
replaces the working file — that is the user's move in the History panel, with their own eyes
on what they lose. The tool's result should say where to find it ("History panel › pinned
versions") rather than offer to do it.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Read `create-version-from-plugins` — error paths (reject callback), what happens mid-unsaved-changes, and the force-persist's cost on a large file
- [ ] Check quota/limits: does the backend cap snapshots per file? A looping agent must not be able to spam 50 versions — decide a per-session cap or a same-label dedupe here
- [ ] Decide label policy: agent-provided label with an enforced prefix (e.g. "agent: before variant surgery") so the History panel shows provenance
- [ ] **Consider `validate()` as the other half.** `cfv/validate-file` (the plugin's `file.validate()`, [file.cljs:155](../../../../../frontend/src/app/plugins/file.cljs)) checks referential integrity — a post-risky-op validate + pre-risky-op snapshot is the full gotcha-#12 defense. Decide whether it rides along in this phase or waits

## Checklist

- [ ] Write tests: snapshot created with the labeled prefix; empty/blank label rejected; the cap/dedupe policy holds
- [ ] Add `save_version` to `tool-specs`, implement over `dwv/create-version-from-plugins`, wire into dispatch
- [ ] (If in scope) `validate_file` reporting integrity findings
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: snapshot, confirm it appears in the History panel with the agent-prefixed label
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec, label policy, dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs`

## Notes

The skill bodies can start prescribing it the day it lands: "✋ Checkpoint: save_version before
this phase" is strictly better than "duplicate the file first", and unlike the gotcha's advice
it is something the agent can actually do.
