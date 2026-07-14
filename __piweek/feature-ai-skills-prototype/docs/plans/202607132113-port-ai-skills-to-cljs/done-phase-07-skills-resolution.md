# Phase 07 — Skills → agent (lighter, built on Miryam's catalog)

**Status:** done

## ⚠️ Redesigned (2026-07-14)

The original plan (below) was to port the **DB-backed cascade** (`resolveCascade` → a Clojure `:get-effective-skills` RPC). **Miryam's US #7 replaced that model** with a **built-in skills catalog** (`skills-core/catalog.ts` `builtinCatalog()`, mirrored as a CLJS catalog in the Skills tab). Per the user's call ("lighter Phase 07 now"), this phase instead **wires the agent to that catalog** — no backend RPC, no cascade port. Blockers deferred: full skill **bodies** aren't in CLJS yet (only metadata), and Miryam's persisted **enabled-state** (her US #7 Phase 02) is still todo (we use the static `:enabled` defaults for now).

## What shipped

- [x] New `app.main.data.workspace.agent-skills` — the **single source of truth** for the built-in catalog: moved the `catalog` + `mode-label` defs out of `ui/workspace/ai_panel.cljs` (Miryam's Skills tab now requires them from here), plus `enabled-skills`, `catalog-manifest` (for the tool), and `system-prompt-section` (the routing index). ✓
- [x] `get_design_skills` tool (in `agent-tools`): returns the **9 enabled** catalog skills (metadata: name/category/mode/blurb), or one by name. Bodies deferred (documented). ✓
- [x] System prompt: filled the Phase 01 seam with the enabled-skills **routing index** + "call get_design_skills to read details and follow it". Verified the actual prompt string lists Foundations/Accessibility audit and excludes the disabled autofix. ✓
- [x] `enforced-rules`: left as-is (the Phase 06 mechanism, off by default) per the user's "simple default". Not wired to the catalog (rules aren't catalog skills). ✓
- [x] Lint / format / typecheck: `clj-kondo` 0/0, `cljfmt` clean, `stylelint` clean, `shadow-cljs compile main` → **0 warnings** (1181 files). ✓
- [x] Preview (live devenv): Miryam's Skills tab still renders (10 cards, 3 groups — no regression from the shared-catalog refactor); `get_design_skills` returns 9 enabled skills; **LLM (Haiku)** — "what design skills do you have?" → agent **called `get_design_skills`** and listed the real catalog skills by category/mode. ✓
- [ ] Human approval; commit `:sparkles: Wire the agent to the built-in skills catalog`

> **Coordination note for Miryam:** this moved your `skills-catalog`/`mode-label` defs from `ui/workspace/ai_panel.cljs` into the shared `data/workspace/agent-skills` ns (your Skills tab now `require`s `[… :as ask]` and uses `ask/catalog` / `ask/mode-label`). Single source of truth for the UI + the agent. When your US #7 Phase 02 lands persisted enabled-state, `agent-skills/enabled-skills` should read it instead of the static `:enabled` defaults.

---

## Original plan (superseded — kept for reference)

**Goal:** Move the skills **cascade resolution** to the backend: port `skills-core`'s `parseSkill`/`resolveCascade`/`skillManifest` to Clojure, add a `:get-effective-skills` RPC that resolves app→team→file into an effective manifest, back the native `get_design_skills` tool with it, and enrich the agent system prompt with the resolved skills, rules manifest, and local bodies.

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
