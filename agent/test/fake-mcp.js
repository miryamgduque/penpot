// A stand-in MCP server over Streamable HTTP, answering each JSON-RPC
// request with a JSON body. One tool, `echo`, plus whatever the test adds.
import { createServer } from "node:http";

export async function startFakeMcp({ tools = [], instructions } = {}) {
  const seen = [];
  const all = [
    {
      name: "echo",
      description: "Says back what it is told.",
      inputSchema: { type: "object", properties: { text: { type: "string" } }, required: ["text"] },
      call: (args) => ({ content: [{ type: "text", text: `echo: ${args.text}` }] }),
    },
    ...tools,
  ];

  function answer(message) {
    switch (message.method) {
      case "initialize":
        return {
          protocolVersion: message.params?.protocolVersion ?? "2025-06-18",
          capabilities: { tools: {} },
          serverInfo: { name: "fake-mcp", version: "1.0.0" },
          ...(instructions ? { instructions } : {}),
        };
      case "tools/list":
        return { tools: all.map(({ call: _call, ...tool }) => tool) };
      case "tools/call": {
        const tool = all.find((candidate) => candidate.name === message.params?.name);
        return tool ? tool.call(message.params?.arguments ?? {}) : { content: [{ type: "text", text: "no such tool" }], isError: true };
      }
      case "ping":
        return {};
      default:
        return undefined;
    }
  }

  const server = createServer((req, res) => {
    if (req.method !== "POST") {
      res.writeHead(405).end();
      return;
    }
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      let message;
      try {
        message = JSON.parse(raw);
      } catch {
        res.writeHead(400).end();
        return;
      }
      seen.push({ method: message.method, params: message.params, authorization: req.headers.authorization });
      // a notification has no id and gets no body
      if (message.id === undefined) {
        res.writeHead(202).end();
        return;
      }
      const result = answer(message);
      res.writeHead(200, { "content-type": "application/json" });
      res.end(
        JSON.stringify(
          result === undefined
            ? { jsonrpc: "2.0", id: message.id, error: { code: -32601, message: "method not found" } }
            : { jsonrpc: "2.0", id: message.id, result },
        ),
      );
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return {
    seen,
    url: `http://127.0.0.1:${server.address().port}/mcp`,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}
