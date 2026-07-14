import { builtinCatalog, CATEGORIES } from "@penpot/skills-core";
import type { CatalogEntry, Category, Mode } from "@penpot/skills-core";

/**
 * The built-in Skills catalog: the first-run view of the Skills tab. Reads the
 * static built-in list straight from skills-core (pure, host-independent) and
 * groups it into Audits / Build / Auto-fix. Read-only at this stage — cards are
 * navigation targets into the detail view (Phase 04); no toggling (story #8).
 *
 * `enabledByName` lets the host override the per-skill enabled state once the
 * payload plumbing lands (Phase 02); until then each card falls back to the
 * catalog's `defaultEnabled` (audits + build on, auto-fix off).
 */

const MODE_LABEL: Record<Mode, string> = {
  suggest: "suggest",
  review: "review",
  autofix: "auto-fix",
};
const MODE_ICON: Record<Mode, string> = {
  suggest: "🔍",
  review: "✏️",
  autofix: "⚡",
};

/**
 * Curated display copy for the built-in skills: a short human label and a tight
 * one-line blurb, so the cards read cleanly instead of truncating each skill's
 * long marketing description. Keyed by skill name; any skill not listed falls
 * back to a derived label + blurb (so a newly-bundled skill still shows).
 */
const CURATED: Record<string, { label: string; blurb: string }> = {
  "penpot-audit-accessibility": { label: "Accessibility audit", blurb: "WCAG 2.1/2.2 AA checks" },
  "penpot-audit-tokens": {
    label: "Tokens governance audit",
    blurb: "Hardcoded values, off-grid spacing",
  },
  "penpot-design-to-code-review": {
    label: "Design-to-code review",
    blurb: "Design vs. built code drift",
  },
  "penpot-foundations": { label: "Foundations", blurb: "Design tokens setup" },
  "penpot-component-factory": { label: "Component factory", blurb: "Builds full variant matrix" },
  "penpot-build-screen": { label: "Build screen", blurb: "Designs screens from a brief" },
  "penpot-build-from-code": { label: "Build from code", blurb: "Recreates a view on your tokens" },
  "penpot-document-handoff": { label: "Document handoff", blurb: "Annotates a design for devs" },
  "penpot-migrate": { label: "Migrate", blurb: "Figma → Penpot migration" },
  "penpot-rename-layers": { label: "Rename layers", blurb: "Auto-fixes messy layer names" },
};

/** "penpot-audit-accessibility" → "Audit accessibility" (fallback for unlisted skills) */
function labelFor(name: string): string {
  const base = name.replace(/^penpot-/, "").replace(/-/g, " ");
  return base.charAt(0).toUpperCase() + base.slice(1);
}

/**
 * The first sentence of the description, tightened for a one-line card blurb:
 * drop any trailing " — …" or ": …" clause (space-delimited separators only, so
 * intra-word hyphens like "production-grade" survive), then cap the length.
 */
function blurbFor(description: string): string {
  const first = description
    .split(/(?<=[.?!])\s/)[0]
    .replace(/\s+[—–]\s+.*$/, "")
    .replace(/:\s.*$/, "")
    .trim();
  return first.length > 72 ? first.slice(0, 69).trimEnd() + "…" : first;
}

export function SkillsCatalog({
  enabledByName,
  onOpen,
}: {
  enabledByName?: Record<string, boolean>;
  onOpen?: (entry: CatalogEntry) => void;
}) {
  const catalog = builtinCatalog();
  const isEnabled = (e: CatalogEntry) => enabledByName?.[e.name] ?? e.defaultEnabled;

  return (
    <div className="catalog">
      {CATEGORIES.map((category: Category) => {
        const entries = catalog.filter((e) => e.category === category);
        if (entries.length === 0) return null;
        return (
          <div className="catalog-group" key={category}>
            <div className="catalog-group-label">{category}</div>
            {entries.map((e) => (
              <button
                key={e.name}
                type="button"
                className={`catalog-card${isEnabled(e) ? "" : " disabled"}`}
                onClick={() => onOpen?.(e)}
              >
                <div className="catalog-card-head">
                  <span className="skill-name">{CURATED[e.name]?.label ?? labelFor(e.name)}</span>
                  {!isEnabled(e) && <span className="default-off">off by default</span>}
                </div>
                <div className="skill-desc">
                  {CURATED[e.name]?.blurb ?? blurbFor(e.description)}
                  <span className={`mode-badge mode-${e.mode}`}>
                    {MODE_ICON[e.mode]} {MODE_LABEL[e.mode]}
                  </span>
                </div>
              </button>
            ))}
          </div>
        );
      })}
    </div>
  );
}
