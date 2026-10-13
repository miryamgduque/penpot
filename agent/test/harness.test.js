import assert from "node:assert/strict";
import { test } from "node:test";

import { createHarness } from "../src/harness.js";
import { startFakeAnthropic } from "./fake-anthropic.js";
import { startFakeMcp } from "./fake-mcp.js";

const MODEL = "claude-sonnet-4-5";

async function setup(script) {
  const fake = await startFakeAnthropic(script);
  const harness = await createHarness({ providers: { anthropic: { baseURL: fake.baseURL } } });
  return {
    fake,
    harness,
    async close() {
      await harness.dispose();
      await fake.close();
    },
  };
}

function turn(overrides) {
  return {
    sessionId: "s1",
    profileId: "p1",
    provider: "anthropic",
    model: MODEL,
    apiKey: "key-one",
    system: "You are the design agent. Literal braces {{stay}} as they are.",
    text: "hello",
    tools: [],
    ...overrides,
  };
}

test("a turn streams text and ends", async () => {
  const t = await setup(() => ({ text: "Hello from the model" }));
  try {
    const events = [];
    const result = await t.harness.runTurn(turn(), { emit: (e) => events.push(e), callTool: async () => ({}) });
    assert.equal(result.reason, "completed");
    const text = events.filter((e) => e.type === "text-delta").map((e) => e.text).join("");
    assert.equal(text, "Hello from the model");
    assert.ok(events.some((e) => e.type === "usage"));
    assert.equal(t.fake.requests[0].key, "key-one");
    assert.match(JSON.stringify(t.fake.requests[0].body.system), /\{\{stay\}\}/);
    assert.deepEqual(t.fake.requests[0].body.tools ?? [], []);
  } finally {
    await t.close();
  }
});

const TOOL = {
  name: "create_shape",
  description: "Creates a shape.",
  inputSchema: { type: "object", properties: { type: { type: "string" } }, required: ["type"] },
};

test("a tool call is delegated and its result reaches the model", async () => {
  const t = await setup((_req, n) =>
    n === 0 ? { tool: { name: "create_shape", input: { type: "rect" } } } : { text: "Done." },
  );
  try {
    const calls = [];
    const events = [];
    await t.harness.runTurn(turn({ tools: [TOOL] }), {
      emit: (e) => events.push(e),
      callTool: async (call) => {
        calls.push(call);
        return { content: '{"id":"abc","type":"rect"}', isError: false };
      },
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].name, "create_shape");
    assert.deepEqual(calls[0].input, { type: "rect" });
    // the declaration reaches the provider with our name and schema, untouched
    assert.deepEqual(t.fake.requests[0].body.tools.map((x) => x.name), ["create_shape"]);
    assert.deepEqual(t.fake.requests[0].body.tools[0].input_schema.required, ["type"]);
    // the second request carries the tool result
    assert.match(JSON.stringify(t.fake.requests[1].body.messages), /\\"id\\":\\"abc\\"/);
    assert.equal(events.filter((e) => e.type === "text-delta").map((e) => e.text).join(""), "Done.");
  } finally {
    await t.close();
  }
});

test("a rejected tool call reaches the model as an error, with its message", async () => {
  const t = await setup((_req, n) =>
    n === 0 ? { tool: { name: "create_shape", input: { type: "rect" } } } : { text: "I will use a token." },
  );
  try {
    await t.harness.runTurn(turn({ tools: [TOOL] }), {
      emit: () => {},
      callTool: async () => ({ content: "token-only-colors: use a color token", isError: true }),
    });
    const second = JSON.stringify(t.fake.requests[1].body.messages);
    assert.match(second, /token-only-colors: use a color token/);
    assert.match(second, /"is_error":true/);
  } finally {
    await t.close();
  }
});

