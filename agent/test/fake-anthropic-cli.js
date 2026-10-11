// A scriptable stand-in model for exercising the whole path — panel, backend,
// service, tool bridge — without a provider key or any spend.
//
//   node test/fake-anthropic-cli.js [port]
//
// Type `call <tool> <json>` in the panel and the "model" calls that tool with
// that input, then reports the result it was given. Anything else is echoed.

import { startFakeAnthropic } from "./fake-anthropic.js";

function lastUserBlocks(body) {
  const last = body.messages?.at(-1);
  if (last === undefined) return [];
  return typeof last.content === "string" ? [{ type: "text", text: last.content }] : last.content;
}

const fake = await startFakeAnthropic((request) => {
  const blocks = lastUserBlocks(request.body);
  const result = blocks.find((b) => b.type === "tool_result");
  if (result !== undefined) {
    const text = typeof result.content === "string" ? result.content : JSON.stringify(result.content);
    return { text: `${result.is_error ? "The tool refused" : "The tool returned"}: ${text.slice(0, 600)}` };
  }
  const text = blocks.filter((b) => b.type === "text").map((b) => b.text).join("\n");
  const slow = /^slow\b/m.exec(text);
  if (slow) return { delayMs: 20000, text: "finally" };
  const call = /^call (\w+)\s*(\{.*\})?\s*$/m.exec(text);
  if (call) return { tool: { name: call[1], input: call[2] ? JSON.parse(call[2]) : {} } };
  return { text: `Stand-in model here. Tools declared: ${request.body.tools?.length ?? 0}. You said: ${text.split("\n").at(-1)}` };
}, Number(process.argv[2] ?? 6068));

console.log(`stand-in model listening on ${fake.baseURL}`);
