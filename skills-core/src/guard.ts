import { parseSkill } from "./parse";

/**
 * Structural enforcement for the token-only-colors rule.
 *
 * The check runs inside Penpot's plugin runtime — not in the model's head —
 * so any agent (frontier or local) hitting the write path is gated the same
 * way. A violation throws a SkillViolationError whose message cites the rule
 * and lists the allowed tokens, so the agent can self-correct.
 *
 * This module is consumer-agnostic: it never touches the `penpot` global or
 * `@penpot/plugin-types` (whose published version predates the tokens API).
 * Callers pass in the library object and the rule-activation policy.
 */

export class SkillViolationError extends Error {
  readonly rule: string;
  constructor(rule: string, message: string) {
    super(message);
    this.name = "SkillViolationError";
    this.rule = rule;
  }
}

export interface AllowedColor {
  value: string; // normalized lowercase hex
  label: string; // token or library color name
  kind: "token" | "library-color";
}

/**
 * Minimal structural view of `penpot.library.local`. The tokens catalog is
 * optional because older Penpot versions (and the published plugin-types)
 * predate the Design Tokens API.
 */
export interface LocalLibraryLike {
  tokens?: {
    sets?: {
      active: boolean;
      tokens: { name: string; type?: string; resolvedValueString?: string; value?: unknown }[];
    }[];
  };
  colors: { color?: string; name?: string }[];
}

export function normalizeHex(color: string): string {
  let c = color.trim().toLowerCase();
  if (/^#[0-9a-f]{3}$/.test(c)) {
    c = "#" + c[1] + c[1] + c[2] + c[2] + c[3] + c[3];
  }
  return c;
}

/** Collects the colors a file allows: color tokens in active sets + library colors. */
export function collectAllowedColors(lib: LocalLibraryLike): AllowedColor[] {
  const allowed: AllowedColor[] = [];

  for (const set of lib.tokens?.sets ?? []) {
    if (!set.active) continue;
    for (const token of set.tokens) {
      if (token.type !== "color") continue;
      const value = token.resolvedValueString ?? token.value;
      if (typeof value === "string" && value.startsWith("#")) {
        allowed.push({ value: normalizeHex(value), label: token.name, kind: "token" });
      }
    }
  }

  for (const color of lib.colors) {
    if (color.color) {
      allowed.push({
        value: normalizeHex(color.color),
        label: color.name ?? color.color,
        kind: "library-color",
      });
    }
  }
  return allowed;
}

/**
 * Validates a fills array against the token-only-colors rule.
 * Throws SkillViolationError when a raw (non-token) color is used.
 */
export function assertFillsAllowed(fills: unknown, allowed: AllowedColor[]): void {
  if (!Array.isArray(fills)) return;
  for (const fill of fills) {
    const color = (fill as { fillColor?: unknown })?.fillColor;
    if (typeof color !== "string") continue; // gradients/images are out of scope for the prototype
    const hex = normalizeHex(color);
    if (!allowed.some((a) => a.value === hex)) {
      const tokenList =
        allowed
          .slice(0, 20)
          .map((a) => `${a.label} (${a.value})`)
          .join(", ") || "none defined yet — create color tokens first";
      throw new SkillViolationError(
        "token-only-colors",
        `Rejected by enforced design skill "token-only-colors": fill color ${color} ` +
          `is not one of this file's color tokens or library colors. ` +
          `Allowed colors: ${tokenList}. ` +
          `Apply colors by token instead of raw hex values — find the token in ` +
          `penpot.library.local.tokens.sets and call token.applyToShapes([shape], ["fill"]).`,
      );
    }
  }
}

/**
 * Checks whether raw file-scope skills data (the JSON array stored in shared
 * pluginData namespace "penpot-skills", key "skills") declares the given
 * skill with `enforcement: enforced`.
 */
export function isSkillEnforcedInSources(
  raw: string | null | undefined,
  skillName: string,
): boolean {
  if (!raw) return false;
  try {
    const sources: unknown = JSON.parse(raw);
    if (!Array.isArray(sources)) return false;
    return sources.some((source) => {
      if (typeof source !== "string") return false;
      const skill = parseSkill(source, "file");
      return skill.name === skillName && skill.enforcement === "enforced";
    });
  } catch {
    // unreadable skills data — do not enforce
    return false;
  }
}

export interface GuardOptions {
  /** Whether the token-only-colors rule is currently active for this file. */
  isRuleActive: () => boolean;
  /** The colors the file allows, collected at validation time. */
  collectAllowed: () => AllowedColor[];
}

/**
 * Wraps the `penpot` API object in a recursive Proxy that intercepts every
 * `fills` assignment anywhere in the object graph and validates it. This is
 * how arbitrary code execution (the MCP-style `execute_code` path) is gated.
 */
export function guardPenpot<T extends object>(root: T, opts: GuardOptions): T {
  const cache = new WeakMap<object, unknown>();
  const unwrapMap = new WeakMap<object, object>();

  const unwrap = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(unwrap);
    return value !== null && typeof value === "object" && unwrapMap.has(value)
      ? unwrapMap.get(value)
      : value;
  };

  const wrap = (obj: unknown): unknown => {
    if (obj === null || (typeof obj !== "object" && typeof obj !== "function")) return obj;
    // Arrays (selection, findShapes results…) must have their ELEMENTS
    // wrapped, or shapes obtained through them would escape the guard.
    if (Array.isArray(obj)) return obj.map(wrap);
    const target = obj as object;
    if (cache.has(target)) return cache.get(target);

    const proxy = new Proxy(target, {
      get(t, prop) {
        // Use the raw target as receiver so native getters keep working.
        const value = Reflect.get(t, prop, t);
        if (typeof value === "function") {
          // unwrap proxied arguments so the real API receives its own objects
          return (...args: unknown[]) => wrap(value.apply(t, args.map(unwrap)));
        }
        return wrap(value);
      },
      set(t, prop, value) {
        if (prop === "fills" && opts.isRuleActive()) {
          assertFillsAllowed(value, opts.collectAllowed());
        }
        if (!Reflect.set(t, prop, unwrap(value), t)) {
          // a bare "proxy set returned false" is useless to an agent —
          // name the property and point at the right API
          throw new TypeError(
            `Property "${String(prop)}" is read-only in the Penpot plugin API` +
              (prop === "width" || prop === "height"
                ? " — use shape.resize(width, height) instead"
                : ""),
          );
        }
        return true;
      },
    });
    cache.set(target, proxy);
    unwrapMap.set(proxy, target);
    return proxy;
  };

  return wrap(root) as T;
}
