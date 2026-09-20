# Enable or disable individual skills (US #8)

**Status:** done
**Created:** 2026-07-14
**Apps:** `backend`, `frontend`
**Source:** [Taiga US #8 — Enable or disable individual skills](https://tree.taiga.io/project/miryam-all-in-penpot/us/8)
**Dependencies:** Builds on US #7 (built-in catalog + read-only detail), which **deferred the
persisted enabled-state to this story**. Out of scope (adjacent): create/fork skills (#9/#10),
team-published toggles + local-vs-team overrides (#12/#13).

## Context

> As a Penpot user, I want to turn specific skills on or off, so that I only get the behavior I
> actually want from the Agent. **All built-in skills are on by default**, so this is about
> **removing** what I don't want rather than opting into what I do.

Just **toggling** — the story explicitly notes there is **no instant-apply behavior** anywhere
(every skill either only **reports** (Audit) or **previews before applying** (Build)), so there is
**no confirmation/warning flow** in scope. (An earlier draft included an auto-fix trust flow;
that has been removed from the story.)

**Interaction (verbatim intent):**
- Every skill card, in **both** the list view and the detail view, has a **toggle**.
- Toggling is **instant, no confirmation**, for any skill.
- Toggling **off** removes that skill from the **router immediately**.

### Decisions (from discovery)

1. **Persistence:** **per-user**, at two scopes — an **account default** and a **per-file
   override**, both private to the user (the file override is _not_ shared with other
   collaborators). Effective state resolves built-in default → account default → per-file
   override. The card toggle (Phase 03) writes the **per-file** override for the current file.
2. **Default:** **all built-in skills default on** — US #8 overrules US #7's auto-fix-off default.
   No first-use guard is needed (no instant-apply skills).

### Current state (grounded in code)

- `agent_skills.cljs` `catalog` has **static `:enabled` defaults** (auto-fix currently off from
  US #7). Phase 02 makes the default **on** for all, and drives effective state from persistence.
- **Account-level state precedent:** `backend/src/app/rpc/commands/ai_providers.clj` +
  `profile_ai_provider` table (upsert on `(profile_id, provider)`), fetched via `rp/cmd!` — the
  template for a `profile_skill_state` table + commands.
- **Per-file override is per-user, not shared.** Unlike the file-scoped `token-only-colors`
  enforcement (`set-enforced-rules` — shared file data), a user's per-file skill override is
  **private to them**, so it lives in the **same per-user backend table keyed by `file_id`**
  (NULL `file_id` = account default), _not_ in the shared file `:data`.
- **Router / system prompt** reads `agent_skills/enabled-skills` (+ `system-prompt-section`,
  `catalog-manifest` for `get_design_skills`) — resolving enabled-state there makes toggles take
  effect immediately.
- **UI primitive:** DS `switch*` (toggle).

### Open design question (minor)

- **No UI to set the account default in this story.** The card toggle writes the **per-file,
  per-user** override; the account-default layer exists in the data model (for resolution +
  future use) but has no dedicated UI here — baseline stays "all on". Confirm that's acceptable.

## Phases

1. [Phase 01 — Backend: skill state](./done-phase-01-backend-skill-state.md) — `profile_skill_state` table (per-user, `file_id` NULL = account / set = per-file) + RPC to get/set `enabled`, mirroring ai-providers. ✅ **done** (13-assertion test, lint clean)
2. [Phase 02 — Frontend state + resolution](./done-phase-02-frontend-state-resolution.md) — fetch/persist account + per-file state, effective-enabled resolution feeding the router. ✅ **done** (4 deftests; suite 433/1795 green, test build clean)
3. [Phase 03 — Toggle UI (cards + detail)](./done-phase-03-toggle-ui.md) — a toggle on every catalog card and the detail view; instant; disabled skills drop from the router. ✅ **done** (live RPC round-trip verified; compile/lint clean)

## Acceptance Criteria

- Every skill card (list + detail) has a toggle; toggling is **instant** with no confirmation step.
- Toggling a skill off removes it from the agent's available set / system-prompt index
  **immediately** (verifiable via `enabled-skills` / `get_design_skills`).
- On/off state persists **per user (account)** and can be **overridden per file** (both private to
  the user); effective state resolves default → account → per-file.
- All built-in skills are **on by default**.
- `make lint` / typecheck pass; backend + frontend build; unit tests cover state resolution.
