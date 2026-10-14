// Runs the stand-in OpenAI-compatible server on a port, for trying a
// registered endpoint by hand: node test/fake-openai-cli.js 6069
// It answers every request with a line saying what it was asked, or calls a
// tool when the last message says "call <tool> <json>".

import { startFakeOpenAI } from "./fake-openai.js";

const port = Number(process.argv[2] ?? 6069);
const said = (message) => (typeof message?.content === "string" ? message.content : (message?.content ?? []).map((part) => part.text ?? "").join(""));

const server = await startFakeOpenAI((request) => {
  const messages = request.body.messages ?? [];
  const last = messages.at(-1);
  const text = said(last);
  const call = last?.role === "user" ? /call (\w+) (\{.*\})\s*$/s.exec(text) : null;
  if (call) return { tool: { name: call[1], input: JSON.parse(call[2]) } };
  return { text: `Local stand-in here (${request.body.model}). Tools declared: ${(request.body.tools ?? []).length}. You said: ${text.slice(0, 200)}` };
}, port);
console.log(`stand-in OpenAI-compatible server on ${server.baseURL}`);