test("concurrent conversations each spend only their own key", async () => {
  const t = await setup(() => ({ delayMs: 60, text: "ok" }));
  try {
    const run = (sessionId, profileId, apiKey, text) =>
      t.harness.runTurn(turn({ sessionId, profileId, apiKey, text }), { emit: () => {}, callTool: async () => ({}) });
    await Promise.all([
      run("sa", "pa", "key-A", "from A"),
      run("sb", "pb", "key-B", "from B"),
      run("sc", "pc", "key-C", "from C"),
    ]);
    assert.equal(t.fake.requests.length, 3);
    for (const request of t.fake.requests) {
      const said = JSON.stringify(request.body.messages);
      const who = /from (\w)/.exec(said)[1];
      assert.equal(request.key, `key-${who}`);
    }
  } finally {
    await t.close();
  }
});

test("another profile presenting the same conversation id gets its own, empty conversation", async () => {
  const t = await setup(() => ({ text: "ok" }));
  try {
    const io = { emit: () => {}, callTool: async () => ({}) };
    await t.harness.runTurn(turn({ text: "the owner's secret plan" }), io);
    await t.harness.runTurn(turn({ profileId: "someone-else", apiKey: "key-two", text: "what did we discuss?" }), io);
    const second = t.fake.requests[1];
    assert.equal(second.key, "key-two");
    assert.doesNotMatch(JSON.stringify(second.body.messages), /secret plan/);
  } finally {
    await t.close();
  }
});

test("a stored conversation is resumed by a fresh process, by its owner only", async () => {
  const { mkdtemp, rm } = await import("node:fs/promises");
  const { tmpdir } = await import("node:os");
  const { join } = await import("node:path");
  const root = await mkdtemp(join(tmpdir(), "agent-sessions-"));
  const fake = await startFakeAnthropic(() => ({ text: "ok" }));
  const start = () => createHarness({ providers: { anthropic: { baseURL: fake.baseURL } }, sessionsRoot: root });
  const io = { emit: () => {}, callTool: async () => ({}) };
  try {
    const first = await start();
    await first.runTurn(turn({ text: "remember the word heliotrope" }), io);
    await first.dispose();

    // what is written to disk is the conversation, never the credential
    const { readdir, readFile } = await import("node:fs/promises");
    const files = (await readdir(root, { recursive: true, withFileTypes: true })).filter((f) => f.isFile());
    assert.ok(files.length > 0);
    for (const file of files) {
      const stored = await readFile(join(file.parentPath, file.name), "utf8");
      assert.doesNotMatch(stored, /key-one/);
    }

    const second = await start();
    const result = await second.runTurn(turn({ text: "which word?" }), io);
    assert.equal(result.resumed, true);
    assert.match(JSON.stringify(fake.requests[1].body.messages), /heliotrope/);
    await second.runTurn(turn({ profileId: "someone-else", text: "which word?" }), io);
    assert.doesNotMatch(JSON.stringify(fake.requests[2].body.messages), /heliotrope/);
    await second.dispose();
  } finally {
    await fake.close();
    await rm(root, { recursive: true, force: true });
  }
});

test("an idle conversation leaves memory", async () => {
  const fake = await startFakeAnthropic(() => ({ text: "ok" }));
  const harness = await createHarness({ providers: { anthropic: { baseURL: fake.baseURL } }, idleMs: 30 });
  try {
    await harness.runTurn(turn(), { emit: () => {}, callTool: async () => ({}) });
    assert.equal(harness.conversations.size, 1);
    await new Promise((r) => setTimeout(r, 120));
    assert.equal(harness.conversations.size, 0);
  } finally {
    await harness.dispose();
    await fake.close();
  }
});

test("a key is not kept after the turn", async () => {
  const t = await setup(() => ({ text: "ok" }));
  try {
    await t.harness.runTurn(turn(), { emit: () => {}, callTool: async () => ({}) });
    assert.equal(t.harness.ctx.credentials.keysBySession.size, 0);
  } finally {
    await t.close();
  }
});

