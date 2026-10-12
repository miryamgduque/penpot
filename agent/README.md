# Agent service

Runs the design agent on [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness).
It is the only place the agent's loop runs: the panel sends a turn, the
service talks to the model, runs the tools and streams back what happened.
Exploration: see the epic "Move the design agent to a backend service built
on DeepSeek Harness".

The service is called only by the Penpot backend. The backend authenticates
the user, checks file access, attaches the user's provider key and forwards
the turn; the service holds no credentials and reads none from the
environment or from disk.

## Run it

```bash
pnpm install
pnpm run start        # http://127.0.0.1:6067
pnpm run test
```

Then give the backend `PENPOT_AGENT_SERVICE_URI=http://localhost:6067`.
Without it the panel says the service is not configured.

| Variable | Default | Meaning |
|---|---|---|
| `PENPOT_AGENT_SERVICE_PORT` | `6067` | Listening port |
| `PENPOT_AGENT_SERVICE_HOST` | `127.0.0.1` | Listening address |
| `PENPOT_AGENT_SERVICE_SHARED_KEY` | unset | When set, callers must send it as `x-penpot-shared-key` |
| `PENPOT_AGENT_SERVICE_DATA_DIR` | `.data` | Stored conversations and images |
| `PENPOT_AGENT_SERVICE_ANTHROPIC_BASE_URL` | unset | Development only: a stand-in for the Anthropic API |
| `PENPOT_AGENT_SERVICE_MCP_ALLOW_PRIVATE` | unset | Development only: `true` lets a user's MCP server be plain http or on a private address |

## Where tools run

With the flag `design-agent-server-tools` (backend and exporter) the tools
run on the server. Each turn carries a turn token from the backend, naming
one profile, one file, one conversation and one turn; it is the only
credential the tools use.

| Tools | Run by | Why there |
|---|---|---|
| reads, audit, comments, versions, page metadata, `save_recipe` | the backend (`run-agent-tool`) | they read the stored file or call what the workspace calls, as the turn's profile; a saved recipe is kept with the file's team |
| everything that changes a page or the file's tokens, components and foundations; `screenshot_page`; `render_board` when no workspace is connected | the exporter (`POST /agent-tool`) | layout and text measurement are the render-wasm engine, which the exporter runs headless; it also keeps the browser pool. Tokens are resolved there with the workspace's library; images and a team's uploaded fonts are handed over by the backend for the turn |
| `build_recipe`, `find_recipes` | the exporter | a recipe is built by running the page tools one after another on the engine; the backend hands over the team's own recipes with the page |
| `explore_design`, `fetch_page` | this service, on a side conversation | a cheap model with its own prompt; the web reader holds no tools |
| `critique_design` | this service, on a side conversation | the turn's own model, shown a render and a brief; it did not build the board and holds no tools |
| skills | the harness's own `skill` tool | the panel sends the enabled skills with the turn |
| `generate_code` | the exporter, in the frontend's render page | the generators render the workspace's own components, so they run where those are: a page of the exporter's browser pool, handed the shapes and nothing else |
| `ask_user`, `switch_page`, `render_board` | the connected workspace | only a workspace can ask the user or move their view; a board is rendered by the engine the user is looking at (by the exporter when no workspace is connected) |

Before a server tool runs, the service asks the workspace to save what it has
not saved and to say what the user is looking at (page, selection, active
rules). After the exporter commits, the workspace is told which revision was
written and catches up before anything else is asked of it.

A server tool can still answer with a fallback, and the workspace then runs
the call. With the exporter set up as above, these are the cases left:

| Fallback | Reason |
|---|---|
| "the render engine is not enabled on this exporter" | the exporter was started without `design-agent-server-tools`, or without the render-wasm build: it has no engine to lay out or measure with |
| "a font this text uses is not available to the server" | a font file could not be downloaded (a Google font the proxy cannot reach, a team font whose file is gone or over 8 MB): the text cannot be measured, and a guessed size would be committed as fact |
| "the tool did not settle on the server" | a guard: a tool asked for tokens or fonts more times than it should ever need to |
| "the code generator could not run on the server" | the exporter's browser could not load the render page or run the generator in it |

The other fallbacks in `common/src/app/common/agent/` are for a host that
does not supply something the exporter always does (the engine, the font
list, the token resolver, an SVG parser): they keep the shared tools usable
elsewhere, such as in tests on the JVM.

Nothing else is sent to the workspace.

