# Phase 19 — Edit the words

**Status:** todo

The agent can put text on the canvas and never touch it again. `create_text`
([agent_tools.cljs:159](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs))
creates an auto-width string with a fill; after that there is no way to change the string, and
no way — at creation or later — to set font size, family, weight, alignment, line height or
letter spacing. Every text in an agent-built screen is the same default-sized paragraph.

The demand is structural rather than symbol-counted: the playbooks say `heading` ×8 and
`label` ×10, `build-screen`'s method is boards full of typed text, and
`penpot-design-to-code-review` audits an entire typography dimension —
*"typography (family/size/weight/line-height/letter-spacing)"* — that the agent can neither
read nor write. `penpot-foundations` builds a type scale of `fontSizes` tokens (Minor Third)
that today can be authored (Phase 08) but never expressed on an actual heading.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Re-read `create-text` (agent_tools.cljs) — `txt/change-text` already writes content into a shape's `:content`; check whether it can rewrite an *existing* shape's content or only seed a new one
- [ ] Read `texts.cljs` — `update-text-attrs` ([:464](../../../../../frontend/src/app/main/data/workspace/texts.cljs)), `update-root-attrs` (:417), `update-paragraph-attrs` (:437), `update-text-with-function` (:515). Determine which work headlessly (no open editor) — the agent never has an editor session open
- [ ] Check the wasm resize path: `create-text` already calls `dwwt/resize-wasm-text-debounce`; a content or font-size change also invalidates size
- [ ] **Check the token path first.** `apply_tokens` may already bind `fontSizes`/`letterSpacing` tokens to text attrs (`dwta/token-properties` carries text attrs — [application.cljs:301](../../../../../frontend/src/app/main/data/workspace/tokens/application.cljs)). Whatever it covers, this phase covers the rest; whatever it should cover, the tool description should point at it (same relationship `fill` has with color tokens)
- [ ] Font family is not a free string — `update-font-family` resolves against loaded fonts ([application.cljs:386](../../../../../frontend/src/app/main/data/workspace/tokens/application.cljs)); decide how the agent discovers valid families and how an unknown family is rejected (name the fix in the message, per the standing rule)
- [ ] Decide the surface: widen `create_text` + a new `set_text` (content and/or style), vs pushing style into `modify_shape`. Lean `set_text`: text attrs are a different vocabulary from geometry, and `modify_shape` is already the widest tool

## Checklist

- [ ] Write tests: change content; set size/weight/align; reject an unknown font family with an actionable message; a fill change under `token-only-colors` enforcement is guarded (text fills are colors too)
- [ ] Widen `create_text` (fontSize, fontFamily, weight, align at minimum — creating right beats creating then fixing)
- [ ] Add `set_text` for existing shapes (content + the same style params)
- [ ] Wire into dispatch
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: a screen with a real heading, body copy and a relabeled button — then rewrite the heading in place
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — widen `create_text`, add `set_text`, dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — validation + guard tests

## Notes

**Typography tokens stay out of scope.** `composite-token-types`
([agent_tools.cljs:63](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs))
excludes `typography` from `create_token` because its value is structured and the tool would
author something malformed without complaint. This phase gives the agent the *raw* text-styling
path; lifting the composite-token exclusion is its own decision for another day, and nothing
here should preclude it.

**Content vs layer name.** `modify_shape`'s `name` renames the *layer*; the transcript pattern
to watch for is the agent renaming a text layer and believing it changed the words. `set_text`'s
description should say the difference out loud.
