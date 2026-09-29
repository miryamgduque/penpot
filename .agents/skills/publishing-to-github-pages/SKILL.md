---
name: publishing-to-github-pages
description: Publish a self-contained static HTML site (e.g. the pitch/deck) as a shareable GitHub Pages site via a gh-pages orphan branch, without touching the working tree or main branch history. Triggers: "publish this to GitHub Pages", "deploy the pitch/deck", "set up gh-pages", "share a rendered link to this HTML".
---

<!-- TRANSIENT dev tooling — committed only so collaborators on this branch can
     see it. DELETE THIS SKILL'S OWN DIRECTORY before the PR merges; it is not
     part of the Penpot product. Do NOT delete `.agents/skills/` or the
     `.claude/skills` symlink: those are upstream's and hold real project
     skills. The branch's transient ones are the directories carrying this
     banner. -->

# Publishing a static site as a shareable GitHub Pages site

Worked example: the pitch/deck at `__piweek/feature-ai-skills-prototype/`
(`index.html`, `deck.html`, `oss-agent-survey.html`, `design-tools-comparison.html` —
inline CSS/JS/SVG favicon, no external assets). Generalize the file list to whatever
you're publishing.

## Why not the obvious routes
- **A GitHub file link shows source, not a rendered page** — GitHub won't serve raw `.html` as a live site.
- **Pages from a branch subfolder starting with `_` won't work**: Jekyll (Pages' default) ignores anything starting with `_`; it would also serve the whole monorepo.

## The approach: a `gh-pages` orphan branch with the site at root
Built with git plumbing so the **working tree and main index are never touched** (safe in a big monorepo):

```bash
cd <repo root>
sitedir=<path/to/site/dir>
tmpidx=$(mktemp); rm -f "$tmpidx"; export GIT_INDEX_FILE="$tmpidx"
git read-tree --empty
for f in index.html deck.html oss-agent-survey.html design-tools-comparison.html; do
  blob=$(git hash-object -w "$sitedir/$f"); git update-index --add --cacheinfo 100644,"$blob","$f"
done
nj=$(printf '' | git hash-object -w --stdin); git update-index --add --cacheinfo 100644,"$nj",.nojekyll
tree=$(git write-tree)
parent=$(git rev-parse -q --verify refs/heads/gh-pages || true)   # empty first time
commit=$(git commit-tree "$tree" ${parent:+-p "$parent"} \
  -m ":rocket: Deploy site" \
  -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>")
git update-ref refs/heads/gh-pages "$commit"
unset GIT_INDEX_FILE; rm -f "$tmpidx"
git push origin gh-pages
```

## Enable Pages (one-time, web UI — can't be done from the CLI)
Settings → Pages → **Deploy from a branch** → Branch `gh-pages`, Folder `/ (root)` → Save.
Live at **`https://<user>.github.io/<repo>/`** (the first file listed is served at the root).

## Updating after edits
Re-run the plumbing block above (it auto-detects the parent) and push; Pages redeploys in ~1 min.
Keep source in sync too — commit the edited files on the source branch:
```bash
git add <sitedir>/<file>.html
git commit -m ":wastebasket: …" && git push origin <source-branch>
```

## Gotchas
- **`.nojekyll` is required** — without it Jekyll drops `_*` paths and may choke on the build; with it, files serve verbatim.
- **The fork/repo must be public** for free Pages (or a paid plan).
- **Relative cross-links work** only because all files sit at the branch root together.
- **Cache**: hard-refresh (Cmd/Ctrl+Shift+R) to see an update; during dev append `?v=N` to the URL to bust it.
- The plumbing (temp `GIT_INDEX_FILE` + `commit-tree`) never checks the branch out, so it can't disturb your working copy.
