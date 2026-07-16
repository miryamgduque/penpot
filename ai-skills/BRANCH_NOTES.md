<!--
  ⚠️ TRANSIENT — DELETE BEFORE MERGE
  Working notes for collaborators on `feature/ai-skills-prototype`.
  Not documentation; not meant to survive the branch.
-->

# Branch notes — `feature/ai-skills-prototype`

Draft PR: https://github.com/miryamgduque/penpot/pull/1 (base: fork `develop`)

Scratch, shared-between-us notes. **Delete this file before merging.** Durable docs live in
[`README.md`](README.md).

## What this branch is

Design **skills & rules** for Penpot: intent that lives with the file/team/instance, inherited by any
agent, with the rules that matter **enforced at the write path**. Two kinds, cascaded **App → Team → File**:

- **skill** = knowledge for the agent (playbooks/conventions)
- **rule** = a checkable constraint (advisory / triggered / **enforced**)

## Where things live

| Area | Path |
|------|------|
| Shared core (parser, cascade resolver, guard) | `skills-core/` |
| Backend table + seed + RPC | `backend/src/app/migrations/{sql/0152-*,clj/migration_0153}.clj`, `backend/src/app/rpc/commands/design_skills.clj`, `backend/resources/app/design-skills-seed.json` |
| Dashboard CRUD page | `frontend/src/app/main/ui/dashboard/skills.{cljs,scss}`, `frontend/src/app/main/data/skills.cljs` |
| Native panels (dock, header buttons, DB push) | `frontend/src/app/main/data/workspace/skills.cljs`, `frontend/src/app/main/ui/workspace/right_header.cljs`, `frontend/src/app/plugins/register.cljs` |
| Panel app (React) | `ai-skills/` (`src/plugin.ts` core, `plugin-{chat,skills,main}.ts` entries, `src/ui/`) |
| MCP door (external agents) | `mcp/` |

## Run it (what actually worked, incl. gotchas)

```bash
./manage.sh run-devenv          # ws0, non-agentic; UI on http://localhost:3450 (https:3449)
cd ai-skills && npm install && npm start   # panel assets on :4500 (only needed to open the panels)
```

Gotchas hit while bringing it up:
- **Mailer port clash:** the shared infra `mailer` binds host `:1080`; if another stack (chiara maildev)
  owns it, `run-devenv` aborts at infra. The `main` container doesn't need the mailer — bring it up
  directly if infra half-starts: `source ./manage.sh; instance-compose ws0 up -d main` then
  `docker exec -d -e PENPOT_TMUX_ATTACH=false <main> sudo -EH -u penpot /home/start-tmux.sh`.
- **No email → activate account in DB:** register in the UI, then
  `update profile set is_active=true where email='…';` (mailer is down so the verify link never arrives).
- **Skip the onboarding modal:** `update profile set props = props || '{"~:onboarding-viewed": true}'::jsonb where email='…';`
- **`shadow-cljs — Reconnecting …` banner** is just the hot-reload websocket against the self-signed cert — cosmetic.
- Skills & Rules page: `/#/dashboard/skills?team-id=<id>` (or the tab in any team's settings).

Demo creds used in testing: `demo@skills.local` / `demopassword123` (DB volume persists across `stop-devenv`).

## Status

**Verified live (devenv):** migrations apply + seed 16 app rows; dashboard renders from Postgres; create
round-trips (modal → RPC → `db/insert!` → re-fetch); frontend compiles clean; `skills-core` tests pass;
all bundles build.

**Not yet driven live:** the file-level panel wiring — DB-scope push into the docked panels, panel
swap/active-state, cross-panel "Fix via chat". Implemented + builds, needs a design file open to exercise.
→ **When the DB push is confirmed working, do the fallback cleanup in "Candidates" below.**

## Cleanup

**Done this pass:** removed the dead **atomic design-tool** cluster from `ai-skills/src/plugin.ts`
(`setFill`/`createShape` fns + `set-fill`/`create-shape`/`rename-shape` ops, and the orphaned
`save-file-skills`/`get-violations` ops) — leftovers from before the chat tools were consolidated to
`read_design`/`apply_tokens`/`execute_code`. Also pruned now-unused imports.

**Candidates still on the table — ⏳ DO ONCE THE DB PUSH IS VERIFIED LIVE.**
These are all held back by one thing: the native DB-scope push into the panels isn't confirmed working
end-to-end yet (see "Not yet driven live" above). That push is what replaces the standalone fallback
below, so **once we've watched the panels populate from the DB in a real workspace, remove all three.**
Until then they're the only thing keeping the panel usable outside the native app.

- **Manual plugin-install path** — `ai-skills/public/manifest.json` + `icon.png`, the `plugin-main.ts`
  "all" bundle (`dist/plugin.js`), and the `mode:"all"` branch in `App.tsx`. Superseded by the native
  docked panels; only kept as a standalone/manual-load fallback. Remove if we commit fully to native.
- **localStorage org/project scope stores** in `plugin.ts` (`SCOPE_DEFAULTS`, `loadStoredScope`,
  `saveStoredScope`, the `org|project` branch of `save-scope-skills`) + `EDITABLE_SCOPES` in
  `Skills.tsx`. Org(=team)/project are now DB-managed via the dashboard and read-only in the panel;
  these only serve the non-native fallback. Removing them means the panel shows nothing until the DB
  push arrives (~0.6–3.6s) when run standalone — acceptable once we drop standalone support.
- **`project` scope generally** — the cascade is App→Team→File; the `project` slot is unused natively.
  `PROJECT_SKILLS` in `skills-core` and the project handling could go if we don't want to keep the
  4-scope model for future use.

## Open TODOs (post-verify)

- Sync DB app/team scopes to the **MCP** path (today MCP reads file scope + bundled builtins only).
- Real **access control** on app-scope CRUD (currently any authenticated user).
- ai-kit `prompts/` as chat quick-starts; evals harness; gradient enforcement.

## Chat history per file (2026-07-16)

Agent conversations are now DB-backed, per profile+file (`profile_agent_chat`,
migration 0158; RPC ns `agent-chats`). Saves fire at turn boundaries (images
stripped, never stored); panel open restores the most recent conversation —
transcript, canonical history and spend meter survive a hard refresh. Header
gained New chat + History (popover: resume / inline rename / delete); the
status-row "Clear" is now the non-destructive "New chat". Titles derive from
the first user message on insert only, so renames are durable. Live-verified
in devenv incl. cross-profile probes (list → [], foreign id → 404). Plan:
`__piweek/.../plans/completed/202607160021-chat-history-per-file/`.
