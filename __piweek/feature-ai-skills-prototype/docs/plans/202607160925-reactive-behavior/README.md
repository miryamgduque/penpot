# Reactive behavior (US #14)

**Status:** all 4 phases done + self-verified live (2026-07-16); awaiting the
user's final holistic review before moving to `completed/`
**Created:** 2026-07-16
**Taiga:** [US #14 — Skills declare their reactive behavior](https://tree.taiga.io/project/miryam-all-in-penpot/us/14) (Taiga status: Blocked; user directed to proceed anyway 2026-07-16)

> **Execution mode (user directive, 2026-07-16):** built **directly on
> `ai-skills-prototype`** (no worktree). Per-phase gates are compile
> (`shadow-cljs compile main`, 0 warnings on our namespaces) + `clj-kondo`, plus
> `check-fmt:clj` for the backend phase. **Tests waived** during development
> (demo is 2026-07-17). Commit per phase; collapse the per-phase human-approval
> pauses into ONE live review at the end in devenv.

**Apps:** `frontend`, `backend`
**Dependencies:** builds on the completed **autofix-watcher** plan
(`completed/202607160022-autofix-watcher`). That plan is why this one is cheap:
the two skills US #14 marks **Observer** are exactly the two the watcher already
observes.

## Context

Today a skill carries a **`:mode`** — `suggest` / `review` / `autofix` — a
three-state governance label shown as a colored badge. US #14 retires that
per-skill concept and replaces it with **reactive behavior**, a two-state axis
describing *when* a skill acts:

- **On-call** — request-driven; only acts when directly invoked in chat.
- **Observer** — maintains general awareness of relevant actions and notifies in
  the chat panel when something catches its eye.

It also collapses the **Auto-fix** category into **Build** (Rename layers moves
there), and adds the axis to the creation interview and the skill cards/detail.

**The load-bearing insight — reactive behavior is the user-facing name for the
watcher.** The completed autofix-watcher plan already runs an ambient loop over
a set of skills:

- deterministic watch = enabled skills' `:rule`s
  (`ask/watched-rules`, [agent_skills.cljs:320](../../../../../frontend/src/app/main/data/workspace/agent_skills.cljs))
- semantic tick = enabled skills with `:detect "model"`
  (`model-detect-skills`, [data/workspace/ai_panel.cljs:341](../../../../../frontend/src/app/main/data/workspace/ai_panel.cljs))

The only two skills carrying `:rule`/`:detect` today are **Tokens governance
audit** (`token-only-colors`) and **Rename layers** (`layer-naming`) — precisely
the two US #14 marks **Observer**. Verified in code (2026-07-16): the watcher
keys off `:rule`/`:detect`/`:model` and **never off `:mode`**, so renaming
`:mode` → `:reactive` cannot disturb it. This plan then *scopes* the watch to
Observer skills so the mapping is explicit: enabling an Observer starts its
watch, disabling it stops — the behavior the story describes.

Governance (Suggest / Apply-with-review / Auto-fix) does **not** disappear — it
lives on as the always-on per-change policy in `inner-knowledge`
([agent_skills.cljs:134](../../../../../frontend/src/app/main/data/workspace/agent_skills.cljs)).
US #14 only removes it as a *per-skill* attribute/badge. Rename layers keeps
applying its fixes directly — that comes from the safe-set + Fix-it-now routing,
not from a `:mode "autofix"` label.

## Phases

1. [Phase 01 — Catalog model](./todo-phase-01-catalog-model.md) — drop the
   Auto-fix category; `:mode` → `:reactive` (`on-call`/`observer`) with US #14's
   assignments; `reactive-label`; scope `watched-rules` + `model-detect-skills`
   to Observer skills; update `catalog-manifest` + the system-prompt index.
2. [Phase 02 — Skills UI badge](./todo-phase-02-skills-ui-badge.md) —
   `reactive-badge*` (On-call / Observer) on the skill **card** and the detail
   view, alongside category; retire the mode badge.
3. [Phase 03 — Creation flow + generation](./todo-phase-03-creation-and-generation.md)
   — replace the "Mode" step with the On-call/Observer question; `propose-reactive`;
   `skill_gen` categories → `["Audits" "Build"]`, prompt + parse use `:reactive`.
4. [Phase 04 — Backend](./todo-phase-04-backend-migration.md) — migration
   renaming `profile_skill.mode` → `reactive` (remap rows: `autofix`→`observer`,
   else `on-call`); RPC schema `[:enum "on-call" "observer"]`; entry mapping.

## Acceptance Criteria

- The built-in catalog shows two categories (Audits, Build); no "Auto-fix"
  category exists, and Rename layers appears under Build.
- Every built-in skill shows a reactive-behavior badge on its card and detail:
  Tokens governance audit and Rename layers read **Observer**; all others read
  **On-call**.
- Enabling/disabling an Observer skill adds/removes its rule from the live watch
  (the affected strip reacts) — no regression in the watcher's demo loop.
- Creating a skill asks the On-call/Observer question (conversational, with a
  proposed default) and the created card shows the chosen behavior.
- No user-facing "Mode / suggest / review / auto-fix" label remains on skills;
  governance still governs each change (unchanged `inner-knowledge`).
- Existing user-created skills survive the backend migration with a sensible
  reactive behavior and still load.

## Out of scope (per US #14 — separate stories)

- How an Observer notification appears in the chat panel.
- Throttling how often an Observer notifies.
- Editing reactive behavior after a skill already exists.
- The fork interview's copy of the question (Fork/Promote is US #10/#12, not yet
  wired) — the shared heuristic lands here; the fork entry point adopts it there.
