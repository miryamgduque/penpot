/**
 * Default file-scope skills, written into a design file's shared pluginData
 * the first time the Skills plugin opens it. From then on the file itself is
 * the source of truth — editable in the Skills panel, versioned with the file,
 * and readable by any other tool (including the MCP server) via
 * getSharedPluginData("penpot-skills", "skills").
 */

export const FILE_SEED_SKILLS: string[] = [
  `---
name: token-only-colors
scope: file
kind: rule
enforcement: enforced
trigger: fill-change
description: Fills and strokes must use color tokens or library colors from this file — raw hex values are rejected at the write path.
---

# Token-only colors

Never set a raw hex color on a shape. Every fill and stroke color must come
from this file's design tokens (or library colors). Use \`get_color_tokens\`
to list what is available and apply colors by token name.

This rule is **enforced**: the write path rejects any fill or stroke whose
color does not resolve to a token, no matter which agent or model issued it.`,

  `---
name: layer-naming
scope: file
kind: rule
enforcement: triggered
trigger: rename
description: Layers use semantic, lowercase names (hero, nav/item, cta-button) — never default names like "Rectangle 5".
---

# Semantic layer names

Name layers after their role, not their geometry: \`hero\`, \`nav/item\`,
\`cta-button\`, \`price-card\`. Lowercase, hyphenated, use \`/\` for grouping.
Default names ("Rectangle 5", "Board 2", "Ellipse") should be renamed as soon
as the shape's purpose is known.`,
];
