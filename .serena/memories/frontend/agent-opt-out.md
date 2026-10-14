# Agent opt-out: surfaces and model-call paths

Epic #84. The opt-out is personal and browser-side only. The server reads
none of it. Resolver: `app.main.data.agent-access/agent-enabled?`
(instance flag, then account, then file; file unknown means off).

## Rules for new work

- A new agent surface asks `agent-enabled?` (refs: `refs/agent-enabled?`,
  `refs/make-agent-enabled-ref`). Never read the `:design-agent` flag.
- A new event that can reach a model checks `agent-enabled?` with file
  scope at its start.
- A new request to a provider goes through
  `agent-access/guard-model-call`. It checks again when the request goes
  out, so a chain that began while the agent was on cannot send its next
  request after the switch-off.
- A surface that offers a change to the file also asks
  `agent-access/can-write?` (ref `refs/agent-can-write?`). It is not part
  of `agent-enabled?`: a viewer keeps the agent. Never test a role name.
- Add the row here and a case in `frontend-tests.data.agent-opt-out-test`.
- Tests prove browser gates only. Do not call them a server check.

## Model-call paths

Transports (3, all guarded):
- `agent/stream-round` (`::sse/ai-agent-round-stream`): turns.
- `agent/run-side-turn` (`:ai-agent-round`): `detect-round`, playbook
  match, compaction, summary, session review, the `explore_design` tool.
- `skill-gen/generate-skill` (`:ai-agent-round`).

Entry events (7), each gated at start and tested for every account/file
combination in `agent-opt-out-test`:
- `ai-panel/send-message` (also fix-it-now, playbook match, compaction)
- `ai-panel/continue-turn`
- `ai-panel/summarize-into-new-chat`
- `ai-panel/start-watcher`, then `run-semantic-tick` (observer)
- `session-review/review-session`
- `user-skills/create-from-answers` (skill generation)
- Stop: `agent-access/turned-off` ends the watcher and a running turn.

Not enforced, by design (server, non-goal): RPCs `ai-agent-round`,
`ai-agent-round-stream`, `connect-ai-provider`, `get-ai-provider-models`.
The last two are not called by the frontend. `fetch-web-page` is an agent
tool, only inside a turn.

## UI surfaces

Status: test = behaviour tested; ref = hidden by the resolver ref, the
render itself is not tested; design = stays visible on purpose; gap.

- Workspace header agent button: ref
- Agent panel mount (`ai-panel-open?`): test
- Alt+B shortcut (`toggle-panel` via `set-open`): test
- Inside the panel (arrival notices, recorder controls, slash commands,
  skill creation): follows the panel, test
- Dashboard sidebar: team menu Skills, Agent Skills link: ref
- Team page Skills tab: ref
- `?screen=dashboard-skills` and `dashboard-agent-skills`: test (route
  redirect in `ui/routes_test`)
- Main menu > Preferences item: design
- Recording dot in the header: design
- Settings sidebar Integrations link, Integrations page: design
- Shortcut lists (workspace shortcuts panel, Settings > Shortcuts): gap
- Skills page already open when switched off from another tab: gap

Out of scope: the MCP Server section and `data/workspace/mcp.cljs`. They
link an outside AI client to the file. They are not the design agent.

## Known gaps (recorded, not fixed)

- A turn started in a file keeps running after the user opens another
  file where the agent is on. The guard stops it if that file is off.
- The shortcut lists still show the panel shortcut while the agent is off.
- An open skills page stays on screen when the agent is switched off in
  another tab. The route check runs on navigation only.
- A recording that was running keeps saving after the agent is switched
  off. Only the dot stays.
- `skill_state` RPCs take any file id without a file access check.

## Check again

`rg "ai-agent-round|guard-model-call|agent-enabled\?|instance-enabled\?"`
over `frontend/src` and `backend/src`. Every provider request site must
sit under `guard-model-call`.
