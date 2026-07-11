import { test } from "node:test";
import assert from "node:assert/strict";
import {
  SkillViolationError,
  assertFillsAllowed,
  collectAllowedColors,
  guardPenpot,
  isSkillEnforcedInSources,
  normalizeHex,
  type LocalLibraryLike,
} from "./guard";

const lib: LocalLibraryLike = {
  tokens: {
    sets: [
      {
        active: true,
        tokens: [
          { name: "color.brand.primary", type: "color", resolvedValueString: "#6366F1" },
          { name: "spacing.4", type: "spacing", value: "16" },
        ],
      },
      {
        active: false,
        tokens: [{ name: "color.inactive", type: "color", resolvedValueString: "#000000" }],
      },
    ],
  },
  colors: [{ name: "Paper", color: "#fff" }],
};

test("collectAllowedColors: active color tokens + library colors, normalized", () => {
  const allowed = collectAllowedColors(lib);
  assert.deepEqual(allowed, [
    { value: "#6366f1", label: "color.brand.primary", kind: "token" },
    { value: "#ffffff", label: "Paper", kind: "library-color" },
  ]);
});

test("collectAllowedColors tolerates a library without a tokens catalog", () => {
  const allowed = collectAllowedColors({ colors: [{ name: "Ink", color: "#111111" }] });
  assert.deepEqual(allowed, [{ value: "#111111", label: "Ink", kind: "library-color" }]);
});

test("assertFillsAllowed: token-valued fill passes, raw hex throws citing the rule", () => {
  const allowed = collectAllowedColors(lib);
  assertFillsAllowed([{ fillColor: "#6366F1" }], allowed);
  assert.throws(
    () => assertFillsAllowed([{ fillColor: "#ff0000" }], allowed),
    (e: unknown) =>
      e instanceof SkillViolationError &&
      e.rule === "token-only-colors" &&
      e.message.includes("#ff0000") &&
      e.message.includes("color.brand.primary"),
  );
});

test("guardPenpot gates fills assignments everywhere in the object graph", () => {
  const shape = { name: "hero", fills: [] as unknown[] };
  const page = {
    shapes: [shape],
    getShape: (name: string) => (name === shape.name ? shape : null),
  };
  let active = true;
  const guarded = guardPenpot(page, {
    isRuleActive: () => active,
    collectAllowed: () => collectAllowedColors(lib),
  });

  // via array element
  assert.throws(() => {
    guarded.shapes[0].fills = [{ fillColor: "#ff0000" }];
  }, SkillViolationError);

  // via method return value
  assert.throws(() => {
    guarded.getShape("hero")!.fills = [{ fillColor: "#123456" }];
  }, SkillViolationError);

  // allowed color passes and reaches the raw target
  guarded.shapes[0].fills = [{ fillColor: "#ffffff" }];
  assert.deepEqual(shape.fills, [{ fillColor: "#ffffff" }]);

  // rule off → raw hex passes
  active = false;
  guarded.shapes[0].fills = [{ fillColor: "#ff0000" }];
  assert.deepEqual(shape.fills, [{ fillColor: "#ff0000" }]);
});

test("guardPenpot unwraps proxied arguments so the real API sees its own objects", () => {
  const child = { kind: "child" };
  let received: unknown;
  const api = {
    child,
    accept(arg: unknown) {
      received = arg;
      return arg;
    },
  };
  const guarded = guardPenpot(api, { isRuleActive: () => false, collectAllowed: () => [] });
  const wrappedChild = guarded.child;
  assert.notEqual(wrappedChild, child, "reads come back wrapped");
  guarded.accept(wrappedChild);
  assert.equal(received, child, "arguments are unwrapped before reaching the target");
});

test("guardPenpot names read-only properties in the error", () => {
  const shape = {};
  Object.defineProperty(shape, "width", { value: 10, writable: false });
  const guarded = guardPenpot(shape as { width: number }, {
    isRuleActive: () => false,
    collectAllowed: () => [],
  });
  assert.throws(
    () => {
      guarded.width = 20;
    },
    (e: unknown) =>
      e instanceof TypeError && e.message.includes('"width"') && e.message.includes("resize"),
  );
});

test("isSkillEnforcedInSources reads enforcement from raw pluginData", () => {
  const enforced = JSON.stringify([
    "---\nname: token-only-colors\nscope: file\nenforcement: enforced\ndescription: x\n---\nBody",
  ]);
  const advisory = JSON.stringify([
    "---\nname: token-only-colors\nscope: file\nenforcement: advisory\ndescription: x\n---\nBody",
  ]);
  assert.equal(isSkillEnforcedInSources(enforced, "token-only-colors"), true);
  assert.equal(isSkillEnforcedInSources(advisory, "token-only-colors"), false);
  assert.equal(isSkillEnforcedInSources(enforced, "other-rule"), false);
  assert.equal(isSkillEnforcedInSources(null, "token-only-colors"), false);
  assert.equal(isSkillEnforcedInSources("not json", "token-only-colors"), false);
});

test("normalizeHex expands shorthand and lowercases", () => {
  assert.equal(normalizeHex(" #FFF "), "#ffffff");
  assert.equal(normalizeHex("#A1B2C3"), "#a1b2c3");
});
