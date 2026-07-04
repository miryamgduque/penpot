/**
 * Core data model for Penpot Skills.
 *
 * A skill is a small markdown document with YAML-ish frontmatter. It is
 * human-authorable, diffable and vendor-neutral. Skills live at four scopes
 * mirroring Penpot's hierarchy; a cascade resolver merges them into one
 * effective set.
 */

export const SCOPES = ["platform", "org", "project", "file"] as const;
export type Scope = (typeof SCOPES)[number];

/** advisory < triggered < enforced */
export const ENFORCEMENTS = ["advisory", "triggered", "enforced"] as const;
export type Enforcement = (typeof ENFORCEMENTS)[number];

/** Moments the runtime knows how to detect and surface triggered skills on. */
export type Trigger =
  | "fill-change"
  | "shape-new"
  | "rename"
  | "selection-change"
  | "content-save";

export interface Skill {
  name: string;
  scope: Scope;
  enforcement: Enforcement;
  description: string;
  /** Only meaningful at platform/org/project scope: lower scopes cannot loosen it. */
  mandatory: boolean;
  trigger?: Trigger;
  /** Markdown body (without frontmatter). */
  body: string;
  /** The raw markdown source (frontmatter + body), for display/editing. */
  source: string;
}

export interface EffectiveSkill extends Skill {
  /** Scope the winning definition came from. */
  definedAt: Scope;
  /**
   * Set when a mandatory higher-scope skill raised the enforcement over what
   * the most specific definition asked for.
   */
  enforcementRaisedBy?: Scope;
  /** Scopes that had definitions overridden by a more specific one. */
  overrides: Scope[];
}

export function enforcementRank(e: Enforcement): number {
  return ENFORCEMENTS.indexOf(e);
}

export function scopeRank(s: Scope): number {
  // higher = more specific
  return SCOPES.indexOf(s);
}
