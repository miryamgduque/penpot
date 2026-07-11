# Penpot Skills — prototype

Design intent that lives in the file, not in the AI. A layered, provider-agnostic
skills-and-rules system for Penpot: any agent you connect inherits the file's
design conventions, and the rules that matter are **enforced at the write path**,
not just suggested.

This folder is the Penpot Skills **panel** (Skills + Tokens + embedded chat
agent + change watcher). It ships as a **native part of Penpot**: on a source
build, a workspace header button opens it as a docked side panel — no plugin
installation, and **no MCP anywhere in the loop** (the chat talks to your
provider directly; tools execute through Penpot's plugin runtime, which is the
sandbox/bridge, an implementation detail).

MCP is the **optional second door** for external agents (Claude Code, Cursor,
Claude Desktop, local models): [`../mcp`](../mcp) adds `get_design_skills` and
the same enforcement guard on its `execute_code` write path. Same file, same
cascade, same rules — you only run it when you want outside agents connected.

## What's here

| Piece | Where | What it does |
|---|---|---|
| Shared core | [../skills-core](../skills-core) | One package for the skill format parser, cascade resolver, scope stubs and the enforcement guard — consumed by this plugin **and** both MCP packages (npm `file:` / pnpm `link:`), so the implementations cannot drift |
| Skill format | [../skills-core/src/parse.ts](../skills-core/src/parse.ts) | Markdown + flat frontmatter (`name`, `scope`, `enforcement`, `mandatory`, `trigger`) |
| Cascade resolver | [../skills-core/src/resolve.ts](../skills-core/src/resolve.ts) | platform → org → project → file; specific wins, `mandatory` can't be loosened below |
| Scope stubs | [../skills-core/src/builtin.ts](../skills-core/src/builtin.ts) | platform/org/project skills (stubbed, per prototype scope) |
| File skills | [src/skills/seed.ts](src/skills/seed.ts) + the design file | Stored in the file's **shared pluginData** (`penpot-skills`/`skills`) — versioned with the design, readable by any tool |
| Enforcement guard | [../skills-core/src/guard.ts](../skills-core/src/guard.ts) | Validates `fills` against the file's color tokens + library colors; recursive Proxy gates arbitrary `execute_code`. Pure logic — callers bind it to their `penpot` global |
| Plugin context | [src/plugin.ts](src/plugin.ts) | RPC ops for the chat's tools, change watching (`shapechange`/`selectionchange`) → triggered skills |
| Embedded chat | [src/ui/](src/ui) | React panel: Chat + Skills + Tokens tabs. BYOK Anthropic (key stays in the browser), manual streaming tool loop — no MCP in the loop |
| Scope management | [src/ui/Skills.tsx](src/ui/Skills.tsx) | Every level is manageable: org/project skills live in the plugin's cross-file store (`penpot.localStorage`), file skills in the file, platform curated/read-only |
| Token management | [src/ui/Tokens.tsx](src/ui/Tokens.tsx) | File color-token CRUD + an org-level palette (cross-file store) that syncs into any file's "org" token set |
| Skill notifications | [src/ui/Notifications.tsx](src/ui/Notifications.tsx) | Transient toasts outside the chat: appear while designing, auto-dismiss, "⌖ Show" selects the affected shape on canvas, "Ask agent" hands the skill to the chat |
| MCP tool | [../mcp/packages/server/src/tools/GetDesignSkillsTool.ts](../mcp/packages/server/src/tools/GetDesignSkillsTool.ts) | Lean manifest by default, bodies on demand |
| MCP guard | [../mcp/packages/plugin/src/SkillsGuard.ts](../mcp/packages/plugin/src/SkillsGuard.ts) | Same token-only-colors gate on the official `execute_code` write path |

## Run it (native, recommended)

The native integration lives in the Penpot frontend source (dock column,
header button, bundled manifest), so it needs Penpot **built from source** —
the devenv does everything:

```bash
# 1. Penpot devenv — builds and serves the frontend with the native panel
#    (manage.sh needs bash ≥ 4: `brew install bash` on macOS)
/opt/homebrew/bin/bash ../manage.sh create-devenv   # first time only
/opt/homebrew/bin/bash ../manage.sh run-devenv

# 2. The panel assets (the frontend loads them from localhost:4500;
#    override with the `penpotSkillsPluginHost` global)
npm install && npm start
```

Open `https://localhost:3449` (or `http://localhost:3450`), register/log in,
open a file, and click the **puzzle button in the workspace header** (next to
comments). The Skills panel docks on the right, the canvas reflows, and the
first open seeds the file with two skills: `token-only-colors` (**enforced**)
and `layer-naming` (**triggered**). Add your Anthropic API key in the panel's
⚙ tab and chat — nothing else is required; **MCP is not part of this path**.

