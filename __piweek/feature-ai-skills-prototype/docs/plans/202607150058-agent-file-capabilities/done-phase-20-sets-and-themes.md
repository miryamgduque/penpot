# Phase 20 — Sets and themes

**Status:** done

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

- [x] Verify plan is still valid
- [x] Re-read `create-token` + `existing-token-set-id` and the Phase 08 wipe hazard — **kept fixed**: the default path is untouched, and `create_token_set` refuses to overwrite an existing set (that overwrite *was* the wipe)
- [x] Read the set/theme events end to end — `create-token-set` asserts and selects; `create-token-theme` refuses a duplicate id; `toggle-token-theme-active` ends with `propagate-workspace-tokens`, which is why the switch re-resolves bound shapes
- [x] **Check what theme activation does to `get-tokens-in-active-sets`** — **the native path does not share the plugin's failure** (see Notes)
- [x] Decide the read side — sets + themes, present only when the file has any
- [x] Decide the tool surface — separate `create_token_set` / `create_token_theme` / `activate_theme`, matching the registry's grain

## Checklist

- [x] Write tests: token lands in the named set; a missing set is rejected with the real names; the Phase 08 wipe regression stays covered
- [x] `create_token` accepts a `set` (default: previous behaviour)
- [x] Add set/theme tools, wire into dispatch
- [x] `read_design` surfaces sets (active flag) and themes
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] **Preview review: the switch works** — light/dark pair, a theme each, toggle, bound shape changes (see Notes)
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — `set` param, new tools, read side
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — set targeting, rejection messages, theme switch

## Notes — what execution found

**The switch works, and the native path does not share the plugin's failure.** This was the
phase's stated acceptance and its open question — `penpot-foundations` warns that the *plugin's*
`theme.addSet()` "doesn't persist; the plugin's read lags". Verified live:

```
create_token_set "modes / light" → normalised to "modes/light"
create_token_set "modes/dark"
create_token color.bg = #ffffff  (set: modes/light)
create_token color.bg = #111111  (set: modes/dark)
create_token_theme Light [modes/light] / Dark [modes/dark]

activate_theme Light → apply_tokens color.bg on a shape → fill #ffffff
activate_theme Dark                                     → fill #111111
```

**Bound once, under Light. Nothing re-applied.** The playbook's own claim — that bindings are by
*name* and re-resolve on a set/theme change — is true here, and `toggle-token-theme-active`
ending in `propagate-workspace-tokens` is why. That is the finding the phase asked for, and it
is the good version: dark mode is reachable natively even though the plugin cannot deliver it.

**The Phase 08 wipe stayed fixed, and shaped a rejection.** `create-token-set` commits over an
existing set — which is *precisely* the mechanism that wiped the library in Phase 08. So
`create_token_set` refuses a name that already exists rather than silently replacing it (and its
tokens), and the message points at `create_token`'s `set` param instead. The default targeting
path is untouched.

**A `set` param that shadowed `clojure.core/set`.** `(defn token-set-problem [set-names set] …
(contains? (set set-names) want))` — the parameter shadows the core fn, so `(set set-names)`
called a *string*: `TypeError: set.call is not a function`. Caught by the tests, not by review.
Renamed to `set-name`, `create_token` destructures `:set` as `target-set`, and the sites use
`(into #{} …)`. Worth remembering: `set`, `name`, `type` and `key` are all easy accidental
shadows, and this file destructures all four.

**The read side is what makes any of it usable.** `read_design` flattens tokens through
`get-tokens-in-active-sets`, so before this the agent could not see that an inactive
`modes/light` existed at all — two same-named tokens were simply one. Now:

```json
sets:   [{"name":"Global"},{"name":"modes/light"},{"name":"modes/dark","active":true}]
themes: [{"name":"Light","sets":["modes/light"]},{"name":"Dark","sets":["modes/dark"],"active":true}]
```

Present only when the file has them, so a single-set file pays nothing. Payload 70%.

**A theme with no sets is rejected** — it would activate and change nothing, which reads as a
broken tool rather than an empty theme. Every rejection names the real sets or themes.

## Notes — from planning

**The switch is the demo.** The acceptance moment for this phase is the one the playbook says
the plugin cannot deliver: the agent builds light + dark, activates the dark theme, and a bound
shape visibly changes. If the native path can't deliver it either, that is a finding to record
here, and the tools should say so rather than pretend (standing rule 2).

**Bindings survive by name.** The playbook's own note — shapes bound to token *names*
re-resolve when sets/themes change — is why this phase needs no re-apply step. Worth asserting
in a test rather than trusting.
