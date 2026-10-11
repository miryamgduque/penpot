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
