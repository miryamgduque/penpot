/**
 * RPC bridge between the plugin iframe UI and the plugin context script.
 * Requests go up via window.parent.postMessage; results and events come back
 * through window "message" events (relayed by penpot.ui.sendMessage).
 */

import type { DisabledByScope, EffectiveSkill, Scope } from "@penpot/skills-core";

export interface ScopeSources {
  platform: string[];
  org: string[];
  project: string[];
  file: string[];
}

export interface SkillsPayload {
  fileSkillSources: string[];
  scopes: ScopeSources;
  disabled: Required<DisabledByScope>;
  /** Scopes managed natively (dashboard/DB) — read-only in this panel. */
  managed: Scope[];
  effective: EffectiveSkill[];
}

export interface PaletteEntry {
  name: string;
  value: string;
}

export interface DesignContext {
  fileName: string;
  pageName: string;
  selection: unknown[];
  pageShapes: unknown[];
}

export interface TriggeredSkillEvent {
  skill: { name: string; enforcement: string; description: string };
  reason: string;
  shape?: { id: string; name: string };
}

export interface Violation {
  id: string;
  rule: string;
  shapeId: string;
  shapeName: string;
  reason: string;
}

type PluginEvent =
  | {
      type: "init";
      theme: string;
      context: DesignContext;
      skills: SkillsPayload;
      violations: Violation[];
    }
  | ({ type: "skill-triggered" } & TriggeredSkillEvent)
  | { type: "skills-change"; skills: SkillsPayload }
  | { type: "violations-change"; violations: Violation[] }
  | { type: "selection-change"; selection: { id: string; name: string; type: string }[] }
  | { type: "theme-change"; theme: string };

type Pending = { resolve: (v: unknown) => void; reject: (e: Error) => void };

const pending = new Map<number, Pending>();
const eventListeners = new Set<(e: PluginEvent) => void>();
let nextId = 1;

window.addEventListener("message", (event) => {
  const msg = event.data;
  if (!msg || msg.source !== "plugin") return;

  if (msg.type === "rpc-result" || msg.type === "rpc-error") {
    const p = pending.get(msg.id);
    if (!p) return;
    pending.delete(msg.id);
    if (msg.type === "rpc-result") p.resolve(msg.result);
    else {
      const err = new Error(msg.error) as Error & { rule?: string };
      err.rule = msg.rule;
      p.reject(err);
    }
    return;
  }

  for (const listener of eventListeners) listener(msg as PluginEvent);
});

export function call<T = unknown>(
  op: string,
  payload?: Record<string, unknown>,
  opts?: { timeoutMs?: number },
): Promise<T> {
  const id = nextId++;
  const timeoutMs = opts?.timeoutMs ?? 30_000;
  return new Promise<T>((resolve, reject) => {
    pending.set(id, { resolve: resolve as (v: unknown) => void, reject });
    window.parent.postMessage({ source: "ui", id, op, payload }, "*");
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(
          new Error(
            `Plugin call timed out after ${Math.round(timeoutMs / 1000)}s: ${op}. ` +
              `NOTE: the operation may still have completed in Penpot — read the current ` +
              `state before retrying, or you may duplicate work.`,
          ),
        );
      }
    }, timeoutMs);
  });
}

export function onPluginEvent(listener: (e: PluginEvent) => void): () => void {
  eventListeners.add(listener);
  return () => eventListeners.delete(listener);
}

export function announceReady(): void {
  window.parent.postMessage({ type: "ready" }, "*");
}
