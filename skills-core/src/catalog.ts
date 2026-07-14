/**
 * The built-in skills catalog — the first-run view of the Skills tab.
 *
 * Derives, purely from the bundled penpot-ai-kit skills, the category-grouped
 * cards shown before the user has created or promoted anything personally:
 * category and default-enabled state come from each skill's `mode`, and an
 * example trigger phrase is lifted from the description's `Triggers:` list.
 *
 * The dispatch router and the shared/core house-rule docs are deliberately
 * excluded — they are not skills from the user's perspective. Kept dependency-
 * free so it runs in the plugin sandbox, the iframe and the MCP server alike.
 */

import type { Category, Mode, Skill } from "./types";
import { parseSkills } from "./parse";
import { AIKIT_PLATFORM_SKILLS } from "./aikit.gen";

export interface CatalogEntry {
  name: string;
  category: Category;
  mode: Mode;
  /** Short description of what the skill does. */
  description: string;
  /** A natural-language sample of how a user might ask for this skill. */
  example: string;
  /** Ships enabled on first run? Every mode is on except the direct-writing autofix. */
  defaultEnabled: boolean;
}

const CATEGORY_BY_MODE: Record<Mode, Category> = {
  suggest: "Audits",
  review: "Build",
  autofix: "Auto-fix",
};

/**
 * Skills that carry a mode but are never shown as catalog cards: the router is
 * dispatch logic, not a user-facing skill. The shared/core docs carry no mode
 * and fall out of the catalog naturally, so they need no entry here.
 */
const HIDDEN_CORE_SKILLS = new Set<string>(["penpot-router"]);

export function categoryForMode(mode: Mode): Category {
  return CATEGORY_BY_MODE[mode];
}

/**
 * Audit + build skills ship on so the agent is fully capable from the first
 * open; the single autofix skill ships off because it applies changes directly
 * without asking and needs explicit activation.
 */
export function defaultEnabledForMode(mode: Mode): boolean {
  return mode !== "autofix";
}

/**
 * Lifts an example trigger phrase from a skill description. Descriptions end
 * with a `Triggers: 'a', 'b', …` list; we take the first quoted phrase,
 * capitalize it and give it terminal punctuation. Falls back to the first
 * sentence of the description when there is no Triggers list.
 */
export function exampleTriggerOf(description: string): string {
  const triggers = description.match(/Triggers?:\s*(.+)$/i);
  const scope = triggers ? triggers[1] : description;
  const quoted = scope.match(/['"]([^'"]+)['"]/);
  if (quoted) {
    const phrase = quoted[1].trim();
    const capped = phrase.charAt(0).toUpperCase() + phrase.slice(1);
    return /[.?!]$/.test(capped) ? capped : capped + ".";
  }
  return description.split(/(?<=[.?!])\s/)[0].trim();
}

/** One parsed skill → a catalog entry, or null when it must not appear as a card. */
export function toCatalogEntry(skill: Skill): CatalogEntry | null {
  if (!skill.mode || HIDDEN_CORE_SKILLS.has(skill.name)) return null;
  return {
    name: skill.name,
    category: categoryForMode(skill.mode),
    mode: skill.mode,
    description: skill.description,
    example: exampleTriggerOf(skill.description),
    defaultEnabled: defaultEnabledForMode(skill.mode),
  };
}

/** The built-in catalog: bundled skills, minus router and core docs, as cards. */
export function builtinCatalog(): CatalogEntry[] {
  return parseSkills(AIKIT_PLATFORM_SKILLS, "platform")
    .map(toCatalogEntry)
    .filter((e): e is CatalogEntry => e !== null);
}
