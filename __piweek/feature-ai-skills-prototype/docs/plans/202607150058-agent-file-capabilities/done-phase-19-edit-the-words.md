# Phase 19 — Edit the words

**Status:** done

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

- [x] Verify plan is still valid
- [x] Re-read `create-text` — `txt/change-text` is a pure content transform, so it rewrites an existing shape's `:content` just as well as it seeds a new one
- [x] Read `texts.cljs` — **`update-text-attrs` needs an open editor** (`[:workspace-editor-state id]`); `update-paragraph-attrs` has a headless `when-not` branch that falls through to `update-shapes`. The agent has no editor, so that branch is the one
- [x] Check the wasm resize path — a content change invalidates size; `resize-wasm-text-debounce` is emitted
- [x] **Check the token path first** — **it already covers most of this phase** (see Notes)
- [x] Font family is not a free string — **moot: family is a token attr, so `apply_tokens` resolves it** and no font-name validation is needed here
- [x] Decide the surface — `set_text`, as the phase leaned. Text is a different vocabulary from geometry, and `modify_shape` is already the widest tool

## Checklist

- [x] Write tests: change content; align; reject an unknown align; reject a non-text shape
- [x] ~~Widen `create_text` (fontSize, fontFamily, weight, align)~~ — **align only**; the rest is the token path (see Notes)
- [x] Add `set_text` for existing shapes (content + align)
- [x] Wire into dispatch
- [x] Lint pass — clj-kondo 0/0, cljfmt clean
- [x] Preview review: created a centred heading, rewrote the words in place, alignment survived
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — widen `create_text`, add `set_text`, dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — validation + guard tests

## Notes — what execution found

**"Check the token path first" was the right instruction, and it cut this phase in half.**
`apply_tokens` already reaches **fontSize, fontFamily, fontWeight, letterSpacing, lineHeight,
textCase and textDecoration** — Phase 09 derived the attr map from `cto/all-keys` and swept them
in without anyone noticing. Verified live, not assumed:

```
create_token fontSizes "font.heading" = 32
apply_tokens {fontSize} on a text  → content font-size "32", shape resized to 38px
```

So the phase's premise — *"every text in an agent-built screen is the same default-sized
paragraph"* — was only half true. `penpot-foundations`' Minor Third type scale already works
end to end, through the path that skill actually prescribes (tokens), and has since Phase 09.

**What was genuinely unreachable: the words, and alignment.** There is no `text-align` token, so
alignment is a real gap. Content had no path at all. That is what shipped: `set_text` (words +
align) and `align` on `create_text`. Font size/family/weight are *deliberately absent* — the
descriptions point at `apply_tokens` instead, the same relationship `fill` has with colour
tokens, and exactly what the phase's own Before Start asked for ("whatever it covers, this phase
covers the rest; whatever it should cover, the tool description should point at it").

Adding raw `fontSize` would have duplicated the token path and quietly competed with
`penpot-component-factory`'s One Rule. The narrower tool is the more opinionated one.

**The headless question mattered.** `update-text-attrs` writes to `[:workspace-editor-state id]`
— a Draft.js editor session the agent never has, so it would have done *nothing*, silently.
`update-paragraph-attrs` has a `when-not` branch that falls through to `update-shapes` when no
editor is open. That branch is the whole align path, and it is why the phase said to check.

**`change-text` keeps the first paragraph's styling**, so rewriting the words does not reset the
look. Verified: a centred heading rewritten in place stayed centred.

**The layer-name trap is now a rejection**, per the phase's own note:

```
set_text on a board → "Card" (…) is a frame, not a text shape — note modify_shape's
                      `name` renames the LAYER, it does not change the words
```

**`""` is a real edit** (it clears the text), so presence is `some?`, not truthiness — the fifth
outing for this trap after `absolute false`, `"0"` tokens, `x: 0` and `opacity 0`.

**Unrelated bug found by the linter, worth recording.** Consolidating the shared helpers
surfaced two `deftest` names colliding with earlier ones — and a redefined `deftest` **silently
replaces** the original, so Phase 06's `a-call-with-nothing-to-change-is-rejected` and Phase 16's
`an-unknown-shape-is-rejected` had stopped running. Renamed; the suite went 700 → 702. The same
silent-substitution failure this plan is about, this time in its own tests.

**Also tidied:** `shape-label`, `labels` and `enum-problem` had scattered through the file as it
grew, and every new tool that reached for one hit a forward reference (four times). They now sit
in one block above every user.

## Notes — from planning

**Typography tokens stay out of scope.** `composite-token-types`
([agent_tools.cljs:63](../../../../../frontend/src/app/main/data/workspace/agent_tools.cljs))
excludes `typography` from `create_token` because its value is structured and the tool would
author something malformed without complaint. This phase gives the agent the *raw* text-styling
path; lifting the composite-token exclusion is its own decision for another day, and nothing
here should preclude it.

**Content vs layer name.** `modify_shape`'s `name` renames the *layer*; the transcript pattern
to watch for is the agent renaming a text layer and believing it changed the words. `set_text`'s
description should say the difference out loud.
