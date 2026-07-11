#!/usr/bin/env node
/**
 * Generates the backend seed for app-level (instance-wide) design skills:
 *   backend/resources/app/design-skills-seed.json
 *
 * Source of truth is the committed skills-core/src/aikit.gen.ts (produced by
 * import-aikit.mjs from a penpot-ai-kit checkout) plus the a11y-contrast
 * platform floor. Run directly, or via import-aikit.mjs which calls
 * generateSeed() after regenerating aikit.gen.ts.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

/** Splits a skill markdown source into { meta, body } (flat frontmatter). */
function parseSource(source) {
  const m = source.match(/^\s*---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/);
  const meta = {};
  if (m) {
    for (const line of m[1].split(/\r?\n/)) {
      const kv = line.match(/^([A-Za-z][\w-]*)\s*:\s*(.*)$/);
      if (kv) meta[kv[1].toLowerCase()] = kv[2].trim().replace(/^["']|["']$/g, "");
    }
  }
  return { meta, body: (m ? m[2] : source).trim() };
}

function toRow(source) {
  const { meta, body } = parseSource(source);
  const enforcement = ["advisory", "triggered", "enforced"].includes(meta.enforcement)
    ? meta.enforcement
    : "advisory";
  return {
    name: meta.name,
    kind: ["skill", "rule"].includes(meta.kind)
      ? meta.kind
      : enforcement === "advisory"
        ? "skill"
        : "rule",
    enforcement,
    "is-mandatory": meta.mandatory === "true",
    "trigger-on": meta.trigger || null,
    description: meta.description || body.split(/\r?\n/)[0].slice(0, 200),
    body,
    origin: meta.origin || null,
  };
}

const A11Y_FLOOR = `---
name: a11y-contrast
scope: platform
kind: rule
enforcement: advisory
mandatory: true
description: Text and interactive elements must meet WCAG 2.1 AA contrast (4.5:1 body text, 3:1 large text and UI components).
---

# Accessibility contrast floor

Every text element must have a contrast ratio of at least **4.5:1** against its
background (3:1 for text larger than 24px, and for interactive component
boundaries). When choosing token pairs, verify the resolved values. If a
requested change would drop below the floor, prefer the nearest darker/lighter
token and say so. This is a platform-level floor: file or project skills may
tighten it but never loosen it.`;

export function generateSeed() {
  const genPath = join(here, "..", "..", "skills-core", "src", "aikit.gen.ts");
  const ts = readFileSync(genPath, "utf8");
  // the module is `export const AIKIT_PLATFORM_SKILLS: string[] = <json array>;`
  const start = ts.indexOf("[", ts.indexOf("="));
  const end = ts.lastIndexOf("]");
  const sources = JSON.parse(ts.slice(start, end + 1));

  const rows = [...sources, A11Y_FLOOR].map(toRow);
  const out = join(here, "..", "..", "backend", "resources", "app", "design-skills-seed.json");
  writeFileSync(out, JSON.stringify(rows, null, 2) + "\n");
  console.log(`wrote ${rows.length} app-scope skill rows to ${out}`);
  return rows.length;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  generateSeed();
}
