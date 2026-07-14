import { test } from "node:test";
import assert from "node:assert/strict";
import { parseSkill } from "./parse";
import {
  builtinCatalog,
  categoryForMode,
  defaultEnabledForMode,
  exampleTriggerOf,
  toCatalogEntry,
} from "./catalog";

/* ---- parse.ts: mode is now read from frontmatter ---- */

test("parseSkill reads a valid mode", () => {
  const s = parseSkill(`---\nname: x\nmode: review\ndescription: d\n---\nbody`);
  assert.equal(s.mode, "review");
});

test("parseSkill leaves mode undefined when absent or invalid", () => {
  assert.equal(parseSkill(`---\nname: x\ndescription: d\n---\nb`).mode, undefined);
  assert.equal(parseSkill(`---\nname: x\nmode: nonsense\ndescription: d\n---\nb`).mode, undefined);
});

/* ---- pure helpers ---- */

test("categoryForMode maps mode → display category", () => {
  assert.equal(categoryForMode("suggest"), "Audits");
  assert.equal(categoryForMode("review"), "Build");
  assert.equal(categoryForMode("autofix"), "Auto-fix");
});

test("defaultEnabledForMode: only autofix ships off", () => {
  assert.equal(defaultEnabledForMode("suggest"), true);
  assert.equal(defaultEnabledForMode("review"), true);
  assert.equal(defaultEnabledForMode("autofix"), false);
});

test("exampleTriggerOf pulls the first quoted phrase from a Triggers list, capitalized", () => {
  const desc =
    "Audit a Penpot design against WCAG. Triggers: 'check accessibility', 'accessibility audit', 'WCAG check'.";
  assert.equal(exampleTriggerOf(desc), "Check accessibility.");
});

test("exampleTriggerOf keeps an existing terminal punctuation", () => {
  const desc = "Do the thing. Triggers: 'is this accessible?', 'a11y review'.";
  assert.equal(exampleTriggerOf(desc), "Is this accessible?");
});

test("exampleTriggerOf falls back to the first sentence when no Triggers list", () => {
  const desc = "Sets up design tokens for the file. Then more detail follows.";
  assert.equal(exampleTriggerOf(desc), "Sets up design tokens for the file.");
});

/* ---- toCatalogEntry: exclusion + shape ---- */

test("toCatalogEntry excludes the router even though it has a mode", () => {
  const router = parseSkill(`---\nname: penpot-router\nmode: suggest\ndescription: dispatch\n---\nb`);
  assert.equal(toCatalogEntry(router), null);
});

test("toCatalogEntry excludes skills with no mode (shared/core docs)", () => {
  const shared = parseSkill(`---\nname: penpot-naming-conventions\ndescription: names\n---\nb`);
  assert.equal(toCatalogEntry(shared), null);
});

test("toCatalogEntry produces a full entry for a normal skill", () => {
  const s = parseSkill(
    `---\nname: penpot-audit-accessibility\nmode: suggest\ndescription: Audit. Triggers: 'check accessibility', 'x'.\n---\nb`,
  );
  assert.deepEqual(toCatalogEntry(s), {
    name: "penpot-audit-accessibility",
    category: "Audits",
    mode: "suggest",
    description: "Audit. Triggers: 'check accessibility', 'x'.",
    example: "Check accessibility.",
    defaultEnabled: true,
  });
});

/* ---- builtinCatalog: real bundled set ---- */

const catalog = builtinCatalog();
const names = catalog.map((e) => e.name);

test("builtinCatalog includes the three audit skills", () => {
  for (const n of [
    "penpot-audit-accessibility",
    "penpot-audit-tokens",
    "penpot-design-to-code-review",
  ]) {
    assert.ok(names.includes(n), `expected ${n} in catalog`);
    assert.equal(catalog.find((e) => e.name === n)!.category, "Audits");
  }
});

test("builtinCatalog includes the six build skills as Build", () => {
  for (const n of [
    "penpot-foundations",
    "penpot-component-factory",
    "penpot-build-screen",
    "penpot-build-from-code",
    "penpot-document-handoff",
    "penpot-migrate",
  ]) {
    assert.ok(names.includes(n), `expected ${n} in catalog`);
    assert.equal(catalog.find((e) => e.name === n)!.category, "Build");
  }
});

test("builtinCatalog includes rename-layers as Auto-fix, default off", () => {
  const rename = catalog.find((e) => e.name === "penpot-rename-layers");
  assert.ok(rename, "expected penpot-rename-layers in catalog");
  assert.equal(rename!.category, "Auto-fix");
  assert.equal(rename!.defaultEnabled, false);
});

test("builtinCatalog hides the router and the shared/core docs", () => {
  for (const n of [
    "penpot-router",
    "penpot-plugin-api-gotchas",
    "penpot-naming-conventions",
    "penpot-operating-modes",
    "penpot-mcp-tool-reference",
  ]) {
    assert.ok(!names.includes(n), `${n} must not appear as a catalog card`);
  }
});

test("builtinCatalog is exactly the 10 expected entries, all well-formed", () => {
  assert.equal(catalog.length, 10);
  for (const e of catalog) {
    assert.ok(e.name && e.description && e.example, `entry ${e.name} is fully populated`);
    assert.ok(e.defaultEnabled === (e.mode !== "autofix"));
  }
});
