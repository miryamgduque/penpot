# Phase 20 — Sets and themes

**Status:** todo

`penpot-foundations` — the skill the corpus itself calls **load-bearing** — has dark mode as a
headline trigger (*"add dark mode tokens"*) and a method built entirely on multi-set structure:
primitives in one set, semantics duplicated across `modes/light` / `modes/dark`, and Penpot
token *themes* (`addTheme({ group, name })`) toggling the matching `modes/*` set. The playbooks
say `theme` ×16 and `dark` ×15.

None of that is reachable. `create_token` resolves its target set as *the first set in the
library* (`existing-token-set-id`,
[agent_tools.cljs:1489](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs))
— correct for the single-set file it was built against (and for the wipe-the-library bug it
fixed), wrong the moment sets are the point. There is no way to create a set, to aim a token at
one, to create or activate a theme, or to see any of this: `read_design` flattens
`get-tokens-in-active-sets` into one type-grouped map, so two same-named tokens in light/dark
sets are indistinguishable and *inactive* sets are invisible.

The internals are, as usual for this plan, complete: `create-token-set`
([library_edit.cljs:308](../../../../../frontend/src/app/main/data/workspace/tokens/library_edit.cljs)),
`create-token-theme` (:227), `toggle-token-theme-active` (:281), `toggle-token-set` (:380).

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `create-token` + `existing-token-set-id` and the Phase 08 notes on the library-wipe hazard — whatever changes here must keep that fixed
- [ ] Read `library_edit.cljs`'s set/theme events end to end: which are silent no-ops on bad input (this plan's recurring enemy), what naming/grouping rules `create-token-set` enforces (`modes/dark` is a *grouped* path)
- [ ] Check what theme activation actually does to `get-tokens-in-active-sets` — the foundations playbook warns the *plugin's* `theme.addSet()` "doesn't persist; the plugin's read lags". We are not the plugin; verify the native events don't share the failure before promising the switch works
- [ ] Decide the read side: `read_design` should name the sets (+ active/inactive) and themes without exploding the payload — the 20k truncation applies here too
- [ ] Decide the tool surface: `create_token` gains a `set` param; then either one `manage_token_sets` verb-style tool or separate `create_token_set` / `create_token_theme` / `activate_theme`. Separate tools match the registry's grain so far

## Checklist

- [ ] Write tests: token lands in the named set; naming a missing set is rejected with the existing set names in the message; theme creation + activation flips which tokens resolve; the Phase 08 wipe regression stays covered
- [ ] `create_token` accepts a `set` (default: current behavior)
- [ ] Add set/theme tools, wire into dispatch
- [ ] `read_design` surfaces sets (active flag) and themes
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: run the foundations skill's own demo — light/dark semantic pair, a theme per mode, toggle, watch a bound shape change
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `set` param, new tools, read side
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — set targeting, rejection messages, theme switch

## Notes

**The switch is the demo.** The acceptance moment for this phase is the one the playbook says
the plugin cannot deliver: the agent builds light + dark, activates the dark theme, and a bound
shape visibly changes. If the native path can't deliver it either, that is a finding to record
here, and the tools should say so rather than pretend (standing rule 2).

**Bindings survive by name.** The playbook's own note — shapes bound to token *names*
re-resolve when sets/themes change — is why this phase needs no re-apply step. Worth asserting
in a test rather than trusting.
