// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

// The agent service: runs the design agent's loop on DeepSeek Harness.
//
// It is called only by the Penpot backend, never by a browser. The backend
// authenticates the user, checks file access, attaches the user's provider
// key and forwards the turn; this service keeps no credentials of its own.
//
//   POST /v1/turns                     → newline-delimited JSON events
//   POST /v1/turns/:id/tool-results    → answers a `tool-call` event
//   GET  /health

import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { PROVIDER_ROUTES, createHarness } from "./harness.js";
import { listMcpTools, turnMcpServers } from "./mcp.js";
import { catalog } from "./catalog.js";
import { recipeUse, recordRecipeUse } from "./recipes.js";
import { runScoutTool, scoutTools } from "./scout.js";

const MAX_BODY_BYTES = 6 * 1024 * 1024;
const HEARTBEAT_MS = 2000;

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function readJson(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new HttpError(413, "request body too large");
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new HttpError(400, "request body is not valid JSON");
  }
}

function sendJson(res, status, body) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}

function requireString(value, name) {
  if (typeof value !== "string" || value.length === 0) throw new HttpError(400, `missing ${name}`);
  return value;
}

const SYNC_CALL = "__sync";

const WORKSPACE_UNDO_TOOLS = new Set(["undo_change", "redo_change"]);

// With the tools on the server, a connected workspace says what the user is
// looking at and does what only a workspace can: ask the user, move their
// view, and render with the engine they are looking at. Nothing else is sent
// to it, except a call the server answered it cannot handle yet.
const WORKSPACE_TOOLS = new Set(["ask_user", "switch_page", "render_board"]);

/**
 * Runs a tool on the Penpot backend for the turn the token names. The token
 * was issued by the backend for one profile on one file; this service only
 * carries it back.
 */
async function runBackendTool(backendUri, token, call, context, signal) {
  const response = await fetch(`${backendUri}/api/rpc/command/run-agent-tool`, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({
      token,
      tool: call.name,
      input: JSON.stringify(call.input ?? {}),
      context: context ?? "{}",
    }),
    signal,
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    return { content: body.hint ?? `the server could not run ${call.name} (status ${response.status})`, isError: true };
  }
  return { content: String(body.content ?? ""), isError: body.isError === true };
}

