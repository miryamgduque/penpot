# Phase 23 — Draw it in SVG

**Status:** done (worktree caps-23-32; suite+live deferred to merge)

Vector paths are a true blank in the registry: `create_shape` offers rect/ellipse/board, no
tool can create or edit a path, and `summarize-shape` reports one only as `type: "path"` plus
its box. An agent asked for an icon today has exactly the improvisation this plan's standing
rule forbids — fake it out of rects — or the correct-but-empty "I can't draw that".

Playbook demand for *path editing* is zero (every `path` match is prose — "the only mutation
path"), so point-level bezier surgery stays out. But the demand that does exist — icons and
small illustrations inside components and screens (`icon` appears in the naming taxonomy and
severity rubrics) — has a much cheaper answer the internals already ship: **SVG import**.
`dwm/create-svg-shape` ([media.cljs:483](../../../../../frontend/src/app/main/data/workspace/media.cljs))
turns an SVG string into real Penpot shapes (it powers paste-SVG, and the plugin API exposes it
as `createShapeFromSvg`), with `valid-svg-string?` (:47) as the gate. Models are good at
writing SVG; the tool is a passthrough to a shipped, tested pipeline.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Read `dwm/create-svg-shape` + `create-svg-shape-with-images` ([media.cljs:483,492](../../../../../frontend/src/app/main/data/workspace/media.cljs)) and the plugin caller ([api.cljs:434](../../../../../frontend/src/app/plugins/api.cljs)) — what id/position they take, what arrives selected, what the result shape tree looks like (an `<svg>` with several elements imports as a group)
- [ ] Decide the images question: `create-svg-shape` alone rejects/ignores embedded rasters; `-with-images` runs the media upload pipeline. Start without images (an icon tool doesn't need them) and say so in the description, or gate on it — decide, don't drift
- [ ] Check failure shape: what an invalid SVG string produces, and whether a *valid-but-empty* SVG imports as a silent nothing (this plan's recurring enemy) — the tool must report the created root id or refuse loudly
- [ ] Check `color-violation`: an SVG carries its own fills, which bypass the `token-only-colors` gate the other create tools honor. Decide the policy — icons are the classic legitimate raw-color case (currentColor, brand marks), but the decision should be written down, not accidental. Options: exempt (audit_file still catches it), scan the SVG's fills, or apply-then-report
- [ ] Sizing: imported SVG lands at its intrinsic size at a position — decide whether the tool takes x/y (+ optional width/height scale) like `create_shape`, and what "resize after import" costs

## Checklist

- [ ] Write tests: valid SVG creates shapes and returns the root id; invalid SVG rejected with a message naming the fix; empty result is an error, not a success
- [ ] Add `create_from_svg` to `tool-specs` (x, y, svg string, optional name), implement over `dwm/create-svg-shape`, wire into dispatch
- [ ] Description tells the agent what this is for (icons, small illustrations) and what it is not (not a screen builder — boards, layout and components stay the method for structure)
- [ ] Lint pass (`lint:clj` + `check-fmt:clj`, in the devenv — there is no Makefile)
- [ ] Preview review: ask the agent for an icon; confirm it draws one instead of composing rects, and that render_board shows it
- [ ] Human approval received
- [ ] Committed with a gitmoji commit (`:sparkles:`)

## After Finish

- [ ] Rename this file: `todo-` → `doing-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs` — spec, validation, dispatch
- `frontend/test/frontend_tests/data/agent_tools_test.cljs` — valid/invalid/empty cases

## Notes

**Path *editing* is explicitly out of scope, and should stay out.** `app.main.data.workspace.path`
is an interactive editor state machine (drag sessions, snapping, hover state) with no headless
"set this path's content" event — wrapping it would be the most expensive tool in the plan, for
a capability no playbook asks for. If an icon is wrong, the agent's move is delete + re-import,
which Wave 4 already covers. Revisit only if a skill starts teaching point-level editing.

**Watch for emulation in reverse.** Once this lands, the failure mode flips: an agent might
reach for SVG to build things that should be boards + layout + components (an entire card as
one path is unauditable, untokenizable, unthemeable). The description's "what it is not" line
and the token/audit rules are the counterweight; check for it in the preview review.
