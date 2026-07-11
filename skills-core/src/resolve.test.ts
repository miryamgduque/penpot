import { test } from "node:test";
import assert from "node:assert/strict";
import { parseSkill } from "./parse";
import { resolveCascade, skillManifest } from "./resolve";

const platformMandatory = parseSkill(`---
name: a11y-contrast
scope: platform
enforcement: enforced
mandatory: true
description: platform floor
---
Platform body`);

const fileLoosened = parseSkill(`---
name: a11y-contrast
scope: file
enforcement: advisory
description: file tries to loosen
---
File body`);

const fileOnly = parseSkill(`---
name: token-only-colors
scope: file
enforcement: enforced
trigger: fill-change
description: tokens only
---
Body`);

const projectAdvisory = parseSkill(`---
name: spacing
scope: project
enforcement: advisory
description: 8px grid
---
Body`);

test("frontmatter parsing", () => {
  assert.equal(fileOnly.name, "token-only-colors");
  assert.equal(fileOnly.scope, "file");
  assert.equal(fileOnly.enforcement, "enforced");
  assert.equal(fileOnly.trigger, "fill-change");
  assert.equal(platformMandatory.mandatory, true);
});

test("kind: explicit frontmatter wins, otherwise inferred from enforcement", () => {
  assert.equal(fileOnly.kind, "rule", "enforced → rule");
  assert.equal(projectAdvisory.kind, "skill", "advisory → skill");
  const explicit = parseSkill(
    "---\nname: contrast\nkind: rule\nenforcement: advisory\ndescription: x\n---\nBody",
  );
  assert.equal(explicit.kind, "rule", "explicit kind overrides inference");
});

test("disabled definitions are skipped; the next enabled definition wins", () => {
  const org = parseSkill(
    "---\nname: tone\nscope: org\nenforcement: advisory\ndescription: org tone\n---\nOrg body",
  );
  const file = parseSkill(
    "---\nname: tone\nscope: file\nenforcement: advisory\ndescription: file tone\n---\nFile body",
  );

  const fileDisabled = resolveCascade([org, file], { file: ["tone"] });
  const tone = fileDisabled.find((s) => s.name === "tone")!;
  assert.equal(tone.definedAt, "org", "org definition wins when file's is disabled");
  assert.equal(tone.body, "Org body");
  assert.equal(tone.disabled, undefined);

  const allDisabled = resolveCascade([org, file], { file: ["tone"], org: ["tone"] });
  const gone = allDisabled.find((s) => s.name === "tone")!;
  assert.equal(gone.disabled, true, "fully disabled skills stay listed but flagged");
});

test("mandatory skills cannot be disabled; manifest excludes disabled skills", () => {
  const effective = resolveCascade([platformMandatory, projectAdvisory], {
    platform: ["a11y-contrast"],
    project: ["spacing"],
  });
  const a11y = effective.find((s) => s.name === "a11y-contrast")!;
  assert.equal(a11y.disabled, undefined, "mandatory ignores the disable");
  assert.equal(effective.find((s) => s.name === "spacing")!.disabled, true);

  const manifest = skillManifest(effective);
  assert.deepEqual(
    manifest.map((m) => m.name),
    ["a11y-contrast"],
  );
});

test("specific scope wins body, mandatory enforcement propagates down", () => {
  const effective = resolveCascade([platformMandatory, fileLoosened, fileOnly, projectAdvisory]);
  const a11y = effective.find((s) => s.name === "a11y-contrast")!;
  assert.equal(a11y.body, "File body", "file body wins");
  assert.equal(a11y.definedAt, "file");
  assert.equal(a11y.enforcement, "enforced", "mandatory platform enforcement cannot be loosened");
  assert.equal(a11y.enforcementRaisedBy, "platform");
  assert.deepEqual(a11y.overrides, ["platform"]);
  assert.equal(a11y.mandatory, true);
});

test("non-mandatory skills cascade normally and sort enforced-first", () => {
  const effective = resolveCascade([projectAdvisory, fileOnly]);
  assert.equal(effective[0].name, "token-only-colors");
  const spacing = effective.find((s) => s.name === "spacing")!;
  assert.equal(spacing.enforcement, "advisory");
  assert.equal(spacing.enforcementRaisedBy, undefined);
});
