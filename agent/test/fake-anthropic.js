// A stand-in for the Anthropic Messages API: records what each request
// carried and replies with a scripted stream.

import { createServer } from "node:http";

function sse(res, event, data) {
  res.write(`event: ${event}\ndata: ${JSON.stringify({ type: event, ...data })}\n\n`);
}

function replyText(res, text, inputTokens = 10) {
  sse(res, "message_start", {
    message: { id: "msg_1", type: "message", role: "assistant", model: "m", content: [], usage: { input_tokens: inputTokens, output_tokens: 0 } },
  });
  sse(res, "content_block_start", { index: 0, content_block: { type: "text", text: "" } });
  for (const piece of text.match(/.{1,6}/gs) ?? []) {
    sse(res, "content_block_delta", { index: 0, delta: { type: "text_delta", text: piece } });
  }
  sse(res, "content_block_stop", { index: 0 });
  sse(res, "message_delta", { delta: { stop_reason: "end_turn" }, usage: { output_tokens: Math.max(5, Math.ceil(text.length / 4)) } });
  sse(res, "message_stop", {});
  res.end();
}

function replyToolUse(res, name, input) {
  sse(res, "message_start", {
    message: { id: "msg_2", type: "message", role: "assistant", model: "m", content: [], usage: { input_tokens: 10, output_tokens: 0 } },
  });
  sse(res, "content_block_start", { index: 0, content_block: { type: "tool_use", id: "toolu_1", name, input: {} } });
  sse(res, "content_block_delta", { index: 0, delta: { type: "input_json_delta", partial_json: JSON.stringify(input) } });
  sse(res, "content_block_stop", { index: 0 });
  sse(res, "message_delta", { delta: { stop_reason: "tool_use" }, usage: { output_tokens: 5 } });
  sse(res, "message_stop", {});
  res.end();
}

/**
 * `script(request, n)` decides the reply to the n-th request:
 * `{text}` or `{tool: {name, input}}` or `{delayMs, text}`.
 */
export async function startFakeAnthropic(script, port = 0) {
  const requests = [];
  const server = createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", async () => {
      const body = raw ? JSON.parse(raw) : {};
      const record = { key: req.headers["x-api-key"], body, aborted: false };
      requests.push(record);
      res.on("close", () => {
        if (!res.writableEnded) record.aborted = true;
      });
      const step = script(record, requests.length - 1);
      res.writeHead(200, { "content-type": "text/event-stream" });
      if (step.delayMs) await new Promise((r) => setTimeout(r, step.delayMs));
      if (res.destroyed) return;
      if (step.tool) replyToolUse(res, step.tool.name, step.tool.input);
      else replyText(res, step.text ?? "ok", step.countTokens ? Math.ceil(raw.length / 4) : 10);
    });
  });
  await new Promise((r) => server.listen(port, "127.0.0.1", r));
  return {
    requests,
    baseURL: `http://127.0.0.1:${server.address().port}`,
    close: () => new Promise((r) => server.close(r)),
  };
}
