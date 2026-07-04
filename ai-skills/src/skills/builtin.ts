/**
 * Platform / org / project scope skills.
 *
 * In the full vision these come from a curated platform set (with approval
 * process), the organization, and the project — here they are stubs bundled
 * with the runtime, exactly as the prototype spec calls for. File-scope
 * skills are the real thing: they live in the design file's pluginData.
 */

export const PLATFORM_SKILLS: string[] = [
  `---
name: a11y-contrast
scope: platform
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
tighten it but never loosen it.`,
];

export const ORG_SKILLS: string[] = [
  `---
name: design-documentation
scope: org
enforcement: advisory
description: Boards intended for handoff carry a short annotation of purpose and interaction notes; components reference their code counterpart.
---

# Documentation conventions

When a screen or component is ready for handoff, add a short annotation text
below the board: what it is, key interactions, and open questions. Components
that exist in code should mention their code path (e.g. \`ui/button\`) so
engineers and tools like Playwright test generators can cross-reference them.`,
];

export const PROJECT_SKILLS: string[] = [
  `---
name: spacing-and-type
scope: project
enforcement: advisory
description: 8px spacing grid; type scale 12/14/16/20/24/32; Inter or Work Sans; friendly, concise microcopy.
---

# Look and feel

- All spacing and sizes sit on an **8px grid** (4px allowed inside dense
  components).
- Type scale: 12, 14, 16, 20, 24, 32. Body text is 14 or 16.
- Prefer the file's typography library; default families are Inter/Work Sans.
- Microcopy tone: friendly and concise, sentence case, no exclamation marks.`,
];
