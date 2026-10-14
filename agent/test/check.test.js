import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { describe } from "../src/catalog.js";
import { checkModel, flatPng } from "../src/check.js";
import { createHarness } from "../src/harness.js";
import { startFakeAnthropic } from "./fake-anthropic.js";

const lastText = (request) => {
  const message = request.body.messages.at(-1);
  return typeof message.content === "string" ? message.content : message.content.map((part) => part.text ?? `[${part.type}]`).join(" ");
};

// A model that does what the check asks, unless told to misbehave.
const model = (flaws = {}) => (request) => {
  const text = lastText(request);
  const hasToolResult = JSON.stringify(request.body.messages).includes("tool_result");
  if (/single word pineapple/.test(text)) return { text: flaws.text ? "I cannot do that." : "pineapple" };
  if (/Record a measurement/.test(text) && !hasToolResult) {
    return flaws.tools === "none" ? { text: "Recorded width 42." } : { tool: { name: "record_measurement", input: { label: "width", value: flaws.tools === "wrong" ? 7 : 42 } } };
  }
  if (hasToolResult) return { text: "done" };
  if (/one flat color/.test(text)) return { text: flaws.vision ? "blue" : "Red" };
  return { text: "?" };
};

async function setup(script, vision = true) {
  const home = await mkdtemp(join(tmpdir(), "agent-check-"));
  const fake = await startFakeAnthropic(script);
  const harness = await createHarness({
    providers: { anthropic: { baseURL: fake.baseURL } },
    attachmentsHome: home,
    capabilities: (provider, id) => ({ ...describe(provider, { id, type: "chat" }), vision }),
  });
  return {
    fake,
    run: () => checkModel(harness, { provider: "anthropic", model: "claude-sonnet-4-5", apiKey: "key-one", profileId: "p1" }),
    async close() {
      await harness.dispose();
      await fake.close();
      await rm(home, { recursive: true, force: true });
    },
  };
}

test("a model that answers, calls the tool as asked and reads the image passes", async () => {
  const t = await setup(model());
  try {
    const result = await t.run();
    assert.equal(result.pass, true);
    assert.deepEqual([result.text.pass, result.tools.pass, result.vision.pass], [true, true, true]);
    assert.match(result.tools.saw, /"label":"width"/);
    // the image really went out, and the key was the caller's
    assert.ok(t.fake.requests.some((request) => JSON.stringify(request.body.messages).includes('"type":"image"')));
    assert.ok(t.fake.requests.every((request) => request.key === "key-one"));
  } finally {
    await t.close();
  }
});

test("each way of failing is told apart, with what was seen", async () => {
  for (const [flaws, failing, saw] of [
    [{ text: true }, "text", /I cannot do that/],
    [{ tools: "none" }, "tools", /no tool call; it said: Recorded width 42/],
    [{ tools: "wrong" }, "tools", /"value":7/],
    [{ vision: true }, "vision", /blue/],
  ]) {
    const t = await setup(model(flaws));
    try {
      const result = await t.run();
      assert.equal(result.pass, false, failing);
      assert.equal(result[failing].pass, false, failing);
      assert.match(result[failing].saw, saw);
      for (const other of ["text", "tools", "vision"].filter((name) => name !== failing)) assert.equal(result[other].pass, true, `${failing}: ${other}`);
    } finally {
      await t.close();
    }
  }
});

test("a model that does not claim vision is not asked to see, and still passes", async () => {
  for (const vision of [false, null]) {
    const t = await setup(model(), vision);
    try {
      const result = await t.run();
      assert.equal(result.pass, true);
      assert.equal(result.vision.skipped, true);
      assert.ok(!t.fake.requests.some((request) => /one flat color/.test(JSON.stringify(request.body.messages))));
    } finally {
      await t.close();
    }
  }
});

test("a provider that refuses the request fails the check, in its own words", async () => {
  const fake = await startFakeAnthropic(() => ({ text: "pineapple" }));
  const harness = await createHarness({ providers: { anthropic: { baseURL: "http://127.0.0.1:9" } } });
  try {
    const result = await checkModel(harness, { provider: "anthropic", model: "claude-sonnet-4-5", apiKey: "key-one", profileId: "p1" });
    assert.equal(result.pass, false);
    assert.equal(result.text.pass, false);
    assert.ok(result.text.saw.length > 0);
  } finally {
    await harness.dispose();
    await fake.close();
  }
});

test("the probe image is a PNG of the color asked for", () => {
  const png = flatPng([220, 20, 20], 8);
  assert.deepEqual([...png.subarray(0, 8)], [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  assert.equal(png.readUInt32BE(16), 8);
  assert.equal(png.readUInt32BE(20), 8);
});

test("a check keeps none of its conversations", async () => {
  const sessions = await mkdtemp(join(tmpdir(), "agent-check-sessions-"));
  const fake = await startFakeAnthropic(model());
  const harness = await createHarness({
    providers: { anthropic: { baseURL: fake.baseURL } },
    sessionsRoot: sessions,
    capabilities: (provider, id) => ({ ...describe(provider, { id, type: "chat" }), vision: false }),
  });
  try {
    const result = await checkModel(harness, { provider: "anthropic", model: "claude-sonnet-4-5", apiKey: "key-one", profileId: "p1" });
    assert.equal(result.pass, true);
    assert.equal(harness.conversations.size, 0);
    const { readdir } = await import("node:fs/promises");
    assert.deepEqual(await readdir(join(sessions, "_no-cwd")).catch(() => []), []);
  } finally {
    await harness.dispose();
    await fake.close();
    await rm(sessions, { recursive: true, force: true });
  }
});