/** Transit-JSON for a flat map of string values, which is all we send. */
function transitMap(entries) {
  const out = ["^ "];
  for (const [key, value] of Object.entries(entries)) {
    // a leading ~ or ^ is transit's escape; none of our values start with one
    out.push(`~:${key}`, /^[~^`]/.test(value) ? `~${value}` : value);
  }
  return JSON.stringify(out);
}

/**
 * Runs a page-changing tool in the exporter, which holds the render engine.
 * Answers `{fallback}` when the exporter does not handle this call yet.
 */
async function runEngineTool(exporterUri, token, call, context, signal) {
  const response = await fetch(`${exporterUri}/agent-tool`, {
    method: "POST",
    headers: { "content-type": "application/transit+json" },
    body: transitMap({
      token,
      tool: call.name,
      input: JSON.stringify(call.input ?? {}),
      context: context ?? "{}",
    }),
    signal,
  });
  const text = await response.text();
  let body = {};
  try {
    body = JSON.parse(text);
  } catch {
    // an error from the exporter arrives as transit, not as our JSON
  }
  if (!response.ok) {
    const hint = /"~:hint","((?:[^"\\]|\\.)*)"/.exec(text)?.[1];
    return { content: hint ?? `the engine could not run ${call.name} (status ${response.status})`, isError: true };
  }
  if (typeof body.fallback === "string") return { fallback: body.fallback };
  return {
    content: String(body.content ?? ""),
    isError: body.isError === true,
    // the design rule that refused the call, when one did
    rule: typeof body.rule === "string" ? body.rule : undefined,
    revn: body.revn,
    // what the tool shows the model, beside what it says
    images: Array.isArray(body.images) ? body.images : undefined,
  };
}

/**
 * The helper model a turn was sent with, or undefined: all three of its
 * headers, and a provider this service knows.
 */
function helperOf(headers) {
  const provider = headers["x-penpot-helper-provider"];
  const model = headers["x-penpot-helper-model"];
  const apiKey = headers["x-penpot-helper-api-key"];
  const present = [provider, model, apiKey].every((value) => typeof value === "string" && value !== "");
  return present && PROVIDER_ROUTES[provider] !== undefined ? { provider, model, apiKey } : undefined;
}

export async function createAgentServer(options = {}) {
  const harness = options.harness ?? (await createHarness(options));
  const sharedKey = options.sharedKey;
  const backendUri = (options.backendUri ?? "http://localhost:6060").replace(/\/$/, "");
  const exporterUri = (options.exporterUri ?? "http://localhost:6061").replace(/\/$/, "");

  /** turnId → { profileId, pending: Map<callId, {resolve}> } */
  const turns = new Map();

  /**
   * conversation → the page its tools act on, for conversations run with no
   * workspace. With one, the page is the one the user is looking at. Kept in
   * memory: after a restart such a conversation is back on the file's first
   * page until it switches again.
   */
  const pages = new Map();

  async function handleTurn(req, res) {
    const profileId = requireString(req.headers["x-penpot-profile-id"], "profile id");
    const provider = requireString(req.headers["x-penpot-provider"], "provider");
    const apiKey = requireString(req.headers["x-penpot-api-key"], "api key");
    const body = await readJson(req);
    const sessionId = requireString(body.sessionId, "sessionId");
    const model = requireString(body.model, "model");
    const system = requireString(body.system, "system");
    const text = requireString(body.text, "text");
    const declared = Array.isArray(body.tools) ? body.tools : [];
    const skills = Array.isArray(body.skills) ? body.skills : [];
    const turnToken = req.headers["x-penpot-turn-token"];
    const namedIn = (header) =>
      typeof turnToken === "string" ? String(req.headers[header] ?? "").split(",").filter(Boolean) : [];
    // tools the backend runs, and tools the exporter runs on the render engine
    const backendTools = new Set(namedIn("x-penpot-server-tools"));
    const engineTools = new Set(namedIn("x-penpot-engine-tools"));
    // A turn started with no workspace: nothing to sync with, nobody to run a
    // tool in a browser, and a board is rendered by the exporter.
    const tabless = body.workspace === false && engineTools.size > 0;
    if (tabless) engineTools.add("render_board");
    // tools this service runs itself, on a side conversation
    const ownTools = scoutTools(backendTools);
    const serverTools = new Set([...backendTools, ...engineTools, ...ownTools]);
    // An edit made on the server reaches the workspace as another
    // collaborator's would: it is not in the user's undo stack. Undoing there
    // would take back the user's own last edit, so those tools are not offered.
    const tools =
      engineTools.size > 0 ? declared.filter((tool) => !WORKSPACE_UNDO_TOOLS.has(tool?.name)) : declared;
    const images = Array.isArray(body.images) ? body.images : [];
    // the model for side jobs: the user's helper, with its own provider and
    // key, or the turn's own model when they chose none
    const helper = helperOf(req.headers) ?? { provider, model, apiKey };
    // the MCP servers the user connected, as the backend handed them over
    const mcp = await turnMcpServers(req.headers["x-penpot-mcp-servers"], {
      allowPrivate: options.mcpAllowPrivate === true,
    });

    const turnId = randomUUID();
    const turn = { profileId, pending: new Map() };
    turns.set(turnId, turn);

    const controller = new AbortController();
    res.on("close", () => {
      if (!res.writableEnded) controller.abort();
    });

    res.writeHead(200, {
      "content-type": "application/x-ndjson",
      "cache-control": "no-cache",
      "x-accel-buffering": "no",
    });
    const emit = (event) => {
      if (!res.writableEnded && !res.destroyed) res.write(`${JSON.stringify(event)}\n`);
    };
    emit({ type: "turn", turnId });
    // said once, so the panel can show which connected services are in play
    if (mcp.servers.length > 0 || mcp.refused.length > 0) {
      emit({ type: "mcp", servers: mcp.servers.map((server) => server.name), refused: mcp.refused });
    }
    // A quiet stream hides a client that has gone: the backend relaying this
    // only notices on a write. The heartbeat is what lets a cancel during a
    // long model pause or a slow tool reach us.
    const heartbeat = setInterval(() => emit({ type: "ping" }), HEARTBEAT_MS);

    // how long each tool call spent away from the service: the bridge round
    // trip plus the tool's own run time in the browser
    const toolTimings = [];
    // what the turn did with recipes, for the hit rate
    const recipes = recipeUse();
    const callTool = (call, signal) =>
      new Promise((resolve, reject) => {
        const startedAt = performance.now();
        const abort = () => {
          turn.pending.delete(call.callId);
          reject(new Error("cancelled before the tool finished"));
        };
        if (signal?.aborted) return abort();
        signal?.addEventListener("abort", abort, { once: true });
        turn.pending.set(call.callId, {
          resolve: (result) => {
            signal?.removeEventListener("abort", abort);
            toolTimings.push({ name: call.name, ms: Math.round(performance.now() - startedAt) });
            resolve(result);
          },
        });
        emit({ type: "tool-call", ...call });
      });

    // A server-side tool works on the stored file. The connected workspace is
    // first asked to save what it has not saved yet — or the tool would miss
    // the agent's own last change — and to say what the user is looking at.
    // It is also told the revision the server last wrote, so it answers only
    // once it has received that change.
    let serverRevn;
    const pageKey = `${profileId}:${sessionId}`;
    // what a workspace would have said: the caller may say it instead, and
    // the page is the one the conversation last switched to
    const tablessContext = () => {
      let context = {};
      try {
        const parsed = JSON.parse(typeof body.context === "string" ? body.context : "{}");
        if (parsed !== null && typeof parsed === "object") context = parsed;
      } catch {
        // not JSON: no context
      }
      const pageId = pages.get(pageKey);
      return JSON.stringify(pageId === undefined ? context : { ...context, pageId });
    };
    const syncWorkspace = (signal) => {
      if (tabless) return Promise.resolve({ content: tablessContext(), isError: false });
      const input = serverRevn === undefined ? {} : { minRevn: serverRevn };
      return callTool({ callId: randomUUID(), name: SYNC_CALL, input }, signal).finally(() => toolTimings.pop());
    };

    // With no workspace there is no view to move: switching page is this
    // service remembering which page the conversation's tools act on.
    const switchTablessPage = async (call, signal) => {
      const checked = await runBackendTool(backendUri, turnToken, { name: "check_page", input: call.input }, "{}", signal);
      let result = checked;
      if (!checked.isError) {
        const page = JSON.parse(checked.content);
        pages.set(pageKey, page.pageId);
        result = {
          content: JSON.stringify({
            pageId: page.pageId,
            note: `switched — the tools now act on the page "${page.name}". No workspace is connected, so nobody's view moved.`,
          }),
          isError: false,
        };
      }
      emit({ type: "server-tool", name: call.name, input: call.input, content: result.content, isError: result.isError });
      return result;
    };

    // The other direction: a tool in the workspace must not run before the
    // workspace has caught up with what the server wrote.
    const callWorkspaceTool = async (call, signal, { fallback = false } = {}) => {
      if (tabless && call.name === "switch_page") return switchTablessPage(call, signal);
      if (tabless) {
        return { content: `${call.name} needs a connected workspace, and this turn has none`, isError: true };
      }
      if (engineTools.size > 0 && !fallback && !WORKSPACE_TOOLS.has(call.name)) {
        return { content: `${call.name} is not a tool this agent has`, isError: true };
      }
      if (serverRevn !== undefined) {
        const synced = await syncWorkspace(signal);
        if (synced.isError) return synced;
        serverRevn = undefined;
      }
      return callTool(call, signal);
    };

    // Runs a tool on the server without telling the panel: → its result, or
    // `{fallback}` when the server does not handle this call.
    const runServerTool = async (call, signal) => {
      const synced = await syncWorkspace(signal);
      if (synced.isError) return { content: synced.content, isError: true };
      if (engineTools.has(call.name)) {
        const result = await runEngineTool(exporterUri, turnToken, call, synced.content, signal);
        if (result.revn !== undefined) serverRevn = result.revn;
        return result;
      }
      return runBackendTool(backendUri, turnToken, call, synced.content, signal);
    };

    // A side conversation reads the file with the server's tools. Its tool
    // calls are its own business: the panel shows the one call that asked.
    const side = ({ helper: asHelper, ...request }) =>
      harness.runSideTurn(
        { profileId, ...(asHelper ? helper : { provider, apiKey }), ...request },
        {
          serverTools,
          signal: controller.signal,
          onUsage: (usage) => emit({ type: "usage", usage }),
          callServerTool: async (call, signal) => {
            if (ownTools.has(call.name)) return { content: "a scout cannot call a scout", isError: true };
            const result = await runServerTool(call, signal);
            return result.fallback !== undefined
              ? { content: `${call.name} is not available here`, isError: true }
              : result;
          },
        },
      );

    const callServerTool = async (call, signal) => {
      const startedAt = performance.now();
      const result = ownTools.has(call.name)
        ? await runScoutTool(call, {
            provider,
            model,
            declared,
            side,
            // whether the model of this turn reads images: the critic runs on it
            sees: harness.capabilities === undefined || harness.capabilities(provider, model).vision === true,
            fetchPage: (url) =>
              runBackendTool(backendUri, turnToken, { name: "fetch_web_page", input: { url } }, "{}", signal),
            // by the exporter when no workspace is connected, else by the
            // workspace, with the engine the user is looking at
            render: (names) => {
              const draw = { callId: randomUUID(), name: "render_board", input: { names } };
              return tabless ? runServerTool(draw, signal) : callWorkspaceTool(draw, signal);
            },
          })
        : await runServerTool(call, signal);
      // not handled on the server yet: the workspace runs it, as before
      if (result.fallback !== undefined) return callWorkspaceTool(call, signal, { fallback: true });
      toolTimings.push({ name: call.name, ms: Math.round(performance.now() - startedAt), server: true });
      recipes.note(call, result);
      emit({
        type: "server-tool",
        name: call.name,
        input: call.input,
        content: result.content,
        isError: result.isError,
        rule: result.rule,
      });
      return result;
    };

    try {
      const result = await harness.runTurn(
        { sessionId, profileId, provider, apiKey, model, system, text, images, tools, skills, mcpServers: mcp.servers },
        { emit, callTool: callWorkspaceTool, callServerTool, serverTools, signal: controller.signal },
      );
      const used = recipes.summary();
      if (used !== undefined) {
        await recordRecipeUse(options.recipeLog, { at: new Date().toISOString(), turnId, sessionId, ...used });
      }
      emit({ type: "done", reason: result.reason, resumed: result.resumed, toolTimings, recipes: used });
    } catch (cause) {
      emit({ type: "error", message: cause?.message ?? "the turn failed" });
    } finally {
      clearInterval(heartbeat);
      turns.delete(turnId);
      res.end();
    }
  }

  // The tools one MCP server offers, for the settings page: the user sees
  // them there and can switch each one off.
  async function handleMcpTools(req, res) {
    requireString(req.headers["x-penpot-profile-id"], "profile id");
    const body = await readJson(req);
    try {
      const tools = await listMcpTools(body.server, { allowPrivate: options.mcpAllowPrivate === true });
      sendJson(res, 200, { tools });
    } catch (cause) {
      sendJson(res, 200, { error: cause.message });
    }
  }

  async function handleToolResult(req, res, turnId) {
    const profileId = requireString(req.headers["x-penpot-profile-id"], "profile id");
    const body = await readJson(req);
    const turn = turns.get(turnId);
    // a turn that belongs to someone else is indistinguishable from none
    if (turn === undefined || turn.profileId !== profileId) throw new HttpError(404, "turn not found");
    const pending = turn.pending.get(requireString(body.callId, "callId"));
    if (pending === undefined) throw new HttpError(404, "tool call not found");
    turn.pending.delete(body.callId);
    const content = typeof body.content === "string" ? body.content : JSON.stringify(body.content ?? "");
    const images = Array.isArray(body.images) ? body.images : [];
    pending.resolve({ content, images, isError: body.isError === true });
    sendJson(res, 200, { ok: true });
  }

  /**
   * One round with no tools on a model the caller names: the side jobs of the
   * panel — matching a playbook, summing up a conversation, the watcher's
   * verdicts. Answers `{text, usage}` when it is over.
   */
  async function handleSide(req, res) {
    const profileId = requireString(req.headers["x-penpot-profile-id"], "profile id");
    const provider = requireString(req.headers["x-penpot-provider"], "provider");
    const apiKey = requireString(req.headers["x-penpot-api-key"], "api key");
    const body = await readJson(req);
    const model = requireString(body.model, "model");
    const system = requireString(body.system, "system");
    const text = requireString(body.text, "text");
    if (PROVIDER_ROUTES[provider] === undefined) throw new HttpError(400, `unknown provider: ${provider}`);
    const controller = new AbortController();
    res.on("close", () => {
      if (!res.writableEnded) controller.abort();
    });
    const usage = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, requests: 0 };
    let answer;
    try {
      answer = await harness.runSideTurn(
        { profileId, provider, apiKey, model, system, text, tools: [] },
        {
          serverTools: new Set(),
          signal: controller.signal,
          callServerTool: async () => ({ content: "no tools here", isError: true }),
          onUsage: (step) => {
            for (const key of ["input", "output", "cacheRead", "cacheWrite"]) usage[key] += step[key] ?? 0;
            usage.requests += 1;
          },
        },
      );
    } catch (cause) {
      // the provider's own words are what the caller can act on
      throw new HttpError(cause?.status === 400 ? 400 : 502, cause?.message ?? "the model request failed");
    }
    sendJson(res, 200, { text: answer, usage });
  }

  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, "http://localhost");
      if (req.method === "GET" && url.pathname === "/health") {
        return sendJson(res, 200, { ok: true, conversations: harness.conversations.size });
      }
      if (sharedKey && req.headers["x-penpot-shared-key"] !== sharedKey) {
        throw new HttpError(401, "unauthorized");
      }
      // what each model of each provider can do, as the provider layer says
      if (req.method === "GET" && url.pathname === "/v1/models") return sendJson(res, 200, catalog(PROVIDER_ROUTES));
      if (req.method === "POST" && url.pathname === "/v1/turns") return await handleTurn(req, res);
      if (req.method === "POST" && url.pathname === "/v1/side") return await handleSide(req, res);
      if (req.method === "POST" && url.pathname === "/v1/mcp/tools") return await handleMcpTools(req, res);
      const match = /^\/v1\/turns\/([A-Za-z0-9_-]+)\/tool-results$/.exec(url.pathname);
      if (req.method === "POST" && match) return await handleToolResult(req, res, match[1]);
      throw new HttpError(404, "not found");
    } catch (cause) {
      if (res.headersSent) return res.end();
      sendJson(res, cause instanceof HttpError ? cause.status : 500, {
        error: cause instanceof HttpError ? cause.message : "internal error",
      });
      if (!(cause instanceof HttpError)) console.error(cause);
    }
  });

  return {
    server,
    harness,
    listen: (port, host) => new Promise((resolve) => server.listen(port, host, () => resolve(server.address()))),
    async close() {
      await new Promise((resolve) => {
        server.close(resolve);
        server.closeAllConnections();
      });
      await harness.dispose();
    },
  };
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.PENPOT_AGENT_SERVICE_PORT ?? 6067);
  const host = process.env.PENPOT_AGENT_SERVICE_HOST ?? "127.0.0.1";
  // Conversations and images are kept here so they survive a restart.
  const dataDir = process.env.PENPOT_AGENT_SERVICE_DATA_DIR ?? fileURLToPath(new URL("../.data", import.meta.url));
  // Development only: point the Anthropic route at a stand-in server.
  const anthropicBaseURL = process.env.PENPOT_AGENT_SERVICE_ANTHROPIC_BASE_URL;
  const app = await createAgentServer({
    sharedKey: process.env.PENPOT_AGENT_SERVICE_SHARED_KEY,
    // development only: lets a user's MCP server be plain http or on a private address
    mcpAllowPrivate: process.env.PENPOT_AGENT_SERVICE_MCP_ALLOW_PRIVATE === "true",
    backendUri: process.env.PENPOT_BACKEND_URI,
    exporterUri: process.env.PENPOT_EXPORTER_URI,
    sessionsRoot: process.env.PENPOT_AGENT_SERVICE_SESSIONS_DIR ?? join(dataDir, "sessions"),
    attachmentsHome: process.env.PENPOT_AGENT_SERVICE_ATTACHMENTS_DIR ?? join(dataDir, "attachments"),
    recipeLog: join(dataDir, "recipe-use.jsonl"),
    providers: anthropicBaseURL ? { anthropic: { baseURL: anthropicBaseURL } } : undefined,
  });
  await app.listen(port, host);
  console.log(`agent service listening on http://${host}:${port}`);
  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.on(signal, async () => {
      await app.close();
      process.exit(0);
    });
  }
}
