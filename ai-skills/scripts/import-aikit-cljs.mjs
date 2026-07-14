#!/usr/bin/env node
/**
 * Generates the native agent's skill-body corpus:
 *   ../skills-core/src/aikit.gen.ts  →  frontend/src/app/main/data/workspace/aikit_bodies.cljs
 *
 * Why a second step rather than emitting from import-aikit.mjs directly: that
 * script needs a penpot-ai-kit checkout, while aikit.gen.ts is committed and is
 * the local source of truth for every consumer (Skills plugin, MCP server, and
 * now the native CLJS agent). The chain stays regenerable either way:
 *
 *   penpot-ai-kit → import-aikit.mjs → aikit.gen.ts → THIS → aikit_bodies.cljs
 *
 * ## What this strips, and why it is not just noise-trimming
 *
 * Every kit skill follows the same 16-section template. Four classes of section
 * are actively WRONG for the embedded agent and are dropped:
 *
 *   - "Penpot MCP Tool Reference" / "Plugin API Essentials" — describe an MCP
 *     server driving the plugin API. The native agent has neither; it calls its
 *     own tools through Penpot's internal changes pipeline.
 *   - "Modes & Policies" / "Naming Conventions" — duplicated verbatim in every
 *     skill, and already carried always-on by `agent-skills/inner-knowledge`.
 *     Keeping them would pay for the same text twice and give two sources of
 *     truth for governance.
 *   - "State Management" — plugin-data (`setSharedPluginData`) specific.
 *   - "Helper Code Snippets" / "Reference Resources" / "Supporting Files" —
 *     plugin-API code and cross-references to kit files we do not ship.
 *
 * What survives is the part worth loading: the method — what to build, in what
 * order, the critical rules, the checkpoints, and the anti-rationalization
 * table. That is ~64% of the corpus.
 *
 * Section-stripping is deterministic and reviewable, but it is NOT sufficient:
 * a few bodies still reference capabilities the native agent lacks
 * (`execute_code`, `export_shape`, the Figma MCP). Those are handled at fetch
 * time by the preamble in `agent-skills/skill-body`, not here — rewriting prose
 * mechanically would be guesswork.
 *
 * Usage: node ai-skills/scripts/import-aikit-cljs.mjs
 */
import { readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const genTs = join(here, "..", "..", "skills-core", "src", "aikit.gen.ts");
const outCljs = join(
  here, "..", "..", "frontend", "src", "app", "main", "data", "workspace", "aikit_bodies.cljs",
);

/** Sections dropped from every body — see the header comment. */
const DROP_SECTION =
  /^##\s*\d+\.\s*(\(intentionally merged[^)]*\)\s*)?(Penpot MCP Tool Reference|Plugin API Essentials|Modes & Policies|State Management|Naming Conventions|Helper Code Snippets|Reference Resources|Supporting Files)/i;

/** Skills that are house-rule docs or the dispatcher, not user-facing playbooks. */
const NOT_A_PLAYBOOK =
  /^penpot-(router|operating-modes|naming-conventions|mcp-tool-reference|plugin-api-gotchas)$/;

/** Drops whole `##` sections listed in DROP_SECTION; collapses the gaps. */
function stripSections(body) {
  const out = [];
  let dropping = false;
  for (const line of body.split("\n")) {
    if (/^##\s/.test(line)) dropping = DROP_SECTION.test(line);
    if (!dropping) out.push(line);
  }
  return out.join("\n").replace(/\n{3,}/g, "\n\n").trim();
}

const src = readFileSync(genTs, "utf8");
const match = src.match(/export const AIKIT_PLATFORM_SKILLS: string\[\] = (\[[\s\S]*?\n\]);/);
if (!match) {
  console.error(`Could not find AIKIT_PLATFORM_SKILLS in ${genTs}`);
  process.exit(1);
}
const skills = JSON.parse(match[1]);

const entries = [];
let before = 0;
let after = 0;
for (const source of skills) {
  const name = (source.match(/^name:\s*(.+)$/m) || [])[1];
  if (!name || NOT_A_PLAYBOOK.test(name)) continue;
  const body = stripSections(source);
  before += source.length;
  after += body.length;
  entries.push([name, body]);
}

// The agent's tool-result cap (agent/max-tool-result-chars) silently truncates
// at 20000 chars, and the body is JSON-encoded on the way out (every newline
// costs 2). A body that quietly loses its tail is worse than one that fails to
// generate, so guard here — at build time, where a human sees it — rather than
// discovering a half-playbook in production.
const MAX_ENCODED = 20000;
const BUDGET = 0.85; // leave room for the preamble + the surrounding JSON
const oversize = entries.filter(
  ([, body]) => JSON.stringify(body).length > MAX_ENCODED * BUDGET,
);
if (oversize.length) {
  console.error(
    `\nERROR: ${oversize.length} body/bodies exceed ${Math.round(MAX_ENCODED * BUDGET)} encoded chars ` +
      `and would be truncated mid-playbook by the tool-result cap:`,
  );
  for (const [name, body] of oversize) {
    console.error(`  ${name}: ${JSON.stringify(body).length} encoded chars`);
  }
  console.error("Strip more sections, or split the skill, before shipping.\n");
  process.exit(1);
}

const cljs = [
  ";; This Source Code Form is subject to the terms of the Mozilla Public",
  ";; License, v. 2.0. If a copy of the MPL was not distributed with this",
  ";; file, You can obtain one at http://mozilla.org/MPL/2.0/.",
  ";;",
  ";; Copyright (c) KALEIDOS INC Sucursal en España SL",
  "",
  ";; GENERATED by ai-skills/scripts/import-aikit-cljs.mjs — do not edit by hand.",
  ";; Source: skills-core/src/aikit.gen.ts (itself generated from penpot-ai-kit).",
  ";; Tooling sections (MCP / plugin API) and the sections duplicated by",
  ";; agent-skills/inner-knowledge are stripped at generation time; see the",
  ";; generator's header comment for the rationale.",
  "",
  "(ns app.main.data.workspace.aikit-bodies",
  '  "Skill bodies for the native agent: the playbook text `get_design_skills`',
  '  serves on demand. Metadata (name/blurb/mode) lives in `agent-skills`; this is',
  '  the part that only loads when a task actually matches a skill.")',
  "",
  "(def bodies",
  "  {",
  ...entries.map(([name, body]) => `   ${JSON.stringify(name)}\n   ${JSON.stringify(body)}`),
  "   })",
  "",
].join("\n");

writeFileSync(outCljs, cljs);

const pct = Math.round(100 - (100 * after) / before);
console.log(`aikit_bodies.cljs: ${entries.length} skills`);
console.log(`  ${before} → ${after} chars (${pct}% stripped, ~${Math.round(after / 4)} tokens total)`);
for (const [name, body] of entries) {
  console.log(`  ${name.padEnd(30)} ${String(body.length).padStart(6)}`);
}