test("a second turn continues the same conversation", async () => {
  const t = await setup(() => ({ text: "ok" }));
  try {
    const io = { emit: () => {}, callTool: async () => ({}) };
    await t.harness.runTurn(turn({ text: "first" }), io);
    await t.harness.runTurn(turn({ text: "second" }), io);
    const messages = JSON.stringify(t.fake.requests[1].body.messages);
    assert.match(messages, /first/);
    assert.match(messages, /second/);
  } finally {
    await t.close();
  }
});

test("cancelling aborts the provider request and ends the turn", async () => {
  const t = await setup(() => ({ delayMs: 5000, text: "never" }));
  try {
    const controller = new AbortController();
    setTimeout(() => controller.abort(), 150);
    const started = Date.now();
    const result = await t.harness.runTurn(turn(), {
      emit: () => {},
      callTool: async () => ({}),
      signal: controller.signal,
    });
    assert.equal(result.reason, "aborted");
    assert.ok(Date.now() - started < 2000);
    await new Promise((r) => setTimeout(r, 50));
    assert.equal(t.fake.requests[0].aborted, true);
  } finally {
    await t.close();
  }
});

test("a Penpot skill is listed to the model and loaded through the harness skill tool", async () => {
  const skills = [{ name: "penpot-build-screen", description: "How to build a screen.", body: "STEP ONE: read the design first." }];
  const t = await setup((_req, n) => (n === 0 ? { tool: { name: "skill", input: { name: "penpot-build-screen" } } } : { text: "Loaded." }));
  try {
    const delegated = [];
    await t.harness.runTurn(turn({ skills }), {
      emit: () => {},
      callTool: async (call) => (delegated.push(call), {}),
    });
    const first = JSON.stringify(t.fake.requests[0].body);
    assert.match(first, /penpot-build-screen/);
    assert.match(first, /How to build a screen\./);
    assert.doesNotMatch(first, /STEP ONE/);
    assert.match(JSON.stringify(t.fake.requests[1].body.messages), /STEP ONE: read the design first\./);
    // the harness ran its own tool: nothing was sent to the browser
    assert.equal(delegated.length, 0);

    // another conversation, with no skills, sees none of it
    await t.harness.runTurn(turn({ sessionId: "s2", text: "hi" }), { emit: () => {}, callTool: async () => ({}) });
    assert.doesNotMatch(JSON.stringify(t.fake.requests[2].body), /penpot-build-screen/);
  } finally {
    await t.close();
  }
});

test("a skill the registry will not take does not cost the others", async () => {
  const skills = [
    { name: "Not Kebab--case", description: "Refused.", body: "NEVER" },
    { name: "penpot-build-screen", description: "How to build a screen.", body: "STEP ONE" },
  ];
  const t = await setup((_req, n) => (n === 0 ? { tool: { name: "skill", input: { name: "penpot-build-screen" } } } : { text: "Loaded." }));
  try {
    await t.harness.runTurn(turn({ skills }), { emit: () => {}, callTool: async () => ({}) });
    assert.match(JSON.stringify(t.fake.requests[1].body.messages), /STEP ONE/);
  } finally {
    await t.close();
  }
});

test("a long conversation is condensed, with the owner's key", async () => {
  const fake = await startFakeAnthropic(() => ({ text: "noted. ".repeat(400), countTokens: true }));
  const harness = await createHarness({
    providers: { anthropic: { baseURL: fake.baseURL, models: [{ id: MODEL, contextWindow: 12000, maxTokens: 1000 }] } },
    compaction: { headroomTokens: 0, thresholdRatio: 0.3, maxTokens: 500 },
    maxTokens: 1000,
  });
  try {
    const io = { emit: () => {}, callTool: async () => ({}) };
    for (let i = 0; i < 6; i++) {
      await harness.runTurn(turn({ text: `turn ${i}: ` + "lorem ipsum dolor sit amet ".repeat(150) }), io);
    }
    // more provider requests than turns: the extra ones are condensation
    assert.ok(fake.requests.length > 6, `requests: ${fake.requests.length}`);
    for (const request of fake.requests) assert.equal(request.key, "key-one");
    const last = JSON.stringify(fake.requests.at(-1).body.messages);
    assert.doesNotMatch(last, /turn 0:/);
  } finally {
    await harness.dispose();
    await fake.close();
  }
});

const PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

test("images reach the model from the user message and from a tool result", async () => {
  const { mkdtemp, rm } = await import("node:fs/promises");
  const { tmpdir } = await import("node:os");
  const { join } = await import("node:path");
  const home = await mkdtemp(join(tmpdir(), "agent-attachments-"));
  const fake = await startFakeAnthropic((_req, n) => (n === 0 ? { tool: { name: "create_shape", input: { type: "rect" } } } : { text: "I see it." }));
  const harness = await createHarness({ providers: { anthropic: { baseURL: fake.baseURL } }, attachmentsHome: home });
  try {
    await harness.runTurn(turn({ tools: [TOOL], images: [{ mediaType: "image/png", data: PNG }] }), {
      emit: () => {},
      callTool: async () => ({ content: "rendered", images: [{ mediaType: "image/png", data: PNG }], isError: false }),
    });
    const imagesIn = (request) => (JSON.stringify(request.body.messages).match(/"type":"image"/g) ?? []).length;
    assert.equal(imagesIn(fake.requests[0]), 1);
    assert.equal(imagesIn(fake.requests[1]), 2);
    assert.match(JSON.stringify(fake.requests[1].body.messages), /rendered/);
  } finally {
    await harness.dispose();
    await fake.close();
    await rm(home, { recursive: true, force: true });
  }
});

test("a model that reads no images is sent none, and is told what it did not see", async () => {
  const { mkdtemp, rm } = await import("node:fs/promises");
  const { tmpdir } = await import("node:os");
  const { join } = await import("node:path");
  const { describe } = await import("../src/catalog.js");
  const home = await mkdtemp(join(tmpdir(), "agent-attachments-"));
  for (const vision of [false, null]) {
    const fake = await startFakeAnthropic((_req, n) => (n === 0 ? { tool: { name: "create_shape", input: { type: "rect" } } } : { text: "I could not see it." }));
    const harness = await createHarness({
      providers: { anthropic: { baseURL: fake.baseURL } },
      attachmentsHome: home,
      capabilities: (provider, model) => ({ ...describe(provider, { id: model, name: "Blind One", type: "chat" }), vision }),
    });
    try {
      await harness.runTurn(turn({ tools: [TOOL], images: [{ mediaType: "image/png", data: PNG }] }), {
        emit: () => {},
        callTool: async () => ({ content: "rendered", images: [{ mediaType: "image/png", data: PNG }], isError: false }),
      });
      const sent = JSON.stringify(fake.requests[1].body);
      // no image goes out, from the user or from the tool
      assert.equal((sent.match(/"type":"image"/g) ?? []).length, 0);
      assert.match(sent, /1 image\(s\) attached to this message were not delivered: the model in use \(Blind One\) cannot read images/);
      assert.match(sent, /1 image\(s\) returned by this tool were not delivered/);
      // and its briefing says what that means for the user
      const system = JSON.stringify(fake.requests[0].body.system);
      assert.match(system, /What this model cannot do/);
      assert.match(system, /say plainly that you cannot see the board with this model/);
      assert.match(system, vision === false ? /does not read images/ : /is not known to read images/);
    } finally {
      await harness.dispose();
      await fake.close();
    }
  }
  await rm(home, { recursive: true, force: true });
});

test("a model that sees is told of no limits", async () => {
  const t = await setup(() => ({ text: "ok" }));
  try {
    await t.harness.runTurn(turn(), { emit: () => {}, callTool: async () => ({ content: "", isError: false }) });
    assert.doesNotMatch(JSON.stringify(t.fake.requests[0].body.system), /What this model cannot do/);
  } finally {
    await t.close();
  }
});

