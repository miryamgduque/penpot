# Phase 06 — Retire the old plugin-panel buttons & relay

**Status:** done

## Goal

Remove the now-superseded dual plugin-panel entry points: the two old header buttons (feedback + puzzle) and the `window.postMessage` relay wiring that fed the plugin iframes. Keep the backend RPC (`:ai-agent-round`, `:get-ai-providers`) and the US #1 provider data untouched.

**`ai-skills` (the React app) is deliberately RETAINED** — it is the reference base for the separate CLJS porting plan ([`../<ts>-port-ai-skills-to-cljs/`](../)). Do **not** delete it here. This phase only decommissions the *workspace-panel entry points* (the two buttons + the relay listener) that the native shell replaces; the app itself and its `plugin-*.ts` bundles stay on disk until the port is complete and verified, after which a coordinated cleanup removes them.

## Before Start

- [ ] Grep for remaining callers of `app.main.data.workspace.skills` (`dwsk/*`) — confirm the only workspace-panel callers are the two buttons + the `right_header.cljs` message listener
- [ ] Confirm nothing outside the workspace panel depends on `open-panel`/`toggle-panel`/`skills-dock`
- [ ] Confirm the native panel (Phases 01–05) fully covers what the two old buttons did for this story's scope

## Checklist

- [x] Removed the `window` "message" relay listener block in `right_header.cljs` + the `dwsk` require. ✓
- [x] `ref:skills-dock` + the two old buttons were already gone (Phase 02) — confirmed no leftovers. ✓
- [x] `data/workspace/skills.cljs` was fully superseded (its only consumer was that listener) — **deleted the whole ns**. ✓
- [x] Removed the orphaned `skills-plugin-id` / `skills-manager-plugin-id` defs + their `check-permission` bypass in `register.cljs`. ✓
- [x] Left `data/ai_providers.cljs` + the backend `:ai-agent-round` RPC intact. ✓
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, no lingering refs to the deleted ns, `shadow-cljs compile main` → **0 warnings** (1178 files, down from 1179). ✓
- [x] Preview review (live devenv): no regression — panel still opens/closes via the button after cleanup; app loads clean. ✓
- [ ] Human approval received
- [ ] Commit: `refactor(workspace): retire plugin-iframe ai panel relay`

## After Finish

- [ ] Rename `todo-` → `done-`, update README link
- [ ] In Notes, record what remains of `skills.cljs` / the React `ai-skills` app and file a follow-up for full removal (coordinate with the skills-management story)

## Files

- `frontend/src/app/main/ui/workspace/right_header.cljs` — drop the message listener + old buttons/ref
- `frontend/src/app/main/data/workspace/skills.cljs` — remove superseded toggle/relay code (or delete ns)

## Notes

- This is intentionally a *decommission of the workspace surface only*, not a teardown of the whole prototype — the React app + MCP path may still be exercised elsewhere on the branch. Full cleanup is a coordinated follow-up, consistent with the `__piweek/` "delete before PR" plan.
- If any of `skills.cljs` must stay (e.g. `fetch-and-push-scopes` reused by the native panel for skills data), keep the minimal slice and document why.
