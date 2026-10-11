# Agent service

Runs the design agent's loop on [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
instead of in the browser. Exploration: see the epic "Move the design agent
to a backend service built on DeepSeek Harness".

The service is called only by the Penpot backend. The backend authenticates
the user, checks file access, attaches the user's provider key and forwards
the turn; the service holds no credentials and reads none from the
environment or from disk. Tools still execute in the browser: the service
emits a `tool-call` event and waits for the result.

## Run it

```bash
pnpm install
pnpm run start        # http://127.0.0.1:6067
pnpm run test
```

Then give the backend `PENPOT_AGENT_SERVICE_URI=http://localhost:6067` and
enable the `design-agent-service` flag (with `design-agent`).

| Variable | Default | Meaning |
|---|---|---|
| `PENPOT_AGENT_SERVICE_PORT` | `6067` | Listening port |
| `PENPOT_AGENT_SERVICE_HOST` | `127.0.0.1` | Listening address |
| `PENPOT_AGENT_SERVICE_SHARED_KEY` | unset | When set, callers must send it as `x-penpot-shared-key` |
| `PENPOT_AGENT_SERVICE_DATA_DIR` | `.data` | Stored conversations and images |
| `PENPOT_AGENT_SERVICE_ANTHROPIC_BASE_URL` | unset | Development only: a stand-in for the Anthropic API |

## Server-side tools

With the backend flag `design-agent-server-tools`, tools stop depending on a
browser one family at a time. Each turn carries a turn token from the backend,
naming one profile and one file; it is the only credential the tools use.

| Tools | Run by | Why there |
|---|---|---|
| `read_design`, `find_shapes` | the backend (`run-agent-tool`) | pure reads of the stored file |
| `create_shape`, `modify_shape` | the exporter (`POST /agent-tool`) | changing a page needs layout, and layout is the render-wasm engine, which the exporter runs headless |
| everything else | the connected workspace | not moved yet |

A server-side tool works on the stored file, so before each one the service
asks the workspace to save its pending changes and to report the page in view
and the selection. After the exporter commits a change, the workspace is told
which revision was written and catches up before it runs its next tool.

When the exporter answers that it does not handle a call yet (a grid layout,
resizing text, a rotation), the workspace runs it, as before.

`PENPOT_BACKEND_URI` (default `http://localhost:6060`) and
`PENPOT_EXPORTER_URI` (default `http://localhost:6061`) are where the service
reaches them. The exporter needs the same flag to start its engine workers.

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
