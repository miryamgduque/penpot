---
name: devenv-operations
description: Start, check, or restart the Penpot devenv Docker container, verify a frontend/backend build is ready, fix the SCSS/icon-asset watch going stale, and run the pull-rebase-push routine for this branch. Triggers: "start the devenv", "is the devenv up", "restart the container", "new SCSS/icon not showing up", "pull rebase push", "check the build".
---

<!-- TRANSIENT dev tooling — committed only so collaborators on this branch can
     see it. DELETE THIS SKILL'S OWN DIRECTORY before the PR merges; it is not
     part of the Penpot product. Do NOT delete `.agents/skills/` or the
     `.claude/skills` symlink: those are upstream's and hold real project
     skills. The branch's transient ones are the directories carrying this
     banner. -->

# Penpot devenv operations

The dev environment runs in the Docker container `penpot-devenv-ws0-main`, with the
dev servers in tmux windows.

## tmux windows (session `penpot`)
| # | Window | What |
|---|---|---|
| 0 | frontend watch | `./scripts/watch app` — shadow-cljs + SCSS/sprite build |
| 1 | frontend storybook | |
| 2 | exporter | |
| 3 | backend | `./scripts/start-dev` — JVM API on :6060 |

## Check status
```bash
sg docker -c 'docker ps -a --filter "name=penpot-devenv" --format "{{.Names}}\t{{.Status}}"'
sg docker -c 'docker exec -u penpot penpot-devenv-ws0-main bash -lc "tmux list-windows -t penpot"'
```

## Start it (incl. after it was stopped / OOM-killed)
Exit code **137** = OOM/SIGKILL — the container died, just start it again. From the repo root:
```bash
sg docker -c './manage.sh run-devenv --ws 0'
```
A **cold start is slow**: the render-wasm Rust module (`skia-safe`) compiles (10–20 min)
and the backend JVM boots. Wait for both before using the app.

## Verify it's ready
```bash
# frontend cljs build (window 0)
sg docker -c 'docker exec -u penpot penpot-devenv-ws0-main bash -lc \
  "tmux capture-pane -t penpot:0 -p -S -30 | grep -iE \"Build completed|error\" | tail -2"'

# backend (from INSIDE the container — :6060 is not exposed to the host)
sg docker -c 'docker exec -u penpot penpot-devenv-ws0-main bash -lc \
  "curl -s -o /dev/null -w %{http_code} http://localhost:6060/readyz"'   # -> 200

# frontend HTTP
curl -sk -o /dev/null -w "%{http_code}\n" https://localhost:3449/         # -> 200
```

## Open the app
- **https://localhost:3449** — the real HMR dev server (self-signed cert; accept it once).
- **http://localhost:3450** — plain HTTP entrypoint (handy when a tool can't get past the cert).
- The in-app `:5390` preview proxy can't reach the wss, so it shows "Reconnecting" — use `:3449` and reload manually.

## Fix: new SCSS / icon files not showing up
The dev asset watch keeps a **stale in-memory file glob**, so newly-added `.scss` or icon
files get dropped from `main.css` / the sprite on later rebuilds. Two fixes:
```bash
# one-off re-glob
sg docker -c 'docker exec -u penpot penpot-devenv-ws0-main bash -lc \
  "cd /home/penpot/penpot/frontend && node scripts/build-app-assets.js"'
# durable: restart the frontend watch so it re-globs
#   (in tmux window 0: Ctrl-C, then ./scripts/watch app)
```

## Quality gate before committing
Shadow-cljs `0 warnings` **and** clj-kondo clean:
```bash
sg docker -c 'docker exec -u penpot -w /home/penpot/penpot/frontend penpot-devenv-ws0-main bash -lc \
  "clj-kondo --lint <changed .cljs files>"'
```
If clj-kondo reports a bogus arity error after a merge: `rm -rf .clj-kondo/.cache`.

## Pull-rebase-push routine
Local branch `ai-skills-prototype` ≠ upstream name, so push with an explicit refspec:
```bash
cd frontend    # or repo root
git pull --rebase
git diff --name-only --diff-filter=U        # confirm no conflicts
git push origin HEAD:feature/ai-skills-prototype
git status -sb | head -1                     # confirm in sync
```
After a pull that changed source, re-check the build is still `0 warnings` (above).

## Never commit
The untracked `ai-skills/` directory (node_modules + dist, ~89 MB). Stage explicit paths, not `git add .`.