test("a model that cannot call tools cannot be the agent", async () => {
  const { describe } = await import("../src/catalog.js");
  const fake = await startFakeAnthropic(() => ({ text: "ok" }));
  const harness = await createHarness({
    providers: { anthropic: { baseURL: fake.baseURL } },
    capabilities: (provider, model) => ({ ...describe(provider, { id: model, name: "Talker" }), tools: model === "no-tools" ? false : null }),
  });
  try {
    await assert.rejects(
      harness.runTurn(turn({ model: "no-tools" }), { emit: () => {}, callTool: async () => ({ content: "", isError: false }) }),
      (error) => error.status === 400 && /Talker cannot call tools, which the design agent needs/.test(error.message),
    );
    assert.equal(fake.requests.length, 0);
    // one whose tool use nobody stated is tried: unknown is not a no
    await harness.runTurn(turn({ sessionId: "s2" }), { emit: () => {}, callTool: async () => ({ content: "", isError: false }) });
    assert.equal(fake.requests.length, 1);
  } finally {
    await harness.dispose();
    await fake.close();
  }
});

test("without an image store, images are reported in words, not dropped silently", async () => {
  const t = await setup(() => ({ text: "ok" }));
  try {
    await t.harness.runTurn(turn({ images: [{ mediaType: "image/png", data: PNG }] }), { emit: () => {}, callTool: async () => ({}) });
    assert.match(JSON.stringify(t.fake.requests[0].body.messages), /1 image\(s\) attached to this message were not delivered/);
  } finally {
    await t.close();
  }
});

test("a user's MCP server: its tools are the conversation's, called with the headers it was given", async () => {
  const mcp = await startFakeMcp();
  const t = await setup((_req, n) =>
    n === 0 ? { tool: { name: "mcp__notes__echo", input: { text: "hi there" } } } : { text: "Done." },
  );
  try {
    const mcpServers = [{ name: "notes", url: mcp.url, headers: { Authorization: "Bearer token-one" } }];
    const delegated = [];
    const emitted = [];
    await t.harness.runTurn(turn({ mcpServers }), {
      emit: (event) => emitted.push(event),
      callTool: async (call) => (delegated.push(call), {}),
    });
    // the panel is told of the call, under the name that carries the service's
    assert.deepEqual(
      emitted.filter((event) => event.type === "server-tool"),
      [{ type: "server-tool", name: "mcp__notes__echo", input: { text: "hi there" }, content: "echo: hi there", isError: false }],
    );
    const first = t.fake.requests[0].body;
    assert.ok(first.tools.some((tool) => tool.name === "mcp__notes__echo"), "the server's tool is listed to the model");
    assert.match(JSON.stringify(first.system), /belong to services the user connected/);
    // the harness called the server itself: nothing was asked of a workspace
    assert.equal(delegated.length, 0);
    assert.match(JSON.stringify(t.fake.requests[1].body.messages), /echo: hi there/);
    const call = mcp.seen.find((request) => request.method === "tools/call");
    assert.deepEqual(call.params.arguments, { text: "hi there" });
    assert.equal(call.authorization, "Bearer token-one");

    // another conversation, with no servers, has none of it
    await t.harness.runTurn(turn({ sessionId: "s2", text: "hi" }), { emit: () => {}, callTool: async () => ({}) });
    const other = t.fake.requests[2].body;
    assert.ok(!(other.tools ?? []).some((tool) => tool.name.startsWith("mcp__")));
    assert.doesNotMatch(JSON.stringify(other.system), /services the user connected/);
  } finally {
    await t.close();
    await mcp.close();
  }
});

test("an MCP server that does not answer costs the turn nothing but its tools", async () => {
  const t = await setup(() => ({ text: "Still here." }));
  try {
    const mcpServers = [{ name: "gone", url: "http://127.0.0.1:9/mcp", headers: {} }];
    const result = await t.harness.runTurn(turn({ mcpServers }), { emit: () => {}, callTool: async () => ({}) });
    assert.equal(result.reason, "completed");
    assert.ok(!(t.fake.requests[0].body.tools ?? []).some((tool) => tool.name.startsWith("mcp__")));
  } finally {
    await t.close();
  }
});
