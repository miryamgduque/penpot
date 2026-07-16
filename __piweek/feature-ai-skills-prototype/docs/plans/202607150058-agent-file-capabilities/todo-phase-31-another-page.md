# Phase 31 — Another page

**Status:** todo

The agent lives its whole life on the current page: `read_design` reports one page's shapes
and never mentions that others exist, and no tool can create, rename or duplicate a page. A
multi-page file — design on one page, explorations on another, the classic "playground" page a
skills session should be making its mess on — is invisible and unreachable.

Internals: `create-page` ([pages.cljs:162](../../../../../frontend/src/app/main/data/workspace/pages.cljs),
auto-names "Page N"), `rename-page` (:325), `duplicate-page` (:186), `delete-page` (:372).

Two boundaries to draw deliberately:

- **Switching pages is navigation, not mutation.** The tools all operate on *the current*
  page; making another page current moves the user's canvas out from under them
  (`dcm/go-to-workspace`). Creating a page without switching to it is safe; whether the agent
  may switch — and whether the other tools should accept a `pageId` instead — is the design
  decision of this phase.
- **`delete_page` is `delete_shape` times everything on it.** If it ships at all, it refuses
  non-empty pages by default, naming the count of what would go.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Decide the operating model: (a) tools stay current-page + a `switch_page` navigation tool (moves the user — disclose loudly in the result), or (b) mutating tools accept optional `pageId` (no navigation, but every problem-checker's `lookup-page-objects` needs the page threading). (a) is a far smaller change; (b) is the better end state — decide which this phase buys
- [ ] `read_design` gains the page list (names + ids + which is current) — cheap, and useful even if nothing else ships
- [ ] Check `create-page`'s id/naming knobs — the agent should name the page at creation, not create-then-rename
- [ ] Decide `delete_page`: in with the empty-only guard, or out entirely (duplicate + rename cover most tidiness)

## Checklist

- [ ] Write tests: create with a name; rename; duplicate; page list read; (if in) delete refuses a non-empty page naming what it holds
- [ ] Add `create_page` / `rename_page` / `duplicate_page` (+ the switching decision's outcome), wire into dispatch
- [ ] `read_design` lists pages
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: agent sets up a named playground page and reports how the user can switch to it
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — specs, page threading or switch tool, read side
- `frontend/test/frontend_tests/data/agent_tools_test.cljs`

## Notes

Zero corpus demand (the playbooks are single-page by habit, possibly *because* their runtime
was too). The playground-page pattern is the use to watch: "try it on a scratch page first"
composes beautifully with Phase 28's snapshots as the low-risk workflow story.
