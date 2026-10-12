import assert from "node:assert/strict";
import { test } from "node:test";

import { MAX_MCP_SERVERS, listMcpTools, turnMcpServers } from "../src/mcp.js";
import { createServer } from "node:http";

import { assertPublicUrl, guardOrigin, guardedFetch, isPublicAddress } from "../src/netguard.js";
import { createAgentServer } from "../src/server.js";
import { startFakeAnthropic } from "./fake-anthropic.js";
import { startFakeMcp } from "./fake-mcp.js";

const header = (servers) => Buffer.from(JSON.stringify(servers)).toString("base64");
const resolveTo = (address) => async () => [{ address }];

test("only public addresses may be reached for a user", () => {
  for (const address of ["127.0.0.1", "10.1.2.3", "172.20.0.5", "192.168.1.9", "169.254.169.254", "100.64.0.1", "0.0.0.0"]) {
    assert.equal(isPublicAddress(address), false, address);
  }
  for (const address of ["::1", "::", "fe80::1", "fd00::1", "::ffff:10.0.0.1", "::ffff:127.0.0.1", "::ffff:7f00:1", "::ffff:a9fe:a9fe"]) {
    assert.equal(isPublicAddress(address), false, address);
  }
  for (const address of ["93.184.216.34", "8.8.8.8", "2606:4700:4700::1111", "::ffff:8.8.8.8"]) {
    assert.equal(isPublicAddress(address), true, address);
  }
  assert.equal(isPublicAddress("not-an-address"), false);
});

test("an MCP address has to be https and resolve to a public host", async () => {
  const pub = resolveTo("93.184.216.34");
  assert.equal((await assertPublicUrl("https://mcp.example.com/mcp", { resolve: pub })).hostname, "mcp.example.com");
  await assert.rejects(assertPublicUrl("http://mcp.example.com/mcp", { resolve: pub }), /must be https/);
  await assert.rejects(assertPublicUrl("https://user:pw@mcp.example.com/", { resolve: pub }), /must not carry credentials/);
  await assert.rejects(assertPublicUrl("https://intranet.example.com/", { resolve: resolveTo("10.0.0.7") }), /private or blocked/);
  // one private answer among public ones is enough to refuse
  await assert.rejects(
    assertPublicUrl("https://mixed.example.com/", { resolve: async () => [{ address: "8.8.8.8" }, { address: "127.0.0.1" }] }),
    /private or blocked/,
  );
  await assert.rejects(assertPublicUrl("https://127.0.0.1/mcp"), /private or blocked/);
  await assert.rejects(assertPublicUrl("https://[::1]/mcp"), /private or blocked/);
  await assert.rejects(assertPublicUrl("https://nowhere.example.com/", { resolve: async () => [] }), /does not resolve/);
  await assert.rejects(assertPublicUrl("file:///etc/passwd"), /must be https/);
  await assert.rejects(assertPublicUrl("nope"), /not a URL/);
  // a developer's own machine, when the service is told so
  assert.equal((await assertPublicUrl("http://127.0.0.1:3000/mcp", { allowPrivate: true })).port, "3000");
});

test("a turn's servers are checked one by one; a bad one is left out, not fatal", async () => {
  const { servers, refused } = await turnMcpServers(
    header([
      { name: "notes", url: "https://mcp.example.com/mcp", headers: { Authorization: "Bearer t", Cookie: "x=1", "X-Bad": "a\r\nb" } },
      { name: "notes", url: "https://other.example.com/mcp" },
      { name: "bad name!", url: "https://mcp.example.com/mcp" },
      { name: "inside", url: "https://intranet.example.com/mcp" },
      { name: "plain", url: "http://mcp.example.com/mcp" },
    ]),
    { resolve: async (host) => [{ address: host.startsWith("intranet") ? "10.0.0.1" : "93.184.216.34" }] },
  );
  assert.deepEqual(servers, [
    { name: "notes", url: "https://mcp.example.com/mcp", headers: { Authorization: "Bearer t" }, disabledTools: [] },
  ]);
  assert.deepEqual(refused.map((entry) => entry.name), ["notes", "bad name!", "inside", "plain"]);
  assert.match(refused[2].reason, /private or blocked/);
  assert.match(refused[3].reason, /must be https/);
});

