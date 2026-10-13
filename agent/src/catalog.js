/**
 * What each model can do: the capability schema, and the catalog read from
 * the provider layer.
 *
 * One record per model, the same for every provider:
 *
 *   {
 *     provider,        Penpot's id of the provider
 *     id, name,
 *     vision,          reads images
 *     tools,           calls tools
 *     reasoning,       thinks before answering
 *     promptCaching,   reuses a prefix of the prompt at a lower price
 *     contextLimit,    tokens it can take in
 *     outputLimit,     tokens it can answer with
 *     price,           { input, output, cacheRead, cacheWrite } in US dollars
 *                      per million tokens
 *   }
 *
 * A capability is true, false or null. Null means UNKNOWN: the source does
 * not say, and nothing here guesses. Whoever reads a record decides what
 * unknown means for them — the agent does not send images to a model whose
 * vision is unknown, and does not offer a model whose tool use is.
 *
 * The catalog is not kept by hand. It is what the installed provider layer
 * (pi-ai, through the harness) describes, so a model a provider ships shows
 * up with the next update of that layer and no change here.
 */
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

/**
 * The provider layer's own catalog module: the copy of pi-ai the harness's
 * model plugin runs on, found next to that plugin. Reading the same copy is
 * the point — a second one, installed for this file, could describe models
 * the harness cannot serve.
 */
async function providerLayer() {
  const entry = "@earendil-works/pi-ai/dist/providers/all.js";
  let dir = dirname(fileURLToPath(import.meta.resolve("@deepseek-ai/dsh-llm-pi-ai")));
  for (;;) {
    const candidate = join(dir, "node_modules", entry);
    if (existsSync(candidate)) return import(pathToFileURL(candidate).href);
    const sibling = join(dir, entry);
    if (dir.endsWith("node_modules") && existsSync(sibling)) return import(pathToFileURL(sibling).href);
    const up = dirname(dir);
    if (up === dir) throw new Error("the provider layer's catalog was not found next to the harness's model plugin");
    dir = up;
  }
}

const { getBuiltinModels } = await providerLayer();

export const SCHEMA_VERSION = 1;

/** The fields of a record that are capabilities: true, false or null. */
export const CAPABILITIES = ["vision", "tools", "reasoning", "promptCaching"];

const known = (value) => (typeof value === "boolean" ? value : null);

const tokens = (value) => (typeof value === "number" && Number.isFinite(value) && value > 0 ? value : null);

function priceOf(cost) {
  if (cost === null || typeof cost !== "object") return null;
  const parts = ["input", "output", "cacheRead", "cacheWrite"].map((key) => cost[key]);
  if (!parts.every((part) => typeof part === "number" && Number.isFinite(part) && part >= 0)) return null;
  // a price of nothing for both directions is a price nobody stated
  if (cost.input === 0 && cost.output === 0) return null;
  return { input: cost.input, output: cost.output, cacheRead: cost.cacheRead, cacheWrite: cost.cacheWrite };
}

function cachingOf(model) {
  if (model.promptCache !== undefined && model.promptCache !== null) return true;
  // a price for reading the cache says there is one
  if (typeof model.cost?.cacheRead === "number" && model.cost.cacheRead > 0) return true;
  // a stated price list with nothing for the cache says there is none; with
  // no price stated at all, nothing is said
  return priceOf(model.cost) === null ? null : false;
}

/**
 * The record of one model as the provider layer describes it.
 * @param {string} provider Penpot's id of the provider
 * @param {object} model the provider layer's description
 */
export function describe(provider, model) {
  return {
    provider,
    id: model.id,
    name: typeof model.name === "string" && model.name !== "" ? model.name : model.id,
    vision: Array.isArray(model.input) ? model.input.includes("image") : null,
    // the provider layer's chat catalog holds only models that call tools
    tools: model.type === "chat" ? true : model.type === undefined ? null : false,
    reasoning: known(model.reasoning),
    promptCaching: cachingOf(model),
    contextLimit: tokens(model.contextWindow),
    outputLimit: tokens(model.maxTokens),
    price: priceOf(model.cost),
  };
}

/**
 * Why `record` is not a valid capability record, or undefined.
 * @param {unknown} record
 */
export function problem(record) {
  if (record === null || typeof record !== "object") return "a model record is an object";
  for (const key of ["provider", "id", "name"]) {
    if (typeof record[key] !== "string" || record[key] === "") return `needs a \`${key}\``;
  }
  for (const key of CAPABILITIES) {
    if (!(record[key] === true || record[key] === false || record[key] === null)) {
      return `${key} is true, false, or null for unknown`;
    }
  }
  for (const key of ["contextLimit", "outputLimit"]) {
    if (!(record[key] === null || (Number.isInteger(record[key]) && record[key] > 0))) {
      return `${key} is a number of tokens, or null for unknown`;
    }
  }
  if (record.price !== null) {
    if (typeof record.price !== "object") return "price is {input, output, cacheRead, cacheWrite}, or null for unknown";
    for (const key of ["input", "output", "cacheRead", "cacheWrite"]) {
      if (!(typeof record.price[key] === "number" && record.price[key] >= 0)) return `price.${key} is a number, in dollars per million tokens`;
    }
  }
  return undefined;
}

/** The capabilities and limits of `record` that are unknown. */
export function unknowns(record) {
  return [...CAPABILITIES, "contextLimit", "outputLimit", "price"].filter((key) => record[key] === null);
}

/**
 * The catalog: Penpot provider id → the records of its models, by name.
 * @param {Record<string, string>} routes Penpot provider id → the provider layer's route
 * @param {(route: string) => object[]} [models] reads a route's models; the installed catalog by default
 */
export function catalog(routes, models = getBuiltinModels) {
  const providers = {};
  for (const [provider, route] of Object.entries(routes)) {
    providers[provider] = models(route)
      .filter((model) => model.type === undefined || model.type === "chat")
      .filter((model) => model.enabled !== false)
      .map((model) => describe(provider, model))
      .sort((a, b) => a.name.localeCompare(b.name));
  }
  return { schema: SCHEMA_VERSION, providers };
}
