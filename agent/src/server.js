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

import { createHarness } from "./harness.js";

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

export async function createAgentServer(options = {}) {
  const harness = options.harness ?? (await createHarness(options));
  const sharedKey = options.sharedKey;
  const backendUri = (options.backendUri ?? "http://localhost:6060").replace(/\/$/, "");

  /** turnId → { profileId, pending: Map<callId, {resolve}> } */
  const turns = new Map();

  async function handleTurn(req, res) {
    const profileId = requireString(req.headers["x-penpot-profile-id"], "profile id");
    const provider = requireString(req.headers["x-penpot-provider"], "provider");
    const apiKey = requireString(req.headers["x-penpot-api-key"], "api key");
    const body = await readJson(req);
    const sessionId = requireString(body.sessionId, "sessionId");
    const model = requireString(body.model, "model");
    const system = requireString(body.system, "system");
    const text = requireString(body.text, "text");
    const tools = Array.isArray(body.tools) ? body.tools : [];
    const skills = Array.isArray(body.skills) ? body.skills : [];
    const turnToken = req.headers["x-penpot-turn-token"];
    const serverTools = new Set(
      typeof turnToken === "string" ? String(req.headers["x-penpot-server-tools"] ?? "").split(",").filter(Boolean) : [],
    );
    const images = Array.isArray(body.images) ? body.images : [];

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
    // A quiet stream hides a client that has gone: the backend relaying this
    // only notices on a write. The heartbeat is what lets a cancel during a
    // long model pause or a slow tool reach us.
    const heartbeat = setInterval(() => emit({ type: "ping" }), HEARTBEAT_MS);

    // how long each tool call spent away from the service: the bridge round
    // trip plus the tool's own run time in the browser
    const toolTimings = [];
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

    // A server-side tool reads the stored file. The connected workspace is
    // first asked to save what it has not saved yet — or the tool would miss
    // the agent's own last change — and to say what the user is looking at.
    const callServerTool = async (call, signal) => {
      const startedAt = performance.now();
      const synced = await callTool({ callId: randomUUID(), name: SYNC_CALL, input: {} }, signal);
      toolTimings.pop();
      const result = synced.isError
        ? { content: synced.content, isError: true }
        : await runBackendTool(backendUri, turnToken, call, synced.content, signal);
      toolTimings.push({ name: call.name, ms: Math.round(performance.now() - startedAt), server: true });
      emit({ type: "server-tool", name: call.name, input: call.input, content: result.content, isError: result.isError });
      return result;
    };

    try {
      const result = await harness.runTurn(
        { sessionId, profileId, provider, apiKey, model, system, text, images, tools, skills },
        { emit, callTool, callServerTool, serverTools, signal: controller.signal },
      );
      emit({ type: "done", reason: result.reason, resumed: result.resumed, toolTimings });
    } catch (cause) {
      emit({ type: "error", message: cause?.message ?? "the turn failed" });
    } finally {
      clearInterval(heartbeat);
      turns.delete(turnId);
      res.end();
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

  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, "http://localhost");
      if (req.method === "GET" && url.pathname === "/health") {
        return sendJson(res, 200, { ok: true, conversations: harness.conversations.size });
      }
      if (sharedKey && req.headers["x-penpot-shared-key"] !== sharedKey) {
        throw new HttpError(401, "unauthorized");
      }
      if (req.method === "POST" && url.pathname === "/v1/turns") return await handleTurn(req, res);
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
    backendUri: process.env.PENPOT_BACKEND_URI,
    sessionsRoot: process.env.PENPOT_AGENT_SERVICE_SESSIONS_DIR ?? join(dataDir, "sessions"),
    attachmentsHome: process.env.PENPOT_AGENT_SERVICE_ATTACHMENTS_DIR ?? join(dataDir, "attachments"),
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
