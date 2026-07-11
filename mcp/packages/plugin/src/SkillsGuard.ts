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
 * The guard itself lives in the shared @penpot/skills-core package (also used
 * by the Skills plugin); this module only binds it to this plugin's `penpot`
 * global and the file's declared rules.
 */

import {
    collectAllowedColors,
    guardPenpot as guardWithSkills,
    isSkillEnforcedInSources,
    parseDisabledNames,
    SkillViolationError,
    type LocalLibraryLike,
} from "@penpot/skills-core";

export { SkillViolationError };

function isSkillEnforced(skillName: string): boolean {
    try {
        const file = penpot.currentFile;
        const disabled = parseDisabledNames(file?.getSharedPluginData("penpot-skills", "disabled"));
        if (disabled.includes(skillName)) return false;
        return isSkillEnforcedInSources(file?.getSharedPluginData("penpot-skills", "skills"), skillName);
    } catch {
        // unreadable skills data — do not enforce
        return false;
    }
}

/**
 * Wraps the `penpot` API object in a recursive Proxy that validates every
 * `fills` assignment while the token-only-colors rule is enforced by the
 * current file.
 */
export function guardPenpot<T extends object>(root: T): T {
    return guardWithSkills(root, {
        isRuleActive: () => isSkillEnforced("token-only-colors"),
        // the published plugin-types (pinned here) predate the tokens API,
        // hence the structural cast — skills-core feature-detects the catalog
        collectAllowed: () =>
            collectAllowedColors(penpot.library.local as unknown as LocalLibraryLike),
    });
}
