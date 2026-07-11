import type { EffectiveSkill, Skill, Scope } from "./types";
import { enforcementRank, scopeRank } from "./types";

/** Skill names switched off per scope (recorded at the scope that defines them). */
export type DisabledByScope = Partial<Record<Scope, string[]>>;

/**
 * Cascade resolver: merges skills from the four scopes into one effective set.
 *
 * Rules:
 *  - Skills are keyed by name. The most specific scope that defines a name
 *    wins its body, description and trigger (stylistic choices cascade like CSS).
 *  - A skill marked `mandatory: true` at a higher (less specific) scope cannot
 *    be loosened below: the effective enforcement is the maximum of the
 *    winning definition's enforcement and every mandatory broader definition.
 *  - A definition listed in `disabled` for its scope is skipped (the next most
 *    specific enabled definition wins instead). Mandatory definitions cannot
 *    be disabled. When every definition is disabled, the skill stays in the
 *    result flagged `disabled: true` so UIs can show and re-enable it.
 */
export function resolveCascade(skills: Skill[], disabled: DisabledByScope = {}): EffectiveSkill[] {
  const byName = new Map<string, Skill[]>();
  for (const s of skills) {
    const list = byName.get(s.name) ?? [];
    list.push(s);
    byName.set(s.name, list);
  }

  const isDisabled = (d: Skill) => !d.mandatory && (disabled[d.scope] ?? []).includes(d.name);

  const effective: EffectiveSkill[] = [];
  for (const [, defs] of byName) {
    defs.sort((a, b) => scopeRank(a.scope) - scopeRank(b.scope));
    const enabledDefs = defs.filter((d) => !isDisabled(d));
    const pool = enabledDefs.length > 0 ? enabledDefs : defs;
    const winner = pool[pool.length - 1]; // most specific enabled definition
    const broader = defs.filter((d) => scopeRank(d.scope) < scopeRank(winner.scope));

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
      ...(enabledDefs.length === 0 ? { disabled: true } : {}),
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

/** Lean manifest for context windows: excludes disabled skills and their bodies. */
export function skillManifest(skills: EffectiveSkill[]) {
  return skills
    .filter((s) => !s.disabled)
    .map((s) => ({
      name: s.name,
      kind: s.kind,
      scope: s.definedAt,
      enforcement: s.enforcement,
      mandatory: s.mandatory,
      trigger: s.trigger,
      description: s.description,
    }));
}
