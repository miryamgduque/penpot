#!/usr/bin/env node
/**
 * Imports the official penpot-ai-kit (https://github.com/penpot/penpot-ai-kit)
 * for the NATIVE agent, generating two committed CLJS modules:
 *
 *   frontend/src/app/main/data/workspace/aikit_bodies.cljs   (skill playbooks)
 *   frontend/src/app/main/data/workspace/aikit_refs.cljs     (per-skill references)
 *
 * This replaces the retired ai-skills/skills-core pipeline (kit → aikit.gen.ts
 * → aikit_bodies.cljs) with one step, and — new since the Kahoot postmortem —
 * it now also imports each skill's `references/*.md`. Those files carry the
 * design knowledge the playbooks lean on (layout composition, style profiles,
 * component recipes, critique framework); the bodies cite them by filename,
 * and until this import they were dangling pointers the agent could not
 * follow. `get_design_skills {name, reference}` serves them on demand.
 *
 * ## What is stripped from the BODIES, and why it is not just noise-trimming
 *
 * Every kit skill follows the same 16-section template. Four classes of
 * section are actively WRONG for the embedded agent and are dropped:
 *
 *   - "Penpot MCP Tool Reference" / "Plugin API Essentials" — describe an MCP
 *     server driving the plugin API. The native agent has neither.
 *   - "Modes & Policies" / "Naming Conventions" — duplicated in every skill
 *     and already carried always-on by `agent-skills/inner-knowledge`.
 *   - "State Management" — plugin-data (`setSharedPluginData`) specific.
 *   - "Helper Code Snippets" / "Reference Resources" / "Supporting Files" —
 *     plugin-API code and cross-references to kit files we do not ship.
 *
 * REFERENCES are imported whole: they are method documents, and the stale
 * API names they occasionally use are reframed at fetch time by the preamble
 * in `agent-skills` (same treatment the bodies get).
 *
 * ## What is skipped entirely
 *
 *   - penpot-router: the dispatcher; the native agent routes via its own
 *     skills index in the system prompt.
 *   - penpot-design-md: the native project-vibes skill owns DESIGN.md with a
 *     playbook written for the native tools.
 *
 * Usage: node frontend/scripts/import-aikit.mjs [path-to-penpot-ai-kit-checkout]
 */
import { readFileSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import { join, dirname, basename } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const kitDir = process.argv[2] ?? join(here, "..", "..", "..", "penpot-ai-kit");
const outDir = join(here, "..", "src", "app", "main", "data", "workspace");

if (!existsSync(join(kitDir, "skills.json"))) {
  console.error(`penpot-ai-kit checkout not found at ${kitDir}`);
  process.exit(1);
}

const SKIP = /^penpot-(router|design-md)$/;

/** Sections dropped from every body — see the header comment. */
const DROP_SECTION =
  /^##\s*\d+\.\s*(\(intentionally merged[^)]*\)\s*)?(Penpot MCP Tool Reference|Plugin API Essentials|Modes & Policies|State Management|Naming Conventions|Helper Code Snippets|Reference Resources|Supporting Files)/i;

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

/** Splits a kit SKILL.md into { meta, body }. */
function parseKitSkill(source) {
  const m = source.match(/^\s*---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
  const meta = {};
  if (m) {
    for (const line of m[1].split(/\r?\n/)) {
      const kv = line.match(/^([A-Za-z][\w-]*)\s*:\s*(.*)$/);
      if (kv) meta[kv[1]] = kv[2].trim().replace(/^["']|["']$/g, "");
    }
  }
  return { meta, body: (m ? m[2] : source).trim() };
}

const oneLine = (s) => s.replace(/\s+/g, " ").trim();

/** Rebuilds the flat frontmatter our skill format carries. */
function makeSkill({ name, description, mode, body, origin }) {
  return [
    "---",
    `name: ${name}`,
    "scope: platform",
    "enforcement: advisory",
    ...(mode ? [`mode: ${mode}`] : []),
    `origin: penpot-ai-kit/${origin}`,
    `description: ${oneLine(description)}`,
    "---",
    "",
    body,
  ].join("\n");
}

// The agent's tool-result cap (agent/max-tool-result-chars) silently truncates
// at 20000 chars, and text is JSON-encoded on the way out (a newline costs 2).
// A document that quietly loses its tail is worse than one that fails to
// generate — guard at build time, where a human sees it.
const MAX_ENCODED = 20000;
const BUDGET = 0.85; // room for the fetch-time preamble + surrounding JSON

function guardSize(kind, name, text) {
  const encoded = JSON.stringify(text).length;
  if (encoded > MAX_ENCODED * BUDGET) {
    console.error(
      `\nERROR: ${kind} ${name} is ${encoded} encoded chars (cap ${Math.round(
        MAX_ENCODED * BUDGET,
      )}) and would be truncated by the tool-result cap. Strip more, or split it.\n`,
    );
    process.exit(1);
  }
}

const kitManifest = JSON.parse(readFileSync(join(kitDir, "skills.json"), "utf8"));

const bodies = [];
const refs = [];
let before = 0;
let after = 0;

for (const entry of kitManifest.skills) {
  if (SKIP.test(entry.id)) continue;

  const source = readFileSync(join(kitDir, entry.path), "utf8");
  const { meta, body } = parseKitSkill(source);
  const stripped = stripSections(body);
  const skill = makeSkill({
    name: entry.id,
    description: meta.description || entry.description,
    mode: entry.mode ?? meta["mode-default"],
    body: stripped,
    origin: entry.path,
  });
  guardSize("body", entry.id, skill);
  before += source.length;
  after += skill.length;
  bodies.push([entry.id, skill]);

  const refDir = join(dirname(join(kitDir, entry.path)), "references");
  if (existsSync(refDir)) {
    const skillRefs = [];
    for (const file of readdirSync(refDir).filter((f) => f.endsWith(".md")).sort()) {
      const text = readFileSync(join(refDir, file), "utf8").trim();
      const key = basename(file, ".md");
      guardSize("reference", `${entry.id}/${key}`, text);
      skillRefs.push([key, text]);
    }
    if (skillRefs.length) refs.push([entry.id, skillRefs]);
  }
}

const license = [
  ";; This Source Code Form is subject to the terms of the Mozilla Public",
  ";; License, v. 2.0. If a copy of the MPL was not distributed with this",
  ";; file, You can obtain one at http://mozilla.org/MPL/2.0/.",
  ";;",
  ";; Copyright (c) KALEIDOS INC Sucursal en España SL",
  "",
  ";; GENERATED by frontend/scripts/import-aikit.mjs — do not edit by hand.",
  ";; Source: the official penpot-ai-kit (https://github.com/penpot/penpot-ai-kit).",
];

writeFileSync(
  join(outDir, "aikit_bodies.cljs"),
  [
    ...license,
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
    ...bodies.map(([name, body]) => `   ${JSON.stringify(name)}\n   ${JSON.stringify(body)}`),
    "   })",
    "",
  ].join("\n"),
);

writeFileSync(
  join(outDir, "aikit_refs.cljs"),
  [
    ...license,
    ";; Imported WHOLE (unlike the bodies): references are the method layer the",
    ";; playbooks cite — layout composition, style profiles, component recipes,",
    ";; critique frameworks — and the fetch-time preamble reframes their stale",
    ";; API names, same as it does for the bodies.",
    "",
    "(ns app.main.data.workspace.aikit-refs",
    '  "Per-skill reference documents, the second disclosure level of',
    '  `get_design_skills`: the index is free, the body is one fetch, and each',
    '  reference the body cites is one more fetch — nothing loads unrequested.")',
    "",
    "(def references",
    "  {",
    ...refs.map(
      ([name, skillRefs]) =>
        `   ${JSON.stringify(name)}\n   {${skillRefs
          .map(([key, text]) => `${JSON.stringify(key)}\n    ${JSON.stringify(text)}`)
          .join("\n    ")}}`,
    ),
    "   })",
    "",
  ].join("\n"),
);

const pct = Math.round(100 - (100 * after) / before);
const refCount = refs.reduce((n, [, r]) => n + r.length, 0);
const refChars = refs.reduce((n, [, r]) => n + r.reduce((m, [, t]) => m + t.length, 0), 0);
console.log(`aikit_bodies.cljs: ${bodies.length} skills`);
console.log(`  ${before} → ${after} chars (${pct}% stripped, ~${Math.round(after / 4)} tokens total)`);
for (const [name, body] of bodies) {
  console.log(`  ${name.padEnd(30)} ${String(body.length).padStart(6)}`);
}
console.log(`aikit_refs.cljs: ${refCount} references, ${refChars} chars`);
for (const [name, skillRefs] of refs) {
  console.log(`  ${name.padEnd(30)} ${skillRefs.map(([k]) => k).join(", ")}`);
}
