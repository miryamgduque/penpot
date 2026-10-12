// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

/**
 * The MCP servers a turn brings with it.
 *
 * A user registers servers in Penpot; the backend keeps them (their tokens
 * encrypted) and hands the enabled ones over with each turn, in a header,
 * so neither the list nor the tokens pass through the browser. Here they are
 * checked once more before the harness connects to any of them.
 */
import { Client, StreamableHTTPClientTransport } from "@modelcontextprotocol/client";

import { assertPublicUrl, guardOrigin } from "./netguard.js";

export const MAX_MCP_SERVERS = 8;

const SERVER_NAME = /^[A-Za-z0-9_-]{1,32}$/;
// a header a caller could use to speak for this service, or to break framing
const FORBIDDEN_HEADERS = new Set(["host", "content-length", "transfer-encoding", "connection", "cookie"]);

function decode(header) {
  if (typeof header !== "string" || header === "") return [];
  try {
    const parsed = JSON.parse(Buffer.from(header, "base64").toString("utf8"));
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function cleanHeaders(headers) {
  const out = {};
  if (headers === null || typeof headers !== "object") return out;
  for (const [name, value] of Object.entries(headers)) {
    if (typeof value !== "string" || /[\r\n]/.test(value) || !/^[A-Za-z0-9-]+$/.test(name)) continue;
    if (FORBIDDEN_HEADERS.has(name.toLowerCase())) continue;
    out[name] = value;
  }
  return out;
}

/**
 * The servers of a turn, from the header the backend sent → `{servers,
 * refused}`. A server that fails a check is left out and named in `refused`
 * with the reason; it never fails the turn.
 */
export async function turnMcpServers(header, { allowPrivate = false, resolve } = {}) {
  const servers = [];
  const refused = [];
  const seen = new Set();
  for (const entry of decode(header).slice(0, MAX_MCP_SERVERS)) {
    const name = typeof entry?.name === "string" ? entry.name : "";
    if (!SERVER_NAME.test(name) || seen.has(name)) {
      refused.push({ name, reason: "the name is not usable" });
      continue;
    }
    try {
      const url = await assertPublicUrl(entry.url, { allowPrivate, resolve });
      servers.push({
        name,
        url: url.toString(),
        headers: cleanHeaders(entry.headers),
        // the tools of this server the user switched off
        disabledTools: Array.isArray(entry.disabledTools) ? entry.disabledTools.filter((tool) => typeof tool === "string") : [],
      });
      seen.add(name);
    } catch (cause) {
      refused.push({ name, reason: cause.message });
    }
  }
  return { servers, refused };
}

const LIST_TIMEOUT_MS = 15000;

/**
 * The tools one server offers → `[{name, description}]`, for the user to see
 * and switch off. The address is held to the same rules as in a turn. Rejects
 * with a message fit to show when the server cannot be reached or listed.
 */
export async function listMcpTools(entry, { allowPrivate = false, resolve } = {}) {
  const url = await assertPublicUrl(entry?.url, { allowPrivate, resolve });
  const release = allowPrivate ? () => {} : guardOrigin(url.toString());
  const client = new Client({ name: "penpot-agent", version: "1.0.0" });
  try {
    const transport = new StreamableHTTPClientTransport(url, { requestInit: { headers: cleanHeaders(entry.headers) } });
    await client.connect(transport, { timeout: LIST_TIMEOUT_MS });
    const { tools } = await client.listTools(undefined, { timeout: LIST_TIMEOUT_MS });
    return (tools ?? []).map((tool) => ({ name: String(tool.name), description: String(tool.description ?? "") }));
  } catch (cause) {
    throw new Error(`the server could not be reached or did not list its tools (${cause?.message ?? "no detail"})`);
  } finally {
    await client.close().catch(() => {});
    release();
  }
}
