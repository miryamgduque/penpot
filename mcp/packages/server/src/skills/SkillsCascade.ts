/**
 * Penpot Skills — cascade resolution for the MCP server.
 *
 * File-scope skills live inside the design file (shared pluginData, namespace
 * "penpot-skills", key "skills", written by the Penpot Skills plugin in
 * /ai-skills). Platform/org/project scopes are stubs here, mirroring
 * ai-skills/src/skills/builtin.ts — in the full vision they come from the
 * platform catalog, the organization and the project.
 *
 * NOTE: this intentionally duplicates the tiny parser/resolver from
 * /ai-skills/src/skills (separate pnpm workspaces); keep the two in sync.
 */

export type Scope = "platform" | "org" | "project" | "file";
export type Enforcement = "advisory" | "triggered" | "enforced";

const SCOPES: Scope[] = ["platform", "org", "project", "file"];
const ENFORCEMENTS: Enforcement[] = ["advisory", "triggered", "enforced"];

export interface Skill {
    name: string;
    scope: Scope;
    enforcement: Enforcement;
    description: string;
    mandatory: boolean;
    trigger?: string;
    body: string;
}

export interface EffectiveSkill extends Skill {
    definedAt: Scope;
    enforcementRaisedBy?: Scope;
    overrides: Scope[];
}

/** Parses `---` frontmatter (flat key: value pairs) followed by a markdown body. */
export function parseSkill(source: string, fallbackScope: Scope): Skill {
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
    return {
        name: meta["name"] || "unnamed-skill",
        scope: SCOPES.includes(meta["scope"] as Scope) ? (meta["scope"] as Scope) : fallbackScope,
        enforcement: ENFORCEMENTS.includes(meta["enforcement"] as Enforcement)
            ? (meta["enforcement"] as Enforcement)
            : "advisory",
        description: meta["description"] || body.split(/\r?\n/)[0].slice(0, 200),
        mandatory: meta["mandatory"] === "true",
        trigger: meta["trigger"] || undefined,
        body,
    };
}

/**
 * Merges skills into the effective set: most specific scope wins the body;
 * enforcement marked mandatory at a broader scope cannot be loosened below.
 */
export function resolveCascade(skills: Skill[]): EffectiveSkill[] {
    const rank = (e: Enforcement) => ENFORCEMENTS.indexOf(e);
    const byName = new Map<string, Skill[]>();
    for (const s of skills) byName.set(s.name, [...(byName.get(s.name) ?? []), s]);

    const effective: EffectiveSkill[] = [];
    for (const defs of byName.values()) {
        defs.sort((a, b) => SCOPES.indexOf(a.scope) - SCOPES.indexOf(b.scope));
        const winner = defs[defs.length - 1];
        const broader = defs.slice(0, -1);
        let enforcement = winner.enforcement;
        let enforcementRaisedBy: Scope | undefined;
        for (const b of broader) {
            if (b.mandatory && rank(b.enforcement) > rank(enforcement)) {
                enforcement = b.enforcement;
                enforcementRaisedBy = b.scope;
            }
        }
        effective.push({
            ...winner,
            enforcement,
            mandatory: winner.mandatory || broader.some((b) => b.mandatory),
            definedAt: winner.scope,
            enforcementRaisedBy,
            overrides: broader.map((b) => b.scope),
        });
    }
    effective.sort((a, b) => rank(b.enforcement) - rank(a.enforcement) || a.name.localeCompare(b.name));
    return effective;
}

/** Stub skills for the broader scopes (kept in sync with ai-skills/src/skills/builtin.ts). */
export const BUILTIN_SKILL_SOURCES: { scope: Scope; source: string }[] = [
    {
        scope: "platform",
        source: `---
name: a11y-contrast
scope: platform
enforcement: advisory
mandatory: true
description: Text and interactive elements must meet WCAG 2.1 AA contrast (4.5:1 body text, 3:1 large text and UI components).
---

# Accessibility contrast floor

Every text element must have a contrast ratio of at least **4.5:1** against its
background (3:1 for text larger than 24px, and for interactive component
boundaries). This is a platform-level floor: file or project skills may tighten
it but never loosen it.`,
    },
    {
        scope: "org",
        source: `---
name: design-documentation
scope: org
enforcement: advisory
description: Boards intended for handoff carry a short annotation of purpose and interaction notes; components reference their code counterpart.
---

# Documentation conventions

When a screen or component is ready for handoff, add a short annotation text
below the board: what it is, key interactions, and open questions.`,
    },
    {
        scope: "project",
        source: `---
name: spacing-and-type
scope: project
enforcement: advisory
description: 8px spacing grid; type scale 12/14/16/20/24/32; Inter or Work Sans; friendly, concise microcopy.
---

# Look and feel

- All spacing and sizes sit on an **8px grid** (4px allowed inside dense components).
- Type scale: 12, 14, 16, 20, 24, 32. Body text is 14 or 16.
- Microcopy tone: friendly and concise, sentence case, no exclamation marks.`,
    },
];
