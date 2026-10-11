// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

/**
 * Which addresses this service may call on a user's behalf.
 *
 * An MCP server URL is typed by a user, and this service sits inside the
 * deployment's network. So the URL has to be https and every address its host
 * resolves to has to be a public one: no loopback, no private or link-local
 * range, no metadata endpoint. Known residual risk: the name is resolved here
 * and again when the connection is made, so a DNS answer that changes in
 * between is not caught.
 */
import { lookup } from "node:dns/promises";
import { BlockList, isIP } from "node:net";

const blocked = new BlockList();
for (const [network, prefix] of [
  ["0.0.0.0", 8],
  ["10.0.0.0", 8],
  ["100.64.0.0", 10],
  ["127.0.0.0", 8],
  ["169.254.0.0", 16],
  ["172.16.0.0", 12],
  ["192.0.0.0", 24],
  ["192.0.2.0", 24],
  ["192.168.0.0", 16],
  ["198.18.0.0", 15],
  ["198.51.100.0", 24],
  ["203.0.113.0", 24],
  ["224.0.0.0", 3],
]) {
  blocked.addSubnet(network, prefix, "ipv4");
}
for (const [network, prefix] of [
  ["::", 127],
  ["fc00::", 7],
  ["fe80::", 10],
  ["ff00::", 8],
  // transition mechanisms that can carry an IPv4 destination inside
  ["64:ff9b::", 96],
  ["2002::", 16],
  ["2001::", 32],
]) {
  blocked.addSubnet(network, prefix, "ipv6");
}

/** Whether `address` is one a user-supplied URL may reach. */
export function isPublicAddress(address) {
  const family = isIP(address);
  if (family === 0) return false;
  // An IPv4 address written as IPv6 (::ffff:10.0.0.1, ::ffff:a00:1) is judged
  // as the IPv4 address it is: the block list matches those against its IPv4
  // rules itself, so they are not listed as a range of their own — which
  // would block every IPv4 address.
  return !blocked.check(address, family === 4 ? "ipv4" : "ipv6");
}

/**
 * Resolves with the URL when it is https and its host is public; rejects with
 * a message fit to show the user otherwise. `allowPrivate` lifts both rules,
 * for a developer's own machine.
 */
export async function assertPublicUrl(raw, { allowPrivate = false, resolve = lookup } = {}) {
  let url;
  try {
    url = new URL(raw);
  } catch {
    throw new Error("the address is not a URL");
  }
  if (allowPrivate) {
    if (url.protocol !== "https:" && url.protocol !== "http:") throw new Error("the address must be http(s)");
    return url;
  }
  if (url.protocol !== "https:") throw new Error("the address must be https");
  if (url.username !== "" || url.password !== "") throw new Error("the address must not carry credentials");
  const host = url.hostname.replace(/^\[|\]$/g, "");
  const addresses = isIP(host) !== 0 ? [{ address: host }] : await resolve(host, { all: true }).catch(() => []);
  if (addresses.length === 0) throw new Error("the host does not resolve");
  if (!addresses.every(({ address }) => isPublicAddress(address))) {
    throw new Error("the host is private or blocked by this Penpot instance");
  }
  return url;
}
