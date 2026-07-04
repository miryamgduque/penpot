/**
 * Penpot Skills — plugin context script.
 *
 * Runs inside Penpot's plugin sandbox. Responsibilities:
 *  - store/load file-scope skills in the file's *shared* pluginData, so any
 *    other tool (e.g. the MCP plugin) reads the same source of truth
 *  - resolve the four-scope cascade into the effective skill set
 *  - execute the chat agent's design tools, with the token-only-colors rule
 *    structurally enforced at the write path
 *  - watch the file for changes and surface triggered skills to the UI
 */

import type { Board, Shape, Text } from "@penpot/plugin-types";
import { PLATFORM_SKILLS, ORG_SKILLS, PROJECT_SKILLS } from "./skills/builtin";
import { FILE_SEED_SKILLS } from "./skills/seed";
import { parseSkills } from "./skills/parse";
import { resolveCascade } from "./skills/resolve";
import type { EffectiveSkill } from "./skills/types";
import {
  SkillViolationError,
  assertFillsAllowed,
  collectAllowedColors,
  guardPenpot,
  normalizeHex,
} from "./guard";

const NAMESPACE = "penpot-skills";
const SKILLS_KEY = "skills";
const DEFAULT_NAME_RE = /^(rectangle|ellipse|board|text|path|group|frame|circle|image|svg)\s*\d*$/i;

penpot.ui.open("Penpot Skills", `?theme=${penpot.theme}`, { width: 400, height: 620 });

/* ------------------------------------------------------------------ */
/* Skills storage & cascade                                            */
/* ------------------------------------------------------------------ */

