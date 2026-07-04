/**
 * Penpot Skills — structural enforcement for the MCP execute_code write path.
 *
 * The design file itself declares its rules (shared pluginData namespace
 * "penpot-skills", key "skills" — markdown skills with frontmatter, written by
 * the Penpot Skills plugin in /ai-skills). When the file declares
 * `token-only-colors` with `enforcement: enforced`, every `fills` assignment
 * made by LLM-authored code is validated against the file's color tokens and
 * library colors, and rejected with an error citing the rule.
 *
 * The check runs here, in the Penpot plugin runtime — not in the model's head —
 * so a mediocre local model is gated identically to a frontier one.
 */

/** Error thrown when a write violates an enforced design skill. */
export class SkillViolationError extends Error {
    readonly rule: string;

    constructor(rule: string, message: string) {
        super(message);
        this.name = "SkillViolationError";
        this.rule = rule;
    }
}

interface AllowedColor {
    value: string;
    label: string;
    kind: "token" | "library-color";
}

function normalizeHex(color: string): string {
    let c = color.trim().toLowerCase();
    if (/^#[0-9a-f]{3}$/.test(c)) {
        c = "#" + c[1] + c[1] + c[2] + c[2] + c[3] + c[3];
    }
    return c;
}

/**
 * Checks whether the current file declares the given skill as enforced.
 * Only frontmatter is inspected — a minimal parse that must stay consistent
 * with ai-skills/src/skills/parse.ts.
 */
export function isSkillEnforced(skillName: string): boolean {
    try {
        const raw = penpot.currentFile?.getSharedPluginData("penpot-skills", "skills");
        if (!raw) return false;
        const sources: unknown = JSON.parse(raw);
        if (!Array.isArray(sources)) return false;
        for (const source of sources) {
            if (typeof source !== "string") continue;
            const fm = source.match(/^\s*---\r?\n([\s\S]*?)\r?\n---/);
            if (!fm) continue;
            const meta: Record<string, string> = {};
            for (const line of fm[1].split(/\r?\n/)) {
                const kv = line.match(/^([A-Za-z][\w-]*)\s*:\s*(.*)$/);
                if (kv) meta[kv[1].toLowerCase()] = kv[2].trim();
            }
            if (meta["name"] === skillName && meta["enforcement"] === "enforced") return true;
        }
    } catch {
        // unreadable skills data — do not enforce
    }
    return false;
}

/** Collects the colors this file allows: color tokens in active sets + library colors. */
function collectAllowedColors(): AllowedColor[] {
    const allowed: AllowedColor[] = [];
    const lib = penpot.library.local;

    // tokens catalog is feature-detected: the published plugin-types (and older
    // Penpot versions) predate the Design Tokens API
    interface TokenSetLike {
        active: boolean;
        tokens: { name: string; resolvedValueString?: string; value?: string; type?: string }[];
    }
    const tokenSets: TokenSetLike[] =
        (lib as unknown as { tokens?: { sets?: TokenSetLike[] } }).tokens?.sets ?? [];

    for (const set of tokenSets) {
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
            allowed.push({ value: normalizeHex(color.color), label: color.name ?? color.color, kind: "library-color" });
        }
    }
    return allowed;
}

/** Validates a fills array against the token-only-colors rule; throws on violation. */
export function assertFillsAllowed(fills: unknown): void {
    if (!Array.isArray(fills)) return;
    const allowed = collectAllowedColors();
    for (const fill of fills) {
        const color = (fill as { fillColor?: unknown })?.fillColor;
        if (typeof color !== "string") continue;
        const hex = normalizeHex(color);
        if (!allowed.some((a) => a.value === hex)) {
            const tokenList =
                allowed
                    .slice(0, 20)
                    .map((a) => `${a.label} (${a.value})`)
                    .join(", ") || "none defined yet — create color tokens first";
            throw new SkillViolationError(
                "token-only-colors",
                `Rejected by enforced design skill "token-only-colors" (defined in this Penpot file): ` +
                    `fill color ${color} is not one of the file's color tokens or library colors. ` +
                    `Allowed colors: ${tokenList}. ` +
                    `Apply one of these token values instead of a raw hex color ` +
                    `(you can also apply tokens directly: find the token in ` +
                    `penpot.library.local.tokens.sets and call token.applyToShapes([shape], ["fill"])).`
            );
        }
    }
}

/**
 * Wraps the `penpot` API object in a recursive Proxy that intercepts every
 * `fills` assignment anywhere in the object graph and validates it while the
 * token-only-colors rule is enforced by the current file.
 */
export function guardPenpot<T extends object>(root: T): T {
    const cache = new WeakMap<object, unknown>();
    const unwrapMap = new WeakMap<object, object>();

    const unwrap = (value: unknown): unknown => {
        if (value !== null && typeof value === "object" && unwrapMap.has(value)) {
            return unwrapMap.get(value);
        }
        return value;
    };

    const wrap = (obj: unknown): unknown => {
        if (obj === null || (typeof obj !== "object" && typeof obj !== "function")) return obj;
        if (Array.isArray(obj)) return obj;
        const target = obj as object;
        if (cache.has(target)) return cache.get(target);

        const proxy = new Proxy(target, {
            get(t, prop) {
                // use the raw target as receiver so native getters keep working
                const value = Reflect.get(t, prop, t);
                if (typeof value === "function") {
                    // unwrap proxied arguments so the real API receives its own objects
                    return (...args: unknown[]) => wrap(value.apply(t, args.map(unwrap)));
                }
                return wrap(value);
            },
            set(t, prop, value) {
                if (prop === "fills" && isSkillEnforced("token-only-colors")) {
                    assertFillsAllowed(value);
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
