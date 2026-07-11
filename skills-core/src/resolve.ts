import type { EffectiveSkill, Skill, Scope } from "./types";
import { enforcementRank, scopeRank } from "./types";

/**
 * Cascade resolver: merges skills from the four scopes into one effective set.
 *
 * Rules:
 *  - Skills are keyed by name. The most specific scope that defines a name
 *    wins its body, description and trigger (stylistic choices cascade like CSS).
 *  - A skill marked `mandatory: true` at a higher (less specific) scope cannot
 *    be loosened below: the effective enforcement is the maximum of the
 *    winning definition's enforcement and every mandatory broader definition.
 */
export function resolveCascade(skills: Skill[]): EffectiveSkill[] {
  const byName = new Map<string, Skill[]>();
  for (const s of skills) {
    const list = byName.get(s.name) ?? [];
    list.push(s);
    byName.set(s.name, list);
  }

  const effective: EffectiveSkill[] = [];
  for (const [, defs] of byName) {
    defs.sort((a, b) => scopeRank(a.scope) - scopeRank(b.scope));
    const winner = defs[defs.length - 1]; // most specific
    const broader = defs.slice(0, -1);

    let enforcement = winner.enforcement;
    let enforcementRaisedBy: Scope | undefined;
    for (const b of broader) {
      if (b.mandatory && enforcementRank(b.enforcement) > enforcementRank(enforcement)) {
        enforcement = b.enforcement;
        enforcementRaisedBy = b.scope;
      }
    }

    effective.push({
      ...winner,
      enforcement,
      // a mandatory skill stays mandatory even if the specific override dropped the flag
      mandatory: winner.mandatory || broader.some((b) => b.mandatory),
      definedAt: winner.scope,
      enforcementRaisedBy,
      overrides: broader.map((b) => b.scope),
    });
  }

  // stable, presentation-friendly order: enforced first, then triggered, then advisory
  effective.sort(
    (a, b) =>
      enforcementRank(b.enforcement) - enforcementRank(a.enforcement) ||
      a.name.localeCompare(b.name),
  );
  return effective;
}

/** Lean manifest for context windows: name/scope/enforcement/description only. */
export function skillManifest(skills: EffectiveSkill[]) {
  return skills.map((s) => ({
    name: s.name,
    scope: s.definedAt,
    enforcement: s.enforcement,
    mandatory: s.mandatory,
    trigger: s.trigger,
    description: s.description,
  }));
}
