# Phase 30 — Leave a note

**Status:** todo

Penpot has a native comment system; the agent cannot touch it. Review-mode skills (`audit`,
`design-to-code-review`, `document-handoff`'s critique cousin) currently deliver findings only
as chat text — ephemeral, unpositioned, invisible to a teammate opening the file tomorrow. A
positioned comment thread ("this fill is a raw hex — token `color.brand.primary` exists") is
the artifact a *design review* actually produces, pinned where the problem is.

Entry points (note: these are **backend** calls, not local store events):
`dc/create-thread-on-workspace` ([data/comments.cljs:115](../../../../../frontend/src/app/main/data/comments.cljs)
— params `page-id`, `file-id`, `position` (a point), `content`; it derives the frame itself)
and `:get-comment-threads` for the read side. The plugin wraps the same pair
([page.cljs:386,438](../../../../../frontend/src/app/plugins/page.cljs)).

**Attribution is the governance question.** A comment is written *as the current user* and
notifies collaborators — this is the plan's first outward-facing tool. The description must
require the agent to make authorship explicit in the content, and the default posture is:
comment when the user asked for a review to be *left on the file*, not as a side effect.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Confirm the notification blast radius: who gets pinged when a thread is created / replied to — this decides how loudly the tool description warns
- [ ] Scope: create + read threads first. Reply/resolve/delete exist (`comments.cljs:145-203` plugin-side) — decide whether resolve rides along (an agent that fixed a violation resolving its own thread is a natural loop) or waits
- [ ] Position semantics: the thread pins to a canvas point, not a shape id — decide how the tool takes "comment on shape X" (center? top-left offset?) and say which in the result
- [ ] Backend availability: comments need the backend session — check behavior in the prototype's auth state and fail loudly if unavailable

## Checklist

- [ ] Write tests: thread created at a shape-anchored position with agent-attributed content; missing content/shape rejected with the fix named; read returns threads with positions and resolved state
- [ ] Add `add_comment` (and `read_comments`; resolve if scoped in), implement, wire into dispatch
- [ ] Description carries the governance: authorship named in content, use when asked to leave a review
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: run an audit, leave one positioned finding as a comment, see it in the comments panel
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — specs, position resolution, dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs`

## Notes

Zero corpus demand — the playbooks' `document-handoff` deliberately builds *shape* annotations
instead, for a visible spec layer. The two are complements, not rivals: annotations for the
handoff artifact, comments for the review conversation. If this phase changes any skill's
method, that is a skill edit, not a tool default.
