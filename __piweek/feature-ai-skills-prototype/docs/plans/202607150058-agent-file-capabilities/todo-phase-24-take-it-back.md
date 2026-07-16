# Phase 24 — Take it back

**Status:** todo

The agent's only recovery tool is `delete_shape` — which covers *creations* and nothing else.
A `modify_shape` that set the wrong fill, resized the wrong board, or renamed the wrong layer
is unrecoverable unless the agent happens to remember the prior value. Meanwhile its own tool
results say *"Undoable by the user with ⌘Z"* ([agent_tools.cljs:204](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs))
— recommending to the user the exact move the agent cannot make. Phase 11 was even *titled*
"Undo the agent's own mess" and shipped delete/duplicate; this closes the other half.

The mechanism is one emit away: `dwu/undo` / `dwu/redo` are the events ⌘Z dispatches
([shortcuts.cljs:71](../../../../../frontend/src/app/main/data/workspace/shortcuts.cljs)).
The phase is not the emit — it is the three ways the emit goes wrong:

1. **The stack is shared.** `:workspace-undo` is one per-session stack; the user's manual
   edits and the agent's interleave, and `stamp-entry` attributes both to the same profile
   ([undo.cljs:109](../../../../../frontend/src/app/main/data/workspace/undo.cljs)). A naive
   undo pops whatever is on top — including an edit the user made while the agent was thinking.
2. **Silent no-ops.** `undo` does nothing on an empty stack / index -1
   ([undo.cljs:338](../../../../../frontend/src/app/main/data/workspace/undo.cljs)) and while a
   text/path editor session is open — *"Editors handle their own undo's"* (:333). Both read as
   success to a thin passthrough.
3. **Coverage is uneven.** Tags are the guard hook — `dch/commit-changes` accepts `:tags` and
   they land on the entry ([changes.cljs:231-256](../../../../../frontend/src/app/main/data/changes.cljs))
   — but only for changes *we* commit. Tools that delegate to internal events
   (`combine-as-variants`, `instantiate-component`, `group-shapes`…) produce entries those
   events commit themselves, untagged unless they forward tags.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `undo.cljs` end to end: entry shape (`:timestamp :by :tags :undo-group`), `undo-to-index`, the editor-session guard, `MAX-UNDO-SIZE` 50
- [ ] **Pick the ownership check.** Option A — tag every agent mutation (`:tags #{:agent}`) and refuse when the top entry isn't tagged; requires auditing every mutating tool's emit path and forwarding tags through delegated events (check which internal events accept/forward commit opts). Option B — watermark: after each agent mutation, record the stack index; `undo_change` only fires when the stack hasn't grown since (nothing happened after the agent's write). B needs no changes to delegated events and fails safe on interleaving; A gives per-entry truth. Decide here, not mid-implementation
- [ ] Check `restore-selection`: undo restores `:selected-before`, mutating the user's selection — the same thing `duplicate_shape` deliberately refuses to do. Decide whether to suppress or accept + disclose in the result
- [ ] Decide `redo` scope: symmetric and cheap, but only meaningful immediately after the agent's own undo — consider allowing it only in that window (same ownership logic)
- [ ] Confirm the failure messages name the fix: "the last change isn't mine — I won't undo the user's work; use modify_shape/delete_shape on the specific shapes instead"

## Checklist

- [ ] Write tests: undoes its own last mutation; refuses when the top entry is not the agent's; refuses loudly on empty stack and during an editor session; redo only after own undo
- [ ] Add `undo_change` (and `redo_change` if kept) to `tool-specs`, implement over `dwu/undo`/`dwu/redo` with the ownership + no-op pre-checks
- [ ] Ownership plumbing (tags or watermark) across all mutating tools
- [ ] Update the tool descriptions that currently say "undoable by the user with ⌘Z" to point at the agent's own path where appropriate
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: agent makes an edit, regrets it, takes it back; then a human edit lands and the agent's undo is refused with the message above
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — tools, pre-checks, ownership plumbing
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — ownership + no-op cases

## Notes

**"Undo the user" is the one failure this tool must make impossible**, and it is worth more
than the capability itself. The refusal message doubles as an honest report ("something changed
since my last edit"), which is information the agent currently has no way to get.

**Scope stays at one step (or one agent transaction).** No `undo_n`, no history browsing —
`undo-to-index` exists but walking the user's history is not this tool's business. The history
panel is the user's; the agent gets "take back my last change" and nothing more.
