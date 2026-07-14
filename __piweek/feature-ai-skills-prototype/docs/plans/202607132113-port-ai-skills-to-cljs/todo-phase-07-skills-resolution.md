# Phase 07 — Backend skills resolution + `get_design_skills` + system prompt

**Status:** todo

## Goal

Move the skills **cascade resolution** to the backend: port `skills-core`'s `parseSkill`/`resolveCascade`/`skillManifest` to Clojure, add a `:get-effective-skills` RPC that resolves app→team→file into an effective manifest, back the native `get_design_skills` tool with it, and enrich the agent system prompt with the resolved skills, rules manifest, and local bodies.

## Before Start

- [ ] Re-read the cascade to port (`skills-core/src/resolve.ts:21-82`, `parse.ts:9-73`, `types.ts:10-75`): scope order `platform(app)→org(team)→project→file`, `scopeRank`/`enforcementRank`, **mandatory no-loosening** (broader mandatory raises enforcement), disabled filtering (mandatory immune), `kind` inference (advisory→skill else rule), `skillManifest` (lean, no bodies)
- [ ] Re-read the DB→scope mapping + disabled construction (`ai-skills/src/plugin.ts:166-287`): app rows→platform, team rows→org, app `is_enabled=false` + team `overrides`→platform-disabled, team `is_enabled=false`→org-disabled, pluginData `disabled`→file-disabled
- [ ] Re-read the existing RPC (`backend/src/app/rpc/commands/design_skills.clj:50-57`) — `:get-design-skills` already returns `{:app :team :overrides}`
- [ ] Confirm the **file-scope gap**: file skills live only in `file.data.plugin-data[:shared/penpot-skills]` (`frontend/src/app/plugins/file.cljs:213-215`) → the client passes them to the RPC (avoids stale server decode)

## Checklist

- [ ] New Clojure ns (e.g. `app.rpc.commands.design-skills` helpers or `app.common.skills`) porting: frontmatter `parse-skill`, `resolve-cascade`, `skill-manifest`, `serialize-skill` (DB row → source), and the disabled-set construction. Unit-test against the same fixtures as the TS resolver (parity).
- [ ] `:get-effective-skills` RPC: params `{:team-id, :file-skills? (vector of source strings), :file-disabled? (vector of names), :name? , :include-bodies?}`. Reuse the app+team+overrides queries; merge file-scope sources; run the cascade; return the lean manifest, or one full body (`name`), or all bodies (`include-bodies`). Register in `rpc.clj` scan-ns.
- [ ] Frontend: `get_design_skills` tool calls `(rp/cmd! :get-effective-skills {…})`, passing the current file's pluginData skills/disabled. Fill the `read_design` skills + manifest seams from Phase 02.
- [ ] `build-system-prompt` (Phase 01 seam): add the platform-skill **routing index** (names + descriptions, bodies fetched on demand), the JSON **rules manifest**, the **local skill bodies** inline, and keep the operating-modes block — port `agent.ts:215-275` faithfully.
- [ ] Expose the resolved `enforced` rule set to the Phase 06 guard (replace the interim "rule active" check).
- [ ] `make lint/backend`, `make lint/frontend`, `make typecheck/frontend`, resolver unit tests
- [ ] Preview: with app/team skills seeded + a file-scope rule, the agent's `get_design_skills` returns the correct cascade (mandatory not loosened, disabled excluded); a matching task follows the fetched skill body.
- [ ] Human approval; commit `feat(backend): effective-skills cascade RPC + native get_design_skills`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link; note where the ported resolver lives (backend-only vs shared `common`) and the RPC param shape

## Files

- `backend/src/app/**/skills*.clj` *(new)* — Clojure port of parse/resolve/manifest + unit tests
- `backend/src/app/rpc/commands/design_skills.clj` — add `:get-effective-skills`
- `backend/src/app/rpc.clj` — register (if scan-ns doesn't auto-pick)
- `frontend/src/app/main/data/workspace/agent.cljs` — `get_design_skills` via RPC; enrich `build-system-prompt`

## Notes

- Resolution is server-side; **enforcement stays client-side** (Phase 06). The RPC's job is the manifest + which rules are `enforced`.
- File-scope transport = client param, per the locked decision. Revisit server-authoritative file-data decode only if a later story needs it.
- Port the resolver with parity tests — subtle drift in the disabled/mandatory rules silently changes the effective set.
