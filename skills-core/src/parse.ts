import type { Enforcement, Scope, Skill, Trigger } from "./types";
import { ENFORCEMENTS, SCOPES } from "./types";

/**
 * Parses a skill markdown file: `---` frontmatter with flat `key: value`
 * pairs, followed by a markdown body. Deliberately tiny — no YAML dependency,
 * so the same code runs in the plugin sandbox, the iframe and the MCP server.
 */
export function parseSkill(source: string, fallbackScope: Scope = "file"): Skill {
  const match = source.match(/^\s*---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
  const meta: Record<string, string> = {};
  let body = source.trim();

  if (match) {
    body = match[2].trim();
    for (const line of match[1].split(/\r?\n/)) {
      const kv = line.match(/^([A-Za-z][\w-]*)\s*:\s*(.*)$/);
      if (kv) meta[kv[1].toLowerCase()] = kv[2].trim().replace(/^["']|["']$/g, "");
    }
  }

  const name = meta["name"] || firstHeading(body) || "unnamed-skill";
  const scope = (SCOPES as readonly string[]).includes(meta["scope"])
    ? (meta["scope"] as Scope)
    : fallbackScope;
  const enforcement = (ENFORCEMENTS as readonly string[]).includes(meta["enforcement"])
    ? (meta["enforcement"] as Enforcement)
    : "advisory";

  return {
    name,
    scope,
    enforcement,
    description: meta["description"] || body.split(/\r?\n/)[0].slice(0, 200),
    mandatory: meta["mandatory"] === "true",
    trigger: meta["trigger"] ? (meta["trigger"] as Trigger) : undefined,
    body,
    source,
  };
}

export function parseSkills(sources: string[], fallbackScope: Scope): Skill[] {
  return sources.filter((s) => s.trim().length > 0).map((s) => parseSkill(s, fallbackScope));
}

function firstHeading(body: string): string | undefined {
  const m = body.match(/^#+\s+(.+)$/m);
  return m ? m[1].trim().toLowerCase().replace(/\s+/g, "-") : undefined;
}

/** Serializes a skill back to markdown (used when seeding defaults). */
export function serializeSkill(s: Skill): string {
  const lines = [
    "---",
    `name: ${s.name}`,
    `scope: ${s.scope}`,
    `enforcement: ${s.enforcement}`,
    ...(s.mandatory ? ["mandatory: true"] : []),
    ...(s.trigger ? [`trigger: ${s.trigger}`] : []),
    `description: ${s.description}`,
    "---",
    "",
    s.body,
  ];
  return lines.join("\n");
}
