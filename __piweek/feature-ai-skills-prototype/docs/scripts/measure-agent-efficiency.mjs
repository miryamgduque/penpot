#!/usr/bin/env node
// Calls-per-round / rounds-per-deliverable extractor for agent conversations.
//
// Reads `profile_agent_chat` rows (transit-verbose jsonb `data` column:
// {"~:usage": {...}, "~:history": [...]}) and prints, per conversation:
// rounds (assistant messages that called tools), total tool calls,
// calls/round, tool mix, and the spend meter.
//
// Usage (host, repo root):
//   docker exec penpotdev-infra-postgres-1 psql -U penpot -d penpot -At -F $'\x1f' \
//     -c "select id, title, data from profile_agent_chat order by updated_at desc limit 5" \
//     | node __piweek/feature-ai-skills-prototype/docs/scripts/measure-agent-efficiency.mjs
//
// The -F $'\x1f' unit separator keeps the JSON (full of | and ,) intact.

import { createInterface } from "node:readline";

// transit-verbose: keys and keyword values carry a "~:" prefix.
const K = (s) => (typeof s === "string" ? s.replace(/^~[:u]/, "") : s);

function decode(node) {
  if (Array.isArray(node)) return node.map(decode);
  if (node && typeof node === "object") {
    const m = {};
    for (const [k, v] of Object.entries(node)) m[K(k)] = decode(v);
    return m;
  }
  return K(node);
}

// $/Mtok list prices (input, output); cache read ≈ 0.1×, write ≈ 1.25× input.
const PRICING = {
  "claude-sonnet-5": [3, 15],
  "claude-opus-4-8": [5, 25],
  "claude-haiku-4-5-20251001": [1, 5],
};

function cost(usage, model) {
  const p = PRICING[model];
  if (!p || !usage) return null;
  const [inp, out] = p;
  return +(((usage["input-tokens"] || 0) * inp +
    (usage["cache-read-tokens"] || 0) * inp * 0.1 +
    (usage["cache-write-tokens"] || 0) * inp * 1.25 +
    (usage["output-tokens"] || 0) * out) / 1e6).toFixed(3);
}

function analyze(id, title, dataJson, model) {
  let data;
  try {
    data = decode(JSON.parse(dataJson));
  } catch (e) {
    return { id, title, error: "unparsable: " + e.message };
  }
  const msgs = data.history || [];
  let rounds = 0, calls = 0, assistantMsgs = 0, userMsgs = 0;
  const tools = new Map();
  for (const m of msgs) {
    if (!m || typeof m !== "object") continue;
    if (m.role === "user" && !m["compacted?"]) userMsgs++;
    if (m.role !== "assistant") continue;
    assistantMsgs++;
    const tc = m["tool-calls"] || [];
    if (tc.length > 0) rounds++;
    calls += tc.length;
    for (const c of tc) tools.set(c.name, (tools.get(c.name) || 0) + 1);
  }
  return {
    id, title: title.slice(0, 60),
    userMsgs, assistantMsgs, rounds, calls,
    callsPerRound: rounds ? +(calls / rounds).toFixed(2) : 0,
    usage: data.usage || null,
    estCostUsd: cost(data.usage, model),
    topTools: [...tools.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8)
      .map(([n, c]) => `${n}×${c}`).join(" "),
  };
}

const MODEL = process.env.MODEL || "claude-sonnet-5";
const SEP = "\x1f";
let buf = "";
const rl = createInterface({ input: process.stdin });
const rows = [];
function flush() {
  const parts = buf.split(SEP);
  if (parts.length >= 3) {
    const jsonPart = parts.slice(2).join(SEP);
    try {
      JSON.parse(jsonPart);
      rows.push(analyze(parts[0], parts[1], jsonPart, MODEL));
      buf = "";
    } catch { /* multi-line JSON still accumulating */ }
  }
}
rl.on("line", (line) => { buf += (buf ? "\n" : "") + line; flush(); });
rl.on("close", () => {
  if (buf.trim()) flush();
  for (const r of rows) console.log(JSON.stringify(r, null, 1));
});