test("what is not a list of servers is no servers", async () => {
  assert.deepEqual(await turnMcpServers(undefined), { servers: [], refused: [] });
  assert.deepEqual(await turnMcpServers("not base64 json"), { servers: [], refused: [] });
  assert.deepEqual(await turnMcpServers(header({ name: "x" })), { servers: [], refused: [] });
  const many = Array.from({ length: MAX_MCP_SERVERS + 3 }, (_, i) => ({ name: `s${i}`, url: "https://mcp.example.com/" }));
  const { servers } = await turnMcpServers(header(many), { resolve: resolveTo("93.184.216.34") });
  assert.equal(servers.length, MAX_MCP_SERVERS);
});

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

test("over HTTP: the servers of the header reach the model as tools, a refused one is reported", async () => {
  const mcp = await startFakeMcp();
  const fake = await startFakeAnthropic((_req, n) =>
    n === 0 ? { tool: { name: "mcp__notes__echo", input: { text: "from the panel" } } } : { text: "Done." },
  );
  const app = await createAgentServer({ providers: { anthropic: { baseURL: fake.baseURL } }, mcpAllowPrivate: true });
  const { port } = await app.listen(0, "127.0.0.1");
  try {
    const response = await fetch(`http://127.0.0.1:${port}/v1/turns`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-penpot-profile-id": "p1",
        "x-penpot-provider": "anthropic",
        "x-penpot-api-key": "key-one",
        "x-penpot-mcp-servers": header([
          { name: "notes", url: mcp.url, headers: { Authorization: "Bearer token-one" } },
          { name: "bad name!", url: mcp.url },
        ]),
      },
      body: JSON.stringify({ sessionId: "s-mcp", model: "claude-sonnet-4-5", system: "You are the design agent.", text: "hello", tools: [] }),
    });
    const seen = [];
    for await (const event of events(response)) seen.push(event);
    const said = seen.find((event) => event.type === "mcp");
    assert.deepEqual(said.servers, ["notes"]);
    assert.deepEqual(said.refused.map((entry) => entry.name), ["bad name!"]);
    assert.ok(seen.some((event) => event.type === "done"));
    assert.match(JSON.stringify(fake.requests[1].body.messages), /echo: from the panel/);
    assert.equal(mcp.seen.find((request) => request.method === "tools/call").authorization, "Bearer token-one");
  } finally {
    await app.close();
    await fake.close();
    await mcp.close();
  }
});

test("without the developer switch, a server on this machine is refused", async () => {
  const mcp = await startFakeMcp();
  const fake = await startFakeAnthropic(() => ({ text: "Done." }));
  const app = await createAgentServer({ providers: { anthropic: { baseURL: fake.baseURL } } });
  const { port } = await app.listen(0, "127.0.0.1");
  try {
    const response = await fetch(`http://127.0.0.1:${port}/v1/turns`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-penpot-profile-id": "p1",
        "x-penpot-provider": "anthropic",
        "x-penpot-api-key": "key-one",
        "x-penpot-mcp-servers": header([{ name: "notes", url: mcp.url }]),
      },
      body: JSON.stringify({ sessionId: "s-mcp-2", model: "claude-sonnet-4-5", system: "S", text: "hello", tools: [] }),
    });
    const seen = [];
    for await (const event of events(response)) seen.push(event);
    assert.deepEqual(seen.find((event) => event.type === "mcp").servers, []);
    assert.equal(mcp.seen.length, 0, "nothing was sent to it");
    assert.ok(!(fake.requests[0].body.tools ?? []).some((tool) => tool.name.startsWith("mcp__")));
  } finally {
    await app.close();
    await fake.close();
    await mcp.close();
  }
});

async function httpServer(handler) {
  const server = createServer(handler);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return { origin: `http://127.0.0.1:${server.address().port}`, close: () => new Promise((resolve) => server.close(resolve)) };
}