function loadFileSkillSources(): string[] {
  const file = penpot.currentFile;
  if (!file) return [];
  const raw = file.getSharedPluginData(NAMESPACE, SKILLS_KEY);
  if (!raw) {
    file.setSharedPluginData(NAMESPACE, SKILLS_KEY, JSON.stringify(FILE_SEED_SKILLS));
    return [...FILE_SEED_SKILLS];
  }
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveFileSkillSources(sources: string[]): void {
  penpot.currentFile?.setSharedPluginData(NAMESPACE, SKILLS_KEY, JSON.stringify(sources));
}

function effectiveSkills(): EffectiveSkill[] {
  return resolveCascade([
    ...parseSkills(PLATFORM_SKILLS, "platform"),
    ...parseSkills(ORG_SKILLS, "org"),
    ...parseSkills(PROJECT_SKILLS, "project"),
    ...parseSkills(loadFileSkillSources(), "file"),
  ]);
}

function isRuleEnforced(name: string): boolean {
  return effectiveSkills().some((s) => s.name === name && s.enforcement === "enforced");
}

/* ------------------------------------------------------------------ */
/* Design reads                                                        */
/* ------------------------------------------------------------------ */

interface ShapeSummary {
  id: string;
  name: string;
  type: string;
  x: number;
  y: number;
  width: number;
  height: number;
  fills: unknown;
  children?: ShapeSummary[];
}

function summarizeShape(shape: Shape, depth = 0): ShapeSummary {
  const base: ShapeSummary = {
    id: shape.id,
    name: shape.name,
    type: shape.type,
    x: Math.round(shape.x),
    y: Math.round(shape.y),
    width: Math.round(shape.width),
    height: Math.round(shape.height),
    fills: Array.isArray(shape.fills)
      ? shape.fills.map((f) => ({ fillColor: f.fillColor, fillOpacity: f.fillOpacity }))
      : "mixed",
  };
  const children = (shape as { children?: Shape[] }).children;
  if (children && depth < 3) {
    base.children = children.slice(0, 30).map((c) => summarizeShape(c, depth + 1));
  }
  return base;
}

function designContext() {
  const page = penpot.currentPage;
  const root = page?.root;
  return {
    fileName: penpot.currentFile?.name ?? "(unsaved)",
    pageName: page?.name ?? "",
    selection: penpot.selection.map((s) => summarizeShape(s)),
    pageShapes: root && Array.isArray((root as { children?: Shape[] }).children)
      ? ((root as unknown as { children: Shape[] }).children ?? []).map((c) => summarizeShape(c, 2))
      : [],
  };
}

function colorTokens() {
  const lib = penpot.library.local;
  const tokens: { name: string; value?: string; set: string; active: boolean }[] = [];
  for (const set of lib.tokens.sets) {
    for (const token of set.tokens) {
      if ((token as { type?: string }).type !== "color") continue;
      tokens.push({
        name: token.name,
        value: token.resolvedValueString ?? (token as { value?: string }).value,
        set: set.name,
        active: set.active,
      });
    }
  }
  return {
    tokens,
    libraryColors: lib.colors.map((c) => ({ name: c.name, value: c.color })),
  };
}

function findColorToken(tokenName: string) {
  for (const set of penpot.library.local.tokens.sets) {
    if (!set.active) continue;
    for (const token of set.tokens) {
      if ((token as { type?: string }).type === "color" && token.name === tokenName) return token;
    }
  }
  return null;
}

function getShapeOrThrow(shapeId: string): Shape {
  const shape = penpot.currentPage?.getShapeById(shapeId);
  if (!shape) throw new Error(`Shape not found: ${shapeId}`);
  return shape;
}

/* ------------------------------------------------------------------ */
/* Design writes (enforced path)                                       */
/* ------------------------------------------------------------------ */

function setFill(args: { shapeId: string; color?: string; tokenName?: string }) {
  const shape = getShapeOrThrow(args.shapeId);

  if (args.tokenName) {
    const token = findColorToken(args.tokenName);
    if (!token) {
      throw new Error(
        `No active color token named "${args.tokenName}". Call get_color_tokens to list them.`,
      );
    }
    token.applyToShapes([shape], ["fill"]);
    return { ok: true, applied: `token ${args.tokenName}`, shape: summarizeShape(shape) };
  }

  if (args.color) {
    const fills = [{ fillColor: normalizeHex(args.color), fillOpacity: 1 }];
    if (isRuleEnforced("token-only-colors")) {
      assertFillsAllowed(fills, collectAllowedColors());
    }
    shape.fills = fills;
    return { ok: true, applied: `color ${args.color}`, shape: summarizeShape(shape) };
  }

  throw new Error("set_fill requires either tokenName or color");
}

function createShape(args: {
  kind: "board" | "rectangle" | "ellipse" | "text";
  name?: string;
  x?: number;
  y?: number;
  width?: number;
  height?: number;
  parentId?: string;
  text?: string;
  fontSize?: number;
  fillTokenName?: string;
  fillColor?: string;
}) {
  let shape: Shape | null;
  switch (args.kind) {
    case "board":
      shape = penpot.createBoard();
      break;
    case "rectangle":
      shape = penpot.createRectangle();
      break;
    case "ellipse":
      shape = penpot.createEllipse();
      break;
    case "text":
      shape = penpot.createText(args.text ?? "Text");
      break;
    default:
      throw new Error(`Unknown shape kind: ${args.kind}`);
  }
  if (!shape) throw new Error(`Could not create ${args.kind}`);

  if (args.name) shape.name = args.name;
  if (args.width && args.height) shape.resize(args.width, args.height);
  if (typeof args.x === "number") shape.x = args.x;
  if (typeof args.y === "number") shape.y = args.y;
  if (typeof args.fontSize === "number" && args.kind === "text") {
    (shape as Text).fontSize = String(args.fontSize);
  }
  if (args.parentId) {
    const parent = getShapeOrThrow(args.parentId);
    (parent as Board).appendChild(shape);
  }
  if (args.fillTokenName || args.fillColor) {
    setFill({ shapeId: shape.id, tokenName: args.fillTokenName, color: args.fillColor });
  }
  watchShape(shape.id);
  return { ok: true, shape: summarizeShape(shape) };
}

function createColorToken(args: { name: string; value: string; set?: string }) {
  const lib = penpot.library.local;
  const setName = args.set ?? "core";
  let set = lib.tokens.sets.find((s) => s.name === setName);
  if (!set) set = lib.tokens.addSet({ name: setName, active: true });
  if (!set.active) set.active = true;
  const token = set.addToken({ type: "color", name: args.name, value: normalizeHex(args.value) });
  return { ok: true, token: { name: token.name, value: token.resolvedValueString, set: set.name } };
}

/**
 * MCP-style arbitrary code execution, gated by the same guard.
 *
 * Executing agent-authored code is the feature here (same trust model as
 * Penpot's official MCP execute_code tool): it runs inside Penpot's SES
 * plugin sandbox with only the guarded penpot API in scope, and the user has
 * explicitly connected the agent to this file. Do not pass strings from any
 * other origin into this function.
 */
async function executeCode(code: string) {
  const guarded = guardPenpot(penpot, () => isRuleEnforced("token-only-colors"));
  const logs: string[] = [];
  const fakeConsole = {
    log: (...a: unknown[]) => logs.push(a.map(String).join(" ")),
    warn: (...a: unknown[]) => logs.push("[warn] " + a.map(String).join(" ")),
    error: (...a: unknown[]) => logs.push("[error] " + a.map(String).join(" ")),
    info: (...a: unknown[]) => logs.push(a.map(String).join(" ")),
  };
  const fn = new Function("penpot", "console", `return (async () => { ${code} })();`);
  const result = await fn(guarded, fakeConsole);
  return { result, log: logs.join("\n") };
}

/* ------------------------------------------------------------------ */
/* Change watching → triggered skills                                  */
/* ------------------------------------------------------------------ */

interface ShapeSnapshot {
  name: string;
  fills: string;
}

const snapshots = new Map<string, ShapeSnapshot>();
const shapeListeners = new Map<string, symbol>();
const recentlyFlagged = new Map<string, number>();

function snapshot(shape: Shape): ShapeSnapshot {
  return {
    name: shape.name,
    fills: JSON.stringify(Array.isArray(shape.fills) ? shape.fills : "mixed"),
  };
}

function throttled(key: string, ms = 4000): boolean {
  const now = Date.now();
  const last = recentlyFlagged.get(key) ?? 0;
  if (now - last < ms) return true;
  recentlyFlagged.set(key, now);
  return false;
}

function emitTriggered(skillName: string, reason: string, shape?: Shape) {
  const skill = effectiveSkills().find((s) => s.name === skillName);
  if (!skill || skill.enforcement === "advisory") return;
  if (throttled(`${skillName}:${shape?.id ?? "global"}:${reason}`)) return;
  penpot.ui.sendMessage({
    source: "plugin",
    type: "skill-triggered",
    skill: { name: skill.name, enforcement: skill.enforcement, description: skill.description },
    reason,
    shape: shape ? { id: shape.id, name: shape.name } : undefined,
  });
}

function checkShapeChange(shape: Shape) {
  const prev = snapshots.get(shape.id);
  const next = snapshot(shape);
  snapshots.set(shape.id, next);
  if (!prev) return;

  if (prev.name !== next.name && DEFAULT_NAME_RE.test(next.name)) {
    emitTriggered(
      "layer-naming",
      `Layer was renamed to the non-semantic name "${next.name}".`,
      shape,
    );
  }

  if (prev.fills !== next.fills) {
    try {
      const fills = JSON.parse(next.fills);
      assertFillsAllowed(fills, collectAllowedColors());
    } catch (e) {
      if (e instanceof SkillViolationError) {
        emitTriggered(
          "token-only-colors",
          `Shape "${shape.name}" now has a fill that is not a token: ` +
            `${(JSON.parse(next.fills) as { fillColor?: string }[])
              .map((f) => f.fillColor)
              .filter(Boolean)
              .join(", ")}. (Edited outside the gated write path — e.g. manually.)`,
          shape,
        );
      }
    }
  }
}

function watchShape(shapeId: string) {
  if (shapeListeners.has(shapeId)) return;
  const shape = penpot.currentPage?.getShapeById(shapeId);
  if (!shape) return;
  snapshots.set(shapeId, snapshot(shape));
  const listener = penpot.on(
    "shapechange",
    () => {
      const s = penpot.currentPage?.getShapeById(shapeId);
      if (s) checkShapeChange(s);
    },
    { shapeId },
  );
  shapeListeners.set(shapeId, listener as unknown as symbol);
}

penpot.on("selectionchange", () => {
  for (const shape of penpot.selection) {
    watchShape(shape.id);
    if (DEFAULT_NAME_RE.test(shape.name)) {
      emitTriggered(
        "layer-naming",
        `Selected layer "${shape.name}" still has a default, non-semantic name.`,
        shape,
      );
    }
  }
  penpot.ui.sendMessage({
    source: "plugin",
    type: "selection-change",
    selection: penpot.selection.map((s) => ({ id: s.id, name: s.name, type: s.type })),
  });
});

penpot.on("themechange", (theme) => {
  penpot.ui.sendMessage({ source: "plugin", type: "theme-change", theme });
});

/* ------------------------------------------------------------------ */
/* RPC                                                                 */
/* ------------------------------------------------------------------ */

type RpcMessage = { source: "ui"; id: number; op: string; payload?: Record<string, unknown> };

const OPS: Record<string, (payload: any) => unknown | Promise<unknown>> = {
  "get-skills": () => ({
    fileSkillSources: loadFileSkillSources(),
    effective: effectiveSkills(),
  }),
  "save-file-skills": (p: { sources: string[] }) => {
    saveFileSkillSources(p.sources);
    return { fileSkillSources: loadFileSkillSources(), effective: effectiveSkills() };
  },
  "get-design-context": () => designContext(),
  "get-color-tokens": () => colorTokens(),
  "set-fill": (p) => setFill(p),
  "create-shape": (p) => createShape(p),
  "create-color-token": (p) => createColorToken(p),
  "rename-shape": (p: { shapeId: string; name: string }) => {
    const shape = getShapeOrThrow(p.shapeId);
    shape.name = p.name;
    snapshots.set(shape.id, snapshot(shape));
    return { ok: true, shape: summarizeShape(shape) };
  },
  "execute-code": (p: { code: string }) => executeCode(p.code),
};

penpot.ui.onMessage(async (message: unknown) => {
  const msg = message as RpcMessage & { type?: string };
  if (msg?.type === "ready") {
    penpot.ui.sendMessage({
      source: "plugin",
      type: "init",
      theme: penpot.theme,
      context: designContext(),
      skills: { fileSkillSources: loadFileSkillSources(), effective: effectiveSkills() },
    });
    return;
  }
  if (msg?.source !== "ui" || typeof msg.id !== "number" || !msg.op) return;

  try {
    const handler = OPS[msg.op];
    if (!handler) throw new Error(`Unknown op: ${msg.op}`);
    const result = await handler(msg.payload ?? {});
    penpot.ui.sendMessage({ source: "plugin", type: "rpc-result", id: msg.id, result });
  } catch (e) {
    penpot.ui.sendMessage({
      source: "plugin",
      type: "rpc-error",
      id: msg.id,
      error: e instanceof Error ? e.message : String(e),
      rule: e instanceof SkillViolationError ? e.rule : undefined,
    });
  }
});
