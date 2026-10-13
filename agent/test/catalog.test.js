import assert from "node:assert/strict";
import { test } from "node:test";

import { CAPABILITIES, catalog, describe, problem, unknowns } from "../src/catalog.js";
import { PROVIDER_ROUTES } from "../src/harness.js";

test("a model is described with every capability, limit and price the schema has", () => {
  const record = describe("anthropic", {
    id: "claude-x",
    name: "Claude X",
    type: "chat",
    reasoning: true,
    input: ["text", "image"],
    cost: { input: 3, output: 15, cacheRead: 0.3, cacheWrite: 3.75 },
    contextWindow: 1000000,
    maxTokens: 64000,
    promptCache: { short: 300 },
  });
  assert.deepEqual(record, {
    provider: "anthropic",
    id: "claude-x",
    name: "Claude X",
    vision: true,
    tools: true,
    reasoning: true,
    promptCaching: true,
    contextLimit: 1000000,
    outputLimit: 64000,
    price: { input: 3, output: 15, cacheRead: 0.3, cacheWrite: 3.75 },
  });
  assert.equal(problem(record), undefined);
  assert.deepEqual(unknowns(record), []);
});

test("what the source does not say is unknown, never assumed", () => {
  const record = describe("local", { id: "some-model" });
  assert.equal(record.name, "some-model");
  for (const key of [...CAPABILITIES, "contextLimit", "outputLimit", "price"]) assert.equal(record[key], null, key);
  assert.equal(problem(record), undefined);
  assert.deepEqual(unknowns(record), ["vision", "tools", "reasoning", "promptCaching", "contextLimit", "outputLimit", "price"]);
  // text only is known: it does not read images
  assert.equal(describe("p", { id: "m", input: ["text"] }).vision, false);
  // a price for reading the cache says there is one; a price list without one says there is none
  assert.equal(describe("p", { id: "m", cost: { input: 1, output: 2, cacheRead: 0.1, cacheWrite: 0 } }).promptCaching, true);
  assert.equal(describe("p", { id: "m", cost: { input: 1, output: 2, cacheRead: 0, cacheWrite: 0 } }).promptCaching, false);
  // and where no price is stated at all, nothing is said
  assert.equal(describe("p", { id: "m", cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } }).promptCaching, null);
  // a price of nothing is a price nobody stated
  assert.equal(describe("p", { id: "m", cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } }).price, null);
});

test("a record that is not one is told why", () => {
  const record = describe("p", { id: "m", type: "chat", input: ["text"], contextWindow: 8000, maxTokens: 1000 });
  assert.equal(problem(record), undefined);
  assert.match(problem({ ...record, vision: "yes" }), /vision is true, false, or null for unknown/);
  assert.match(problem({ ...record, contextLimit: 0 }), /contextLimit is a number of tokens/);
  assert.match(problem({ ...record, id: "" }), /needs a `id`/);
  assert.match(problem({ ...record, price: { input: 1 } }), /price.output is a number/);
});

test("the catalog comes from the provider layer, for every provider Penpot offers", () => {
  const { schema, providers } = catalog(PROVIDER_ROUTES);
  assert.equal(schema, 1);
  assert.deepEqual(Object.keys(providers).sort(), Object.keys(PROVIDER_ROUTES).sort());
  for (const [provider, records] of Object.entries(providers)) {
    assert.ok(records.length > 0, `${provider} has models`);
    for (const record of records) {
      assert.equal(problem(record), undefined, `${provider}/${record.id}`);
      assert.equal(record.provider, provider);
      // every model offered calls tools: the agent is nothing without them
      assert.equal(record.tools, true, `${provider}/${record.id}`);
    }
  }
});

test("a model a provider ships appears with no change here", () => {
  const shipped = [{ id: "old", type: "chat", input: ["text"] }];
  const read = () => shipped;
  assert.deepEqual(catalog({ acme: "acme" }, read).providers.acme.map((r) => r.id), ["old"]);
  shipped.push({ id: "new", type: "chat", input: ["text", "image"] }, { id: "painter", type: "image" }, { id: "off", type: "chat", enabled: false });
  assert.deepEqual(catalog({ acme: "acme" }, read).providers.acme.map((r) => r.id), ["new", "old"]);
});
