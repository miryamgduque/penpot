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
 * range, no metadata endpoint.
 *
 * Checking the URL once is not enough: the server can answer with a redirect
 * to an inside address, and the name can resolve differently when the
 * connection is made. So requests to a user's server also go through
 * `guardOrigin`: redirects are refused, and the address is checked again at
 * the moment the socket connects.
 */
import { lookup as lookupCallback } from "node:dns";
import { lookup } from "node:dns/promises";
import { BlockList, isIP } from "node:net";

import { Agent } from "undici";

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

// --- Every request to a user's server
//
// The MCP client makes its requests with the global `fetch` and offers no way
// to hand it another. So the global `fetch` is wrapped once, and the wrapper
// acts by destination: a request to an origin a user registered is made
// without following redirects, on connections that only open to public
// addresses. Requests to anywhere else pass through untouched.

/** origin → how many open conversations use a server there */
const guardedOrigins = new Map();

function publicOnlyLookup(allowAddress) {
  return (hostname, options, callback) => {
    lookupCallback(hostname, { ...options, all: true }, (error, addresses) => {
      if (error) return callback(error);
      if (addresses.length === 0 || !addresses.every(({ address }) => allowAddress(address))) {
        return callback(new Error("the host is private or blocked by this Penpot instance"));
      }
      if (options?.all) return callback(null, addresses);
      callback(null, addresses[0].address, addresses[0].family);
    });
  };
}

/**
 * `fetch`, guarded for the origins in `origins`: no redirect is followed and
 * the socket only connects to an address `allowAddress` accepts.
 */
export function guardedFetch(original, { origins = guardedOrigins, allowAddress = isPublicAddress } = {}) {
  const dispatcher = new Agent({ connect: { lookup: publicOnlyLookup(allowAddress) } });
  return (input, init) => {
    let origin;
    try {
      origin = new URL(typeof input === "string" || input instanceof URL ? input : input.url).origin;
    } catch {
      return original(input, init);
    }
    if (!origins.has(origin)) return original(input, init);
    return original(input, { ...init, redirect: "error", dispatcher });
  };
}

let installed = false;

/**
 * From now until the returned function is called, requests to the origin of
 * `url` are guarded. Installs the wrapper over the global `fetch` on first
 * use.
 */
export function guardOrigin(url) {
  if (!installed) {
    globalThis.fetch = guardedFetch(globalThis.fetch);
    installed = true;
  }
  const { origin } = new URL(url);
  guardedOrigins.set(origin, (guardedOrigins.get(origin) ?? 0) + 1);
  let released = false;
  return () => {
    if (released) return;
    released = true;
    const left = (guardedOrigins.get(origin) ?? 1) - 1;
    if (left <= 0) guardedOrigins.delete(origin);
    else guardedOrigins.set(origin, left);
  };
}
