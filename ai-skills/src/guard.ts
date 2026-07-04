/**
 * Structural enforcement for the token-only-colors rule.
 *
 * The check runs inside Penpot's plugin runtime — not in the model's head —
 * so any agent (frontier or local) hitting the write path is gated the same
 * way. A violation throws a SkillViolationError whose message cites the rule
 * and lists the allowed tokens, so the agent can self-correct.
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

export function normalizeHex(color: string): string {
  let c = color.trim().toLowerCase();
  if (/^#[0-9a-f]{3}$/.test(c)) {
    c = "#" + c[1] + c[1] + c[2] + c[2] + c[3] + c[3];
  }
  return c;
}

/** Collects the colors this file allows: color tokens in active sets + library colors. */
export function collectAllowedColors(): AllowedColor[] {
  const allowed: AllowedColor[] = [];
  const lib = penpot.library.local;

  for (const set of lib.tokens.sets) {
    if (!set.active) continue;
    for (const token of set.tokens) {
      if ((token as { type?: string }).type !== "color") continue;
      const value = token.resolvedValueString ?? (token as { value?: string }).value;
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
        `Rejected by enforced skill "token-only-colors" (file scope): fill color ${color} ` +
          `is not one of this file's color tokens or library colors. ` +
          `Use one of: ${tokenList}. ` +
          `Apply colors by token (see get_color_tokens) instead of raw hex values.`,
      );
    }
  }
}

/**
 * Wraps the `penpot` API object in a recursive Proxy that intercepts every
 * `fills` assignment anywhere in the object graph and validates it. This is
 * how arbitrary code execution (the MCP-style `execute_code` path) is gated.
 */
export function guardPenpot<T extends object>(root: T, isRuleActive: () => boolean): T {
  const cache = new WeakMap<object, unknown>();
  const unwrapMap = new WeakMap<object, object>();

  const unwrap = (value: unknown): unknown =>
    value !== null && typeof value === "object" && unwrapMap.has(value)
      ? unwrapMap.get(value)
      : value;

  const wrap = (obj: unknown): unknown => {
    if (obj === null || (typeof obj !== "object" && typeof obj !== "function")) return obj;
    // Never wrap plain data (arrays, fills, etc.) — only API objects with methods.
    if (Array.isArray(obj)) return obj;
    const target = obj as object;
    if (cache.has(target)) return cache.get(target);

    const proxy = new Proxy(target, {
      get(t, prop, _receiver) {
        // Use the raw target as receiver so native getters keep working.
        const value = Reflect.get(t, prop, t);
        if (typeof value === "function") {
          // unwrap proxied arguments so the real API receives its own objects
          return (...args: unknown[]) => wrap(value.apply(t, args.map(unwrap)));
        }
        return wrap(value);
      },
      set(t, prop, value) {
        if (prop === "fills" && isRuleActive()) {
          assertFillsAllowed(value, collectAllowedColors());
        }
        return Reflect.set(t, prop, unwrap(value), t);
      },
    });
    cache.set(target, proxy);
    unwrapMap.set(proxy, target);
    return proxy;
  };

  return wrap(root) as T;
}