Every commit a server tool makes is recorded with its conversation, turn and
tool (`get-agent-changes`). The file's design rules are checked in one place
before any tool runs; a mandatory rule cannot be left out by the caller.

A turn can be started with no workspace at all (`"workspace": false` in its
body). The service then syncs with nobody; `render_board` goes to the
exporter; `switch_page` changes which page the conversation's tools act on,
remembered here per conversation; `ask_user` answers that it needs a
workspace.

Without the flag there is no turn token and every tool runs in the workspace.

`PENPOT_BACKEND_URI` (default `http://localhost:6060`) and
`PENPOT_EXPORTER_URI` (default `http://localhost:6061`) are where the service
reaches them. The exporter needs the same flag to start its engine workers.

## External MCP servers

With the backend flag `design-agent-mcp`, a user can connect MCP servers of
other services in Settings › Integrations. The backend keeps them (a token
encrypted like a provider key) and hands the enabled ones over with each
turn, in the `x-penpot-mcp-servers` header: neither the list nor the tokens
pass through the browser.

The harness's MCP client connects them on that conversation only, over
Streamable HTTP; their tools appear to the model as `mcp__<name>__<tool>` and
are called from this service. Before connecting, each address is checked
again here: https only, and every address the host resolves to must be public
(`src/netguard.js`). Every later request to it is held to the same rule: no
redirect is followed, and the address is checked once more when the socket
connects, so neither a redirect nor a changed DNS answer leads inside. A server that fails the check, or does not answer, is
left out and reported in the turn's `mcp` event; the turn goes on without it.
Side conversations get none of them.

What such a tool returns is somebody else's text reaching a model that can
edit the file. The prompt says so, and tells the model to treat it as data.
That lowers the risk; it does not remove it.

A user can switch single tools of a server off. `POST /v1/mcp/tools` with
`{"server": {"url", "headers"}}` lists what a server offers, under the same
address rules as a turn; an entry's `disabledTools` names the ones the model
is never offered: they are dropped where the harness would register them, so
a call to one is a call to an unknown tool. A call to a server's tool is
reported to the panel as a `server-tool` event, like one of Penpot's own.

`PENPOT_AGENT_SERVICE_MCP_ALLOW_PRIVATE=true` lifts both address rules, for a
server on a developer's own machine. Never in a deployment.

## Recipes: the hit rate

A turn that looked for a recipe (`find_recipes`), built one (`build_recipe`)
or built structure by hand (`build_tree`, `create_shape`, `create_text`)
leaves one line in `recipe-use.jsonl` in the data directory: the turn, the
conversation, the counts and the names of the recipes built. Nothing of what
the user wrote. The same counts are in the turn's `done` event, as `recipes`.

The hit rate is, of the conversations that found a recipe, the share that
built one (`hitRate` in `src/recipes.js`).

## Recipes: the community source

Not part of this service, but of the same feature. The backend reads
`PENPOT_RECIPE_COMMUNITY_INDEX_URI`: the address of a public JSON index of
recipe files, kept and reviewed wherever it is hosted.

```json
{"recipes": [{"name": "news-card", "title": "News card",
              "purpose": "A card for a news item.", "category": "container",
              "author": "Ada", "url": "news-card.recipe.json"}]}
```

A `url` may be relative to the index. Team dashboard › Agent Recipes lists
the index; importing a recipe has the backend fetch its file (public
addresses only, as for any page the agent fetches), check it against the
recipe format and keep it for the team, labelled with the index's host and
the author, disabled until a member enables it. Unset, there is no community
source and the section is not shown.

## Try it without a provider key

`node test/fake-anthropic-cli.js 6068` starts a scripted stand-in model.
Start the service with `PENPOT_AGENT_SERVICE_ANTHROPIC_BASE_URL=http://127.0.0.1:6068`
and connect any placeholder key in Penpot. In the panel, `call <tool> <json>`
makes the "model" call that tool and report what it got back.

## Protocol

`POST /v1/turns` with headers `x-penpot-profile-id`, `x-penpot-provider`,
`x-penpot-api-key` and a JSON body `{sessionId, model, system, text, images,
tools, skills}`. The response is newline-delimited JSON:

`turn` · `text-delta` · `thinking-delta` · `tool-call` · `usage` · `ping` · `done` · `error`

`POST /v1/turns/:turnId/tool-results` with `{callId, content, images, isError}`
answers a `tool-call`. Closing the turn's connection cancels it.