How the native pieces fit: the workspace has a plugin dock column
(`frontend/src/app/main/ui/workspace.{cljs,scss}`), the plugins runtime accepts
`dock: true` on `penpot.ui.open` (`plugins/libs/plugins-runtime`), and the
header button starts the panel from a bundled manifest — same pattern as the
integrated MCP plugin (`frontend/src/app/main/data/workspace/skills.cljs`).
The plugin runtime is still the sandbox/API bridge underneath; it's just no
longer something the user sees.

> Devenv notes: if the container is memory-starved (Docker VM < ~10 GB with
> other stacks running), the backend or shadow-cljs watch can get OOM-killed —
> stop other stacks or raise Docker Desktop's memory. Registration asks for
> email verification; grab the link from mailcatcher at `http://localhost:1080`.

### Optional: MCP for external agents

Only needed to connect outside agents (Claude Code, Cursor, Claude Desktop,
local models) to the same skills and enforcement:

```bash
cd ../mcp && ./scripts/setup && pnpm run bootstrap   # 4400/4401/4402
```

Then in Penpot: Plugins manager (⌘⌥P) → install
`http://localhost:4400/manifest.json`, open it and connect. Point any MCP
client at `http://localhost:4401/mcp`.

### Fallback: prebuilt docker images (no source build)

On stock images (`docker compose -p penpot -f ../docker/images/docker-compose.yaml up -d`,
`http://localhost:9001`) the native button doesn't exist, so install the panel
as a regular plugin: Plugins manager (⌘⌥P) → `http://localhost:4500/manifest.json`.
It opens as a floating window; for the docked look, inject the dock layer
(idempotent; rerun after the container is recreated):

```bash
./scripts/install-dock.sh   # patches penpot-penpot-frontend-1
```

### The demo script

1. **Skills tab** — the effective set with scope + enforcement badges; every
   level is manageable (org/project in the cross-file store, file skills saved
   into the design file itself; platform is the curated ai-kit set).
2. **Chat** — *"Style the selected frame using our design system"* or *"create
   a cooking app home page"*. The agent routes through the ai-kit skills,
   builds on canvas with token-bound fills, and if it ever tries a raw hex the
   write path rejects it citing the rule and it self-corrects via
   `get_color_tokens`. The session meter above the composer shows calls,
   tokens (with cached share) and estimated cost.
3. **Watch changes** — rename a layer to "Rectangle 5" or hand-paint a raw
   fill: a transient notification appears with *⌖ Show* (selects the shape)
   and *Ask agent to apply*.
4. **Tokens tab** — file color-token CRUD plus the org palette that syncs into
   any file.
5. **(Optional) Any agent, same rules** — with the MCP stack running, connect
   any MCP client to `http://localhost:4401/mcp`: `get_design_skills` returns
   the same cascade, and `execute_code` writes are gated by the same guard:

```
> execute_code: shape.fills = [{ fillColor: "#FF0000" }]
Rejected by enforced design skill "token-only-colors" (defined in this Penpot
file): fill color #FF0000 is not one of the file's color tokens or library
colors. Allowed colors: color.brand.primary (#6366f1), color.accent (#f59e0b) …
```

The check runs in Penpot's plugin runtime — not in the model's head — so a local
model is gated identically to a frontier one. The guarantee is structural.

## The platform scope: official penpot-ai-kit

The platform scope carries the full official
[penpot-ai-kit](https://github.com/penpot/penpot-ai-kit): all 11 skills
(foundations, component-factory, build-screen, audits, migrate, …) plus the
load-bearing shared docs (`plugin-api-gotchas`, `naming-conventions`,
`operating-modes`, `mcp-tool-reference`), converted to this skill format by
`scripts/import-aikit.mjs` (regenerate with
`node scripts/import-aikit.mjs <path-to-kit-checkout>` — the output,
`../skills-core/src/aikit.gen.ts`, is committed and shared by the panel and
the MCP server alike).

The chat agent consumes them through a **router meta-prompt**: the system
prompt carries only the platform index (name + when-to-use) plus the distilled
operating-modes governance, and instructs the agent to fetch full bodies on
demand via `get_design_skills({name})` — `penpot-plugin-api-gotchas` before the
first mutating `execute_code`. External agents get the identical set through
the MCP `get_design_skills` tool. Org/project/file skills stay small and are
inlined in full.

## Notes / prototype simplifications

- Org/project skills are cross-file stores seeded from stubs; only file scope
  lives in the design file itself. The MCP server serves the stubs as-is, so
  panel edits to org/project scopes are not visible on the MCP path.
- Parser, resolver, stubs and guard live once in
  [`../skills-core`](../skills-core) and are bundled from source into all
  three consumers (`npm install` here / `pnpm install` in `../mcp` wires the
  links; `cd ../skills-core && npm install && npm test` runs its tests).
- The MCP-side guard checks the file's own skills for `enforcement: enforced`
  (no cross-scope mandatory raise on that path yet).
- Enforcement covers solid fills; gradients/strokes are next.