test("a guarded server cannot send the service somewhere else with a redirect", async () => {
  let reached = 0;
  const inside = await httpServer((_req, res) => {
    reached += 1;
    res.end("secret");
  });
  const outside = await httpServer((req, res) => {
    if (req.url === "/ok") return res.end("fine");
    res.writeHead(302, { location: `${inside.origin}/admin` }).end();
  });
  try {
    // addresses allowed, to look at redirects alone
    const guarded = guardedFetch(fetch, { origins: new Map([[outside.origin, 1]]), allowAddress: () => true });
    assert.equal(await (await guarded(`${outside.origin}/ok`)).text(), "fine");
    await assert.rejects(guarded(`${outside.origin}/mcp`, { method: "POST", body: "{}" }));
    assert.equal(reached, 0, "the redirect was not followed");
    // an origin nobody registered is not this guard's business
    assert.equal(await (await guarded(`${inside.origin}/x`)).text(), "secret");
  } finally {
    await inside.close();
    await outside.close();
  }
});

test("a guarded server is refused when its name resolves to an inside address at connect time", async () => {
  let reached = 0;
  const inside = await httpServer((_req, res) => {
    reached += 1;
    res.end("secret");
  });
  try {
    // the URL was public when it was checked; by the time of the request the
    // name answers with this machine
    const origin = inside.origin.replace("127.0.0.1", "localhost");
    const guarded = guardedFetch(fetch, { origins: new Map([[origin, 1]]) });
    await assert.rejects(guarded(`${origin}/mcp`));
    assert.equal(reached, 0);
  } finally {
    await inside.close();
  }
});

test("an origin is guarded while a conversation uses it, and not after", async () => {
  const server = await httpServer((_req, res) => res.end("here"));
  try {
    const url = `${server.origin.replace("127.0.0.1", "localhost")}/mcp`;
    assert.equal(await (await fetch(url)).text(), "here");
    const release = guardOrigin(url);
    const again = guardOrigin(url);
    await assert.rejects(fetch(url));
    release();
    await assert.rejects(fetch(url), "still in use by another conversation");
    again();
    assert.equal(await (await fetch(url)).text(), "here");
  } finally {
    await server.close();
  }
});

const extraTool = {
  name: "delete_everything",
  description: "Deletes it all.",
  inputSchema: { type: "object", properties: {} },
  call: () => ({ content: [{ type: "text", text: "gone" }] }),
};

test("a server's tools can be listed for the user to choose from", async () => {
  const mcp = await startFakeMcp({ tools: [extraTool] });
  try {
    const tools = await listMcpTools({ url: mcp.url, headers: { Authorization: "Bearer t" } }, { allowPrivate: true });
    assert.deepEqual(tools, [
      { name: "echo", description: "Says back what it is told." },
      { name: "delete_everything", description: "Deletes it all." },
    ]);
    assert.equal(mcp.seen.find((request) => request.method === "tools/list").authorization, "Bearer t");
    // the address rules are the turn's
    await assert.rejects(listMcpTools({ url: mcp.url }), /must be https/);
    await assert.rejects(listMcpTools({ url: "http://127.0.0.1:9/mcp" }, { allowPrivate: true }), /could not be reached/);
  } finally {
    await mcp.close();
  }
});

test("a tool the user switched off is not offered to the model", async () => {
  const mcp = await startFakeMcp({ tools: [extraTool] });
  const fake = await startFakeAnthropic(() => ({ text: "Done." }));
  const app = await createAgentServer({ providers: { anthropic: { baseURL: fake.baseURL } }, mcpAllowPrivate: true });
  const { port } = await app.listen(0, "127.0.0.1");
  try {
    const listed = await (
      await fetch(`http://127.0.0.1:${port}/v1/mcp/tools`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-penpot-profile-id": "p1" },
        body: JSON.stringify({ server: { url: mcp.url } }),
      })
    ).json();
    assert.deepEqual(listed.tools.map((tool) => tool.name), ["echo", "delete_everything"]);

    const response = await fetch(`http://127.0.0.1:${port}/v1/turns`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-penpot-profile-id": "p1",
        "x-penpot-provider": "anthropic",
        "x-penpot-api-key": "key-one",
        "x-penpot-mcp-servers": header([{ name: "notes", url: mcp.url, disabledTools: ["delete_everything"] }]),
      },
      body: JSON.stringify({ sessionId: "s-off", model: "claude-sonnet-4-5", system: "S", text: "hello", tools: [] }),
    });
    for await (const _event of events(response)) void _event;
    const offered = fake.requests[0].body.tools.map((tool) => tool.name).filter((name) => name.startsWith("mcp__"));
    assert.deepEqual(offered, ["mcp__notes__echo"]);
  } finally {
    await app.close();
    await fake.close();
    await mcp.close();
  }
});
