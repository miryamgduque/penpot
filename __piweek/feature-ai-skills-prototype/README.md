<!-- TRANSIENT — delete this whole folder before the PR is finalized. -->

# `__piweek/feature/ai-skills-prototype/` — transient team scratch

Shared documents and artifacts for the people working on `feature/ai-skills-prototype`.
**Not part of the product. Delete the `__piweek/` folder before the PR moves forward.**

## Contents

| File | What it is |
|------|-----------|
| [`deck/penpot-ai-agent-deck.html`](deck/penpot-ai-agent-deck.html) | Presentation deck — the development approaches bound to product, the native-vs-MCP-vs-plugin scorecard, and deployment/infra options (BYOK, Penpot-hosted, client-hosted OSS models). Open in a browser; arrow keys navigate; print to PDF for slides. |
| [`skills-pitch.html`](skills-pitch.html) | Shareable pitch page — what the branch does, its value, a Cursor comparison, and a plain-language explainer. Open in a browser. Also published as a private Artifact. |
| [`docs/oss-agent-survey.md`](docs/oss-agent-survey.md) | Eleven OSS agents surveyed from source; ranked adoption list mapped to our measured weaknesses. |
| [`docs/measuring-agent-efficiency.md`](docs/measuring-agent-efficiency.md) | The calls/round + cost measurement recipe, historical baseline, and the parallelism A/B protocol. |
| [`docs/plans/`](docs/plans/) | Phased implementation plans (active + `completed/`). |
| [`docs/*-postmortem.md`](docs/) | The Kahoot and NYT session postmortems that drive the tool/context roadmaps. |

The prototype React app (`ai-skills/`, including its `BRANCH_NOTES.md` and the aikit
import/seed generator scripts) was removed from the branch on 2026-07-16; recover it from git
history if needed.

The branch summary, the skill-loading-strategy analysis, the procedures/ how-tos and the
session handoff/chronology docs that used to live here have been distilled into project-scoped
skills (`.claude/skills/`) and Claude memory; recover the originals from git history if needed.
