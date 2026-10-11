import assert from "node:assert/strict";
import { test } from "node:test";

import { createAgentServer } from "../src/server.js";
import { startFakeAnthropic } from "./fake-anthropic.js";

async function setup(script, options = {}) {
  const fake = await startFakeAnthropic(script);
  const app = await createAgentServer({ providers: { anthropic: { baseURL: fake.baseURL } }, ...options });
  const { port } = await app.listen(0, "127.0.0.1");
  const base = `http://127.0.0.1:${port}`;
  return {
    fake,
    base,
    async close() {
      await app.close();
      await fake.close();
    },
  };
}

const HEADERS = {
  "content-type": "application/json",
  "x-penpot-profile-id": "p1",
  "x-penpot-provider": "anthropic",
  "x-penpot-api-key": "key-one",
};

const BODY = {
  sessionId: "s1",
  model: "claude-sonnet-4-5",
  system: "You are the design agent.",
  text: "hello",
  tools: [{ name: "create_shape", description: "Creates a shape.", inputSchema: { type: "object", properties: {} } }],
};

async function* events(response) {
  let buffer = "";
  for await (const chunk of response.body) {
    buffer += Buffer.from(chunk).toString("utf8");
    let at;
    while ((at = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, at);
      buffer = buffer.slice(at + 1);
      if (line) yield JSON.parse(line);
    }
  }
}

test("a turn over HTTP: tool call answered through the result endpoint", async () => {
  const t = await setup((_req, n) => (n === 0 ? { tool: { name: "create_shape", input: { type: "rect" } } } : { text: "Done." }));
  try {
    const response = await fetch(`${t.base}/v1/turns`, { method: "POST", headers: HEADERS, body: JSON.stringify(BODY) });
    assert.equal(response.status, 200);
    const seen = [];
    let turnId;
    for await (const event of events(response)) {
      seen.push(event.type);
      if (event.type === "turn") turnId = event.turnId;
      if (event.type === "tool-call") {
        // someone else's profile cannot answer this turn
        const foreign = await fetch(`${t.base}/v1/turns/${turnId}/tool-results`, {
          method: "POST",
          headers: { ...HEADERS, "x-penpot-profile-id": "p2" },
          body: JSON.stringify({ callId: event.callId, content: "hijacked" }),
        });
        assert.equal(foreign.status, 404);
        const ok = await fetch(`${t.base}/v1/turns/${turnId}/tool-results`, {
          method: "POST",
          headers: HEADERS,
          body: JSON.stringify({ callId: event.callId, content: '{"id":"abc"}', isError: false }),
        });
        assert.equal(ok.status, 200);
      }
    }
    assert.deepEqual([...new Set(seen)].filter((t) => t !== "ping"), ["turn", "usage", "tool-call", "text-delta", "done"]);
    assert.doesNotMatch(JSON.stringify(t.fake.requests[1].body.messages), /hijacked/);
  } finally {
    await t.close();
  }
});

test("closing the connection cancels the turn upstream", async () => {
  const t = await setup(() => ({ delayMs: 5000, text: "never" }));
  try {
    const controller = new AbortController();
    const response = await fetch(`${t.base}/v1/turns`, {
      method: "POST",
      headers: HEADERS,
      body: JSON.stringify(BODY),
      signal: controller.signal,
    });
    const reader = response.body.getReader();
    await reader.read();
    controller.abort();
    await new Promise((r) => setTimeout(r, 300));
    assert.equal(t.fake.requests[0].aborted, true);
  } finally {
    await t.close();
  }
});

test("the shared key is required when configured", async () => {
  const t = await setup(() => ({ text: "ok" }), { sharedKey: "secret" });
  try {
    const denied = await fetch(`${t.base}/v1/turns`, { method: "POST", headers: HEADERS, body: JSON.stringify(BODY) });
    assert.equal(denied.status, 401);
    const health = await fetch(`${t.base}/health`);
    assert.equal(health.status, 200);
  } finally {
    await t.close();
  }
});

test("a server tool: the workspace is synced first, then the backend runs it with the turn token", async () => {
  const { createServer } = await import("node:http");
  const seen = [];
  const backend = createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      seen.push({ url: req.url, body: JSON.parse(raw) });
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ content: '{"file":"Stored"}', isError: false }));
    });
  });
  await new Promise((r) => backend.listen(0, "127.0.0.1", r));
  const t = await setup(
    (_req, n) => (n === 0 ? { tool: { name: "read_design", input: {} } } : { text: "Read." }),
    { backendUri: `http://127.0.0.1:${backend.address().port}` },
  );
  try {
    const body = { ...BODY, tools: [{ name: "read_design", description: "Reads.", inputSchema: { type: "object", properties: {} } }] };
    const headers = { ...HEADERS, "x-penpot-turn-token": "turn-token-1", "x-penpot-server-tools": "read_design,find_shapes" };
    const response = await fetch(`${t.base}/v1/turns`, { method: "POST", headers, body: JSON.stringify(body) });
    const order = [];
    let turnId;
    let done;
    for await (const event of events(response)) {
      if (event.type === "turn") turnId = event.turnId;
      if (event.type === "tool-call") {
        order.push(`workspace:${event.name}`);
        assert.equal(seen.length, 0, "the backend must not be asked before the workspace has saved");
        await fetch(`${t.base}/v1/turns/${turnId}/tool-results`, {
          method: "POST",
          headers: HEADERS,
          body: JSON.stringify({ callId: event.callId, content: '{"pageId":"page-1","selectedIds":[]}' }),
        });
      }
      if (event.type === "server-tool") order.push(`server:${event.name}`);
      if (event.type === "done") done = event;
    }
    assert.deepEqual(order, ["workspace:__sync", "server:read_design"]);
    assert.equal(seen[0].url, "/api/rpc/command/run-agent-tool");
    assert.equal(seen[0].body.token, "turn-token-1");
    assert.equal(seen[0].body.tool, "read_design");
    assert.equal(JSON.parse(seen[0].body.context).pageId, "page-1");
    assert.match(JSON.stringify(t.fake.requests[1].body.messages), /Stored/);
    assert.deepEqual(done.toolTimings.map((x) => [x.name, x.server]), [["read_design", true]]);
  } finally {
    await t.close();
    await new Promise((r) => backend.close(r));
  }
});

test("without a turn token every tool goes to the workspace", async () => {
  const t = await setup((_req, n) => (n === 0 ? { tool: { name: "read_design", input: {} } } : { text: "Read." }));
  try {
    const body = { ...BODY, tools: [{ name: "read_design", description: "Reads.", inputSchema: { type: "object", properties: {} } }] };
    const response = await fetch(`${t.base}/v1/turns`, {
      method: "POST",
      headers: { ...HEADERS, "x-penpot-server-tools": "read_design" },
      body: JSON.stringify(body),
    });
    const asked = [];
    let turnId;
    for await (const event of events(response)) {
      if (event.type === "turn") turnId = event.turnId;
      if (event.type === "tool-call") {
        asked.push(event.name);
        await fetch(`${t.base}/v1/turns/${turnId}/tool-results`, {
          method: "POST",
          headers: HEADERS,
          body: JSON.stringify({ callId: event.callId, content: "{}" }),
        });
      }
    }
    assert.deepEqual(asked, ["read_design"]);
  } finally {
    await t.close();
  }
});
