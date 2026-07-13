<!-- TRANSIENT — delete this whole folder before the PR is finalized. -->

# `tmp/` — transient team scratch

Shared documents and artifacts for the people working on `feature/ai-skills-prototype`.
**Not part of the product. Delete this whole folder before the PR moves forward.**

> Note: penpot's `.gitignore` ignores `/tmp/`, so these files are **force-added** — they're committed
> and visible on the branch, but `git status` won't flag local edits to them. Treat them as snapshots;
> re-`git add -f` if you change one.

## Contents

| File | What it is |
|------|-----------|
| [`skills-pitch.html`](skills-pitch.html) | Shareable pitch page — what the branch does, its value, a Cursor comparison, and a plain-language explainer. Open in a browser. Also published as a private Artifact. |
| [`skill-loading-strategy.md`](skill-loading-strategy.md) | Analysis: which skills should be always-in-the-meta-prompt vs invoked on demand, and a proposed `load` field to make it author-controlled. |

Related (kept in place, not here): [`../ai-skills/BRANCH_NOTES.md`](../ai-skills/BRANCH_NOTES.md) — run steps,
gotchas, status, and the cleanup checklist.
