// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

import { CredentialProvider } from "@deepseek-ai/dsh-credentials";

/**
 * The reference a provider route resolves its key through. One name per
 * provider; the VALUE depends on which conversation is asking.
 */
export function keyRef(provider) {
  return `PENPOT_KEY_${provider.toUpperCase().replace(/[^A-Z0-9]/g, "_")}`;
}

/**
 * Credentials that never touch disk or the process environment.
 *
 * The harness resolves a key by reference name only: it has no notion of who
 * is asking. Penpot attaches a key to each turn instead, so this provider
 * answers from the conversation that started the current model call, which
 * the harness tracks across awaits. A call made outside any conversation, or
 * for a conversation with no key, resolves to nothing — the request then
 * fails rather than borrowing someone else's key.
 */
export class TurnCredentials extends CredentialProvider {
  static inject = ["agents"];

  // a plain field: the plugin framework hands out proxies of a service, and
  // private fields are unreachable through a proxy
  keysBySession = new Map();

  /** Sets the keys the given conversation may use, replacing earlier ones. */
  setKeys(sessionId, keys) {
    this.keysBySession.set(sessionId, new Map(Object.entries(keys)));
  }

  clearKeys(sessionId) {
    this.keysBySession.delete(sessionId);
  }

  async resolve(ref) {
    const agent = this.ctx.agents.currentInitiator();
    if (agent === undefined) return undefined;
    const value = this.keysBySession.get(String(agent.id))?.get(String(ref));
    return value === undefined ? undefined : { value, source: "penpot" };
  }

  async describe(ref) {
    return { configured: (await this.resolve(ref)) !== undefined, writable: false };
  }

  async set() {
    throw new Error("credentials are supplied per turn by Penpot");
  }

  async unset() {
    throw new Error("credentials are supplied per turn by Penpot");
  }

  async readRecord() {
    return undefined;
  }

  async describeRecord() {
    return { configured: false, writable: false };
  }

  async listRecords() {
    return [];
  }

  async modifyRecord() {
    throw new Error("credential records are not supported");
  }

  async deleteRecord() {}
}
