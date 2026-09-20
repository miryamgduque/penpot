---
name: editing-agent-skills
description: Read or edit a user-created Penpot agent skill (the profile_skill DB rows, not files) and make the change actually reach a running agent. Triggers: "edit a profile_skill", "edit a user-created agent skill", "update the skill body in the DB", "the agent still uses the old skill version", "SQL edit skill playbook".
---

<!-- TRANSIENT dev tooling — committed only so collaborators on this branch can
     see it. DELETE THIS SKILL'S OWN DIRECTORY before the PR merges; it is not
     part of the Penpot product. Do NOT delete `.agents/skills/` or the
     `.claude/skills` symlink: those are upstream's and hold real project
     skills. The branch's transient ones are the directories carrying this
     banner. -->

# Editing a user-created agent skill (profile_skill)

User-created agent skills (the ones authored in the workspace AI panel) live in the
Penpot backend DB, table **`profile_skill`** — **not** as files. So you edit them with
SQL, and there's a propagation rule that trips everyone up (see the end).

Columns: `id, profile_id, name, label, category, reactive, trigger_on, description, body, is_enabled, created_at, updated_at`. The playbook text is `body`.

All `psql` runs inside the devenv container (see [[devenv-operations]] for container basics):
```bash
sg docker -c 'docker exec -u penpot penpot-devenv-ws0-main bash -lc "PGPASSWORD=penpot psql -h postgres -U penpot -d penpot -At -c \"<SQL>\""'
```

## 1. Find the skill

```sql
select id, label, category, reactive, char_length(body)
from profile_skill
where label ilike '%heuristic%';
```

## 2. Read the body

```sql
select body from profile_skill where id = '<id>';
```

## 3. Edit the body safely (base64 round-trip)

Dumping to a file, editing, and writing back via base64 avoids all SQL-quoting pain
with a multi-line Markdown body.

```bash
SP=./scratch            # any working dir
mkdir -p "$SP"

# dump
sg docker -c 'docker exec -u penpot penpot-devenv-ws0-main bash -lc \
  "PGPASSWORD=penpot psql -h postgres -U penpot -d penpot -At -c \
  \"select body from profile_skill where id = '\''<id>'\''\""' > "$SP/body.md"

# psql -At appends ONE trailing newline; strip it so you only change what you meant to
perl -i -pe 'chomp if eof' "$SP/body.md"

#  ... edit $SP/body.md with your editor / Edit tool ...

# write back (base64 keeps the SQL pure-ASCII and safe)
perl -i -pe 'chomp if eof' "$SP/body.md"
B64=$(base64 -w0 "$SP/body.md")
sg docker -c "docker exec -u penpot penpot-devenv-ws0-main bash -lc \
  \"PGPASSWORD=penpot psql -h postgres -U penpot -d penpot -At -c \
  \\\"update profile_skill set body = convert_from(decode('$B64','base64'),'UTF8') \
  where id = '<id>'; select char_length(body) from profile_skill where id = '<id>'\\\"\""
```

`UPDATE 1` + the new length = success.

## 4. IMPORTANT — make the edit reach the running agent

A raw DB write does **not** reach an already-open workspace. Two caches sit in front of it:

1. The client loads user skills into in-memory `:user-skills` **only on skills-panel open or a UI create/edit/delete** (via the `get-skills` RPC). `get_design_skills` serves the body from that in-memory copy.
2. Within one chat, the body is **pinned into the conversation** the first time `get_design_skills` runs.

So after any DB edit:

> **Reload the workspace (so the client re-fetches) → start a fresh chat (so the body is re-read).**

Symptom if you skip this: the agent keeps producing the *previous* version of the skill's output.

## Notes
- The body is served to the agent as **plain text** and shown in the panel inside a `<pre>` — Markdown isn't rendered, but its structure (headings, numbered steps) helps the model follow the playbook. Keep it structured; it's fine that `#`/`**` show literally in the detail card.
- If you'd rather not touch SQL: edit the body in the panel's skill editor (Body field). That persists **and** refetches in one step — then still start a fresh chat.
