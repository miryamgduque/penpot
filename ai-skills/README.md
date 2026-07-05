# Penpot Skills — prototype

Design intent that lives in the file, not in the AI. A layered, provider-agnostic
skills-and-rules system for Penpot: any agent you connect inherits the file's
design conventions, and the rules that matter are **enforced at the write path**,
not just suggested.

This folder is the Penpot Skills **plugin** (Skills panel + embedded chat agent +
change watcher). The companion changes live in [`../mcp`](../mcp):
`get_design_skills` on the MCP server and the enforcement guard on its
`execute_code` write path.

## What's here

| Piece | Where | What it does |
|---|---|---|
| Skill format | [src/skills/parse.ts](src/skills/parse.ts) | Markdown + flat frontmatter (`name`, `scope`, `enforcement`, `mandatory`, `trigger`) |
| Cascade resolver | [src/skills/resolve.ts](src/skills/resolve.ts) | platform → org → project → file; specific wins, `mandatory` can't be loosened below |
| Scope stubs | [src/skills/builtin.ts](src/skills/builtin.ts) | platform/org/project skills (stubbed, per prototype scope) |
| File skills | [src/skills/seed.ts](src/skills/seed.ts) + the design file | Stored in the file's **shared pluginData** (`penpot-skills`/`skills`) — versioned with the design, readable by any tool |
| Enforcement guard | [src/guard.ts](src/guard.ts) | Validates `fills` against the file's color tokens + library colors; recursive Proxy gates arbitrary `execute_code` |
| Plugin context | [src/plugin.ts](src/plugin.ts) | RPC ops for the chat's tools, change watching (`shapechange`/`selectionchange`) → triggered skills |
| Embedded chat | [src/ui/](src/ui) | React panel: Chat + Skills + Tokens tabs. BYOK Anthropic (key stays in the browser), manual streaming tool loop — no MCP in the loop |
| Scope management | [src/ui/Skills.tsx](src/ui/Skills.tsx) | Every level is manageable: org/project skills live in the plugin's cross-file store (`penpot.localStorage`), file skills in the file, platform curated/read-only |
| Token management | [src/ui/Tokens.tsx](src/ui/Tokens.tsx) | File color-token CRUD + an org-level palette (cross-file store) that syncs into any file's "org" token set |
| Skill notifications | [src/ui/Notifications.tsx](src/ui/Notifications.tsx) | Transient toasts outside the chat: appear while designing, auto-dismiss, "⌖ Show" selects the affected shape on canvas, "Ask agent" hands the skill to the chat |
| MCP tool | [../mcp/packages/server/src/tools/GetDesignSkillsTool.ts](../mcp/packages/server/src/tools/GetDesignSkillsTool.ts) | Lean manifest by default, bodies on demand |
| MCP guard | [../mcp/packages/plugin/src/SkillsGuard.ts](../mcp/packages/plugin/src/SkillsGuard.ts) | Same token-only-colors gate on the official `execute_code` write path |

## Run the demo

```bash
# 1. Penpot itself (localhost:9001; login demo@penpot.local / demopassword123)
docker compose -p penpot -f ../docker/images/docker-compose.yaml up -d

# 2. This plugin (serves manifest at localhost:4500)
npm install && npm start

# 3. The patched MCP server + its plugin (4400/4401/4402) — for external agents
cd ../mcp && pnpm install && pnpm run bootstrap
```

### Integrated side panel (instead of a floating plugin window)

**Native (frontend built from source, e.g. the devenv):** the workspace now has
a plugin dock column (`frontend/src/app/main/ui/workspace.cljs` +
`workspace.scss`), and the plugins runtime supports a `dock: true` option on
`penpot.ui.open` (`plugins/libs/plugins-runtime`). This plugin requests it, so
on a source build it renders docked full-height on the right and the canvas
reflows around it. Hosts without the dock fall back to the floating window.

On source builds the panel is also **fully native**: a header button (next to
comments) toggles it with no plugin installation — the frontend starts it from
a bundled manifest like the integrated MCP plugin
(`frontend/src/app/main/data/workspace/skills.cljs`; panel host overridable via
the `penpotSkillsPluginHost` global, default `http://localhost:4500/`). The
plugin manager route below is only needed on prebuilt images.

**Prebuilt docker images:** the same effect via the injected dock layer
(idempotent; rerun after the container is recreated):

```bash
./scripts/install-dock.sh   # patches penpot-penpot-frontend-1
```

In Penpot, open a file → Plugins manager (⌘⌥P) → install
`http://localhost:4500/manifest.json` (and `http://localhost:4400/manifest.json`
for the MCP bridge). Opening the Skills plugin seeds the file with two skills:
`token-only-colors` (**enforced**) and `layer-naming` (**triggered**).

### The demo script

1. **Skills panel** — see the effective set with scope + enforcement badges;
   file skills are editable and saved into the file itself.
2. **Embedded chat** — add your Anthropic API key in ⚙ (bring your own provider),
   then: *"Style the selected frame using our design system."* The agent inherits
   the skills as context; if it tries a raw hex fill, the write path rejects it
   citing the rule and it self-corrects via `get_color_tokens`.
3. **Watch changes** — rename a layer to "Rectangle 5" or hand-paint a raw fill:
   a triggered-skill chip appears in the chat with *Ask agent to apply*.
4. **Any agent, same rules** — connect any MCP client to
   `http://localhost:4401/mcp`: `get_design_skills` returns the same cascade, and
   `execute_code` writes are gated by the same guard:

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
`node scripts/import-aikit.mjs <path-to-kit-checkout>` — output is committed).

The chat agent consumes them through a **router meta-prompt**: the system
prompt carries only the platform index (name + when-to-use) plus the distilled
operating-modes governance, and instructs the agent to fetch full bodies on
demand via `get_design_skills({name})` — `penpot-plugin-api-gotchas` before the
first mutating `execute_code`. External agents get the identical set through
the MCP `get_design_skills` tool. Org/project/file skills stay small and are
inlined in full.

## Notes / prototype simplifications

- Org/project skills are cross-file stores seeded from stubs; only file scope
  lives in the design file itself.
- The parser/resolver is intentionally duplicated in
  `../mcp/packages/server/src/skills/SkillsCascade.ts` (separate workspaces) —
  keep them in sync.
- The MCP-side guard checks the file's own frontmatter for `enforcement: enforced`
  (no cross-scope mandatory raise on that path yet).
- Enforcement covers solid fills; gradients/strokes are next.
