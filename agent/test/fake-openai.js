// A stand-in for an OpenAI-compatible server, the kind a local runtime
// exposes: records what each request carried and replies with a scripted
// chat-completions stream.

import { createServer } from "node:http";

const chunk = (res, data) => res.write(`data: ${JSON.stringify({ id: "chatcmpl-1", object: "chat.completion.chunk", model: "m", ...data })}\n\n`);

function finish(res, reason, text) {
  chunk(res, { choices: [{ index: 0, delta: {}, finish_reason: reason }] });
  chunk(res, { choices: [], usage: { prompt_tokens: 10, completion_tokens: Math.max(5, Math.ceil(text.length / 4)), total_tokens: 20 } });
  res.write("data: [DONE]\n\n");
  res.end();
}

function replyText(res, text) {
  chunk(res, { choices: [{ index: 0, delta: { role: "assistant", content: "" }, finish_reason: null }] });
  for (const piece of text.match(/.{1,6}/gs) ?? []) chunk(res, { choices: [{ index: 0, delta: { content: piece }, finish_reason: null }] });
  finish(res, "stop", text);
}

function replyToolCall(res, name, input) {
  chunk(res, {
    choices: [
      {
        index: 0,
        delta: { role: "assistant", content: null, tool_calls: [{ index: 0, id: "call_1", type: "function", function: { name, arguments: "" } }] },
        finish_reason: null,
      },
    ],
  });
  chunk(res, { choices: [{ index: 0, delta: { tool_calls: [{ index: 0, function: { arguments: JSON.stringify(input) } }] }, finish_reason: null }] });
  finish(res, "tool_calls", "");
}

/**
 * `script(request, n)` decides the reply to the n-th request: `{text}` or
 * `{tool: {name, input}}`. A request is recorded with its path, its bearer
 * key and its body.
 */
export async function startFakeOpenAI(script, port = 0) {
  const requests = [];
  const server = createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      const body = raw ? JSON.parse(raw) : {};
      const record = { path: req.url, key: String(req.headers.authorization ?? "").replace(/^Bearer /, ""), body };
      requests.push(record);
      const step = script(record, requests.length - 1);
      res.writeHead(200, { "content-type": "text/event-stream" });
      if (step.tool) replyToolCall(res, step.tool.name, step.tool.input);
      else replyText(res, step.text ?? "ok");
    });
  });
  await new Promise((r) => server.listen(port, "127.0.0.1", r));
  return {
    requests,
    baseURL: `http://127.0.0.1:${server.address().port}/v1`,
    close: () => new Promise((r) => server.close(r)),
  };
}
