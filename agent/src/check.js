/**
 * The provider verification check: the same three questions put to any
 * model before it is offered to users, so that none is offered that fails
 * without a word.
 *
 *   text    it answers a plain question
 *   tools   it calls a tool it was given, with the arguments asked for, and
 *           goes on once the tool answers
 *   vision  it reads an image — asked only of a model whose record says it
 *           does; a model that does not claim it is not asked
 *
 * Each runs as a real turn through the harness, on the path the agent uses,
 * with the key the caller brings. A check passes or fails with what was seen.
 */
import { deflateSync, crc32 } from "node:zlib";

const WORD = "pineapple";

const PROBE_TOOL = {
  name: "record_measurement",
  description: "Records one measurement. Call it when asked to record something.",
  inputSchema: {
    type: "object",
    properties: {
      label: { type: "string", description: "what was measured" },
      value: { type: "number", description: "the number measured" },
    },
    required: ["label", "value"],
  },
};

/** A PNG of one flat color, `size` pixels square, made here so the check ships no file. */
export function flatPng([r, g, b], size = 64) {
  const chunk = (type, data) => {
    const head = Buffer.alloc(8);
    head.writeUInt32BE(data.length, 0);
    head.write(type, 4, "ascii");
    const tail = Buffer.alloc(4);
    tail.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])) >>> 0, 0);
    return Buffer.concat([head, data, tail]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header.set([8, 2, 0, 0, 0], 8); // 8 bits, RGB
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(size * 3, Buffer.from([r, g, b]))]);
  const pixels = deflateSync(Buffer.concat(Array.from({ length: size }, () => row)));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", pixels),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

const said = (events) =>
  events
    .filter((event) => event.type === "text-delta")
    .map((event) => event.text)
    .join("");

const failed = (cause) => ({ pass: false, saw: String(cause?.message ?? cause).slice(0, 300) });

/**
 * Runs the check on one model.
 * @param {object} harness the harness the agent's turns run on
 * @param {{provider: string, model: string, apiKey: string, profileId: string}} who
 * @returns {Promise<{provider, model, text, tools, vision, pass}>} each of
 * text, tools and vision is `{pass, saw}`; vision is `{skipped: true}` for a
 * model that does not claim it. `pass` is whether every check that ran passed.
 */
export async function checkModel(harness, { provider, model, apiKey, profileId }) {
  const record = harness.capabilities(provider, model);
  const base = { profileId, provider, model, apiKey, system: "You are being checked. Follow the instruction exactly and briefly." };
  const session = (name) => `check-${name}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  const turn = async (request, callTool = async () => ({ content: "", isError: false })) => {
    const events = [];
    await harness.runTurn({ ...base, tools: [], ...request }, { emit: (event) => events.push(event), callTool });
    return events;
  };

  const text = await (async () => {
    try {
      const answer = said(await turn({ sessionId: session("text"), text: `Reply with the single word ${WORD} and nothing else.` }));
      return { pass: answer.toLowerCase().includes(WORD), saw: answer.slice(0, 200) };
    } catch (cause) {
      return failed(cause);
    }
  })();

  const tools = await (async () => {
    try {
      const calls = [];
      const events = await turn(
        {
          sessionId: session("tools"),
          tools: [PROBE_TOOL],
          text: 'Record a measurement with the label "width" and the value 42 using the tool, then reply with the single word done.',
        },
        async (call) => {
          calls.push(call.input);
          return { content: '{"recorded":true}', isError: false };
        },
      );
      const [first] = calls;
      const right = calls.length >= 1 && first?.label === "width" && Number(first?.value) === 42;
      const went = said(events).trim().length > 0;
      return {
        pass: right && went,
        saw:
          calls.length === 0
            ? `no tool call; it said: ${said(events).slice(0, 160)}`
            : `called with ${JSON.stringify(first).slice(0, 120)}; then said: ${said(events).slice(0, 80)}`,
      };
    } catch (cause) {
      return failed(cause);
    }
  })();

  const vision =
    record.vision !== true
      ? { skipped: true, saw: record.vision === false ? "the model does not read images" : "not known to read images" }
      : await (async () => {
          try {
            const image = { mediaType: "image/png", data: flatPng([220, 20, 20]).toString("base64") };
            const answer = said(
              await turn({
                sessionId: session("vision"),
                images: [image],
                text: "The image is one flat color. Reply with the name of that color as a single word: red, green, blue or yellow.",
              }),
            );
            return { pass: /\bred\b/i.test(answer), saw: answer.slice(0, 200) };
          } catch (cause) {
            return failed(cause);
          }
        })();

  return { provider, model, text, tools, vision, pass: text.pass && tools.pass && (vision.skipped === true || vision.pass) };
}
