import assert from "node:assert/strict";
import { test } from "node:test";

import { runScoutTool, scoutTools } from "../src/scout.js";

const image = { mediaType: "image/png", data: "AAAA" };

test("the critic is offered wherever the file can be read", () => {
  assert.ok(scoutTools(new Set(["read_design"])).has("critique_design"));
  assert.ok(!scoutTools(new Set()).has("critique_design"));
});

test("critique_design shows the render and the brief to a critic that holds no tools", async () => {
  const seen = {};
  const result = await runScoutTool(
    { name: "critique_design", input: { names: ["Landing", "Old landing", "Third"], brief: "A bold landing page for a synth festival." } },
    {
      provider: "anthropic",
      model: "the-turn-model",
      declared: [{ name: "read_design" }],
      render: async (names) => {
        seen.rendered = names;
        return { content: "{}", images: [image, image], isError: false };
      },
      side: async (request) => {
        seen.side = request;
        return "**Verdict:** competent and forgettable (3/5).";
      },
    },
  );
  assert.equal(result.isError, false);
  assert.deepEqual(JSON.parse(result.content), { critique: "**Verdict:** competent and forgettable (3/5).", boards: ["Landing", "Old landing"] });
  // at most two boards, one to judge or two to compare
  assert.deepEqual(seen.rendered, ["Landing", "Old landing"]);
  // the turn's own model, the images, no tools, and the brief in the message
  assert.equal(seen.side.model, "the-turn-model");
  assert.deepEqual(seen.side.images, [image, image]);
  assert.deepEqual(seen.side.tools, []);
  assert.match(seen.side.text, /Brief: A bold landing page for a synth festival\./);
  assert.match(seen.side.system, /find what is weakest, not to approve/);
  assert.match(seen.side.system, /Hierarchy/);
  assert.match(seen.side.system, /mistaken for a template/);
});

test("critique_design says what it lacks", async () => {
  const context = { provider: "anthropic", model: "m", declared: [], side: async () => "x", render: async () => ({ images: [image] }) };
  const run = (input, more = {}) => runScoutTool({ name: "critique_design", input }, { ...context, ...more });
  assert.match((await run({ brief: "x" })).content, /needs `names`/);
  assert.match((await run({ names: ["A"] })).content, /needs a `brief`/);
  assert.match((await run({ names: ["A"], brief: "x" }, { render: undefined })).content, /cannot render here/);
  const failed = await run({ names: ["A"], brief: "x" }, { render: async () => ({ content: "no shape named A", isError: true }) });
  assert.equal(failed.isError, true);
  assert.match(failed.content, /could not render A: no shape named A/);
  const silent = await run({ names: ["A"], brief: "x" }, { side: async () => "  " });
  assert.match(silent.content, /the critic returned nothing/);
});
