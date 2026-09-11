# Notify team members when a skill arrives (US #52)

**Status:** doing
**Created:** 2026-09-10
**Taiga:** [US #52 — Notify team members when a skill arrives](https://tree.taiga.io/project/miryam-all-in-penpot/us/52)

> **Execution mode (user directive, 2026-09-10):** same as US #12 — built **directly
> on `ai-skills-prototype`** (no worktree). Per-phase gates are compile
> (`shadow-cljs compile main`, 0 warnings on our namespaces) + `clj-kondo`, plus
> `check-fmt:clj` for backend. **Tests waived** during development. Commit per
> phase; one live review at the end in devenv (two accounts on the same team).

**Apps:** `frontend`, `backend`
**Dependencies:** builds directly on US #12 (Promote a skill to the team,
[completed plan](../completed/202607161238-promote-skill-to-team/README.md)).
References but does **not** modify story #8 (existing enable/disable toggle) or
US #14's reactive-behavior "Observer widget" (pattern precedent only).

## Context

US #12 made promoted team skills **on by default** for every member — deliberate,
since that's what makes shared expertise actually spread. But it means a
member's Agent can change behavior with no visible cause. This story adds
awareness, not an acceptance gate: an inline notice in the Agent panel naming
the skill/promoter/purpose, plus a temporary "new" highlight in the skills list.
Dismissing the notice (or opening the skill's detail) never disables anything —
disabling still goes through story #8's existing toggle.

**Design reference:** a `NoticeCard` component and a `SkillListItem` (new-highlight
state) component were prototyped in Penpot itself this session (MCP-connected file,
not part of this repo) — icon + "New team skill: X" + "Promoted by Y. \<one-line
description\>" + Dismiss/View skill footer; list row gets an accent-tinted
background/border + a small "NEW" pill. Palette diverged from the raw wireframe
reference (`~/Downloads/us52-skill-arrival.html`, layout/content only) — a teal
accent (`#0DB39E`) was chosen over the wireframe's violet to match Penpot's own
product accent family. These are visual/content specs to translate into the
project's actual CSS/SCSS + Clojure(Script) conventions, not literal Penpot-shape
exports.

**Grounding (verified in code, 2026-09-10):**
- `promote-skill` ([team_skills.clj:97-110](../../../../../../backend/src/app/rpc/commands/team_skills.clj)) inserts the `team_skill` row and deletes
  the source `profile_skill`; `get-team-skills` ([team_skills.clj:43-59](../../../../../../backend/src/app/rpc/commands/team_skills.clj)) fetches by
  `team_id` only — no promoter-only filtering, so any member's fetch already
  returns every team skill including brand-new ones.
- **No existing table fits "seen" state.** `profile_skill_state`
  ([skill_state.clj:26-66](../../../../../../backend/src/app/rpc/commands/skill_state.clj)) is keyed `(profile_id, skill-name, file_id)` and means
  "is this skill on/off for me *in this file*" — file-scoped and name-keyed.
  Arrival is team-scoped and `team_skill`-id-keyed, a different axis entirely;
  bolting onto `profile_skill_state` would conflate "disabled" with "not yet
  acknowledged." **Decision: a new `team_skill_seen` table**, `(profile_id,
  team_skill_id, seen_at)`, absence of a row = "not yet seen."
- **The Observer widget (US #14)** is a real, reusable UI pattern, not just a
  name: `observer-card*` / `observer-notifications*`
  ([ai_panel.cljs:697-868](../../../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs)) render a **stack** of dismissible cards with
  expand/collapse and a `:signature`-keyed dismissed-map. A **closer** structural
  match for a single, non-expanding arrival card is the simpler **handoff-notice**
  pattern ([ai_panel.cljs:1227-1244](../../../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs)): header row (text + ✕) + one action button
  below, gated by a dismissed-ref ([ai_panel.cljs:882-894](../../../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs)). Plan: pattern the
  arrival card's *shape* on handoff-notice, but allow more than one to stack
  (rare: two promotions between panel opens) the way Observer already stacks
  multiple cards.
- **Story #8's toggle is untouched-and-reusable:** list-row toggle
  ([ai_panel.cljs:2018-2073](../../../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs), `toggle` fn 2026-2028) and detail-view switch
  ([ai_panel.cljs:1479-1522](../../../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs), lines 1512-1514). "View skill" on the new notice just
  needs to call the same `on-select` used to open a row's detail (2039-2040) —
  no new toggle, no new detail view.
- **Fetch trigger to piggyback on:** `fetch-team-skills`
  ([team_skills.cljs:27-35](../../../../../../frontend/src/app/main/data/workspace/team_skills.cljs)) is called from panel-open at
  [ai_panel.cljs:2569](../../../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs). No new fetch call needed — extend `get-team-skills`'s
  response with a computed `:arrived?` per row and read it off the same
  round-trip already firing on panel open (matches the "fetch-on-open only, no
  live push" decision below).
- **Profile-scoping precedent:** `get-team-skills` destructures `::rpc/profile-id`
  and calls `check-read-permissions!` ([team_skills.clj:56](../../../../../../backend/src/app/rpc/commands/team_skills.clj)); `skill_state.clj:44-66`'s
  upsert mutation is the shape to copy for the new `mark-team-skill-seen`
  mutation.

## Decisions (from discovery interview, 2026-09-10)

1. **Delivery: fetch-on-open only.** No live/websocket push. The notice and
   highlight appear the next time the member opens/refreshes the Agent panel —
   consistent with how team skills already merge into the catalog (US #12) and
   with the Observer widget being a static panel element, not push-driven.
2. **Seen/dismissed state: persisted per member**, via the new
   `team_skill_seen` table — survives reload and follows the member across
   devices.
3. **20s "new" highlight: client-side ephemeral.** A frontend-only timer from
   list-mount; no persisted "first shown at" timestamp. Reopening the panel
   within 20s restarts the visual timer — accepted trade-off. This is a
   **separate lifecycle from "seen"**: per the story's own framing ("the
   new-skill highlight... covers them whenever they do come back"), the
   highlight is expected to *keep reappearing* on every open until the member
   actually acknowledges the arrival via the notice card (Dismiss or View
   skill) — it is not itself what marks the skill as seen.
4. **Execution mode:** same lean gates as US #12 (see banner above). Tests
   waived.

**Seen-marking rule (derived above, stated explicitly so it isn't re-litigated
mid-implementation):** `mark-team-skill-seen` fires only from an explicit
notice-card action — **Dismiss** or **View skill** — never automatically from
merely fetching or rendering the list. This is why the plan needs a dedicated
mutation rather than piggybacking "seen" onto the fetch itself.

## Phases

1. [Phase 01 — Backend: `team_skill_seen` + `arrived?` + mark-seen RPC](./done-phase-01-backend-arrival-state.md) — new table/migration, extend `get-team-skills` with a computed `:arrived?`, add `::mark-team-skill-seen` mutation.
2. [Phase 02 — Frontend data: surface `arrived?` through the catalog](./done-phase-02-frontend-data-arrival.md) — thread the field through `team_skills.cljs` → `agent_skills.cljs`'s `full-catalog`/`team-skill->entry`, add the `mark-team-skill-seen!` action.
3. [Phase 03 — Arrival notice card UI](./done-phase-03-arrival-notice-ui.md) — new dismissible card(s) patterned on `handoff-notice`, wired to Dismiss/View skill → `mark-team-skill-seen!` + reuse of the existing detail view.
4. [Phase 04 — Skill-list "NEW" highlight](./doing-phase-04-list-new-highlight.md) — accent tint + border + "NEW" pill on arrived rows, 20s client-side ephemeral fade.
5. [Phase 05 — Live verification + wrap-up](./todo-phase-05-live-verification.md) — two-account devenv check, completion summary, move to `completed/`.

## Acceptance Criteria

- When a skill is promoted (US #12), every **other** team member — not the
  promoter — sees an arrival notice card the next time they open/refresh the
  Agent panel.
- The notice names the skill, who promoted it, and a one-line description; it
  offers **only** Dismiss and View skill — no Disable action on the card
  itself.
- Dismissing, or opening the skill's detail view from the notice, persists a
  per-member "seen" flag (survives reload/other devices) and the notice does
  not reappear for that skill.
- The corresponding skill's row in the list shows an accent highlight + "NEW"
  pill; the highlight fades ~20s after the list is visited (client-side only)
  but **reappears on a fresh panel open** if the arrival was never acknowledged
  via the notice.
- The promoter never sees a notice or highlight for their own promotion.
- Story #8's toggle (list row + detail view) is unmodified and still the only
  way to disable a skill.
- No live/push notification path is introduced; everything resolves through
  the existing fetch-on-panel-open trigger.

## Out of scope (per US #52 — separate stories)

- Notifying when an **already-promoted** skill is *updated* (vs. newly
  arrived) — separate story, following the shared-libraries pattern.
- Any Penpot-level (non-Agent-panel) notification.
- Changes to story #8's toggle mechanism.
- Un-promoting / removing a team skill (unrelated, not touched here).
