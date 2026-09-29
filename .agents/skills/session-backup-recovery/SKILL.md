---
name: session-backup-recovery
description: Back up and recover Claude Code sessions, especially after an account or organization switch hides them from the desktop app's session list. Triggers: "backup my Claude Code sessions", "my sessions disappeared", "recover a Claude Code session", "resume an old session after switching orgs".
---

<!-- TRANSIENT dev tooling — committed only so collaborators on this branch can
     see it. DELETE THIS SKILL'S OWN DIRECTORY before the PR merges; it is not
     part of the Penpot product. Do NOT delete `.agents/skills/` or the
     `.claude/skills` symlink: those are upstream's and hold real project
     skills. The branch's transient ones are the directories carrying this
     banner. -->

# Backing up & recovering Claude Code sessions

## Where sessions live
Every Claude Code session is a local JSON-lines transcript, stored **per machine**
(not synced to the cloud, not tied to an account at the file level):
```
~/.claude/projects/<encoded-project-path>/<session-id>.jsonl
```
The encoded path is the project dir with `/` → `-` (e.g. `-home-miryamgduque-Desktop-Projects-penpot`).

## Back them all up (non-destructive)
```bash
BK=~/claude-sessions-backup/$(date +%F)
mkdir -p "$BK"
cp -r ~/.claude/projects/. "$BK"/
find "$BK" -name '*.jsonl' | wc -l      # how many transcripts
du -sh "$BK"
```

## List / index what you have
```bash
find ~/.claude/projects/ -name '*.jsonl' \
  -printf '%TY-%Tm-%Td %TH:%TM  %10s  %p\n' 2>/dev/null | sort -r
```
For a readable index (first real user message per session), a small Python pass over
each `.jsonl` extracting the first `type:"user"` text message works well — see the
generated `SESSIONS.md` for the format.

## Resume a session
```bash
cd <the project's real directory>
claude --resume <session-id>     # exact session
claude --resume                  # interactive picker of local sessions
claude --continue                # most recent session in this dir
```
`--resume` reads the local transcript directly and is **account/organization-independent** — it works even when the desktop app hides the session.

## Why sessions "disappear" after an account/org switch
The **desktop app** scopes its session list by the active account **and organization**:
```
~/.config/Claude/claude-code-sessions/<accountUuid>/<organizationUuid>/local_*.json
```
Switching from a personal plan to a company org changes the active `<organizationUuid>`,
so the app lists only that org's sessions. The old ones still exist under the previous
org's folder (and their transcripts are untouched in `~/.claude/projects/`).

- If the old org still exists → switch the app's active workspace/organization back to it.
- If the old personal workspace was **removed** (subscription moved away) → the app can't
  show those sessions at all; use `claude --resume` in the terminal, which ignores the org filter.
- This is documented/known behavior (GitHub claude-code issue #48511, marked "not planned").

## Durable recovery bundle (recommended)
Keep, outside any git repo, a dated folder containing:
- all `*.jsonl` transcripts,
- `SESSIONS.md` (the index with full IDs + resume commands),
- any per-session summary docs.

That folder is your source of truth regardless of what the app/account does.
