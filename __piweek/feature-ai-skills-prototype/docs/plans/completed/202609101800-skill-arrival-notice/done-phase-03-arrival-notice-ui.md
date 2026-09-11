# Phase 03 — Arrival notice card UI

**Status:** todo

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions) — confirmed 2026-09-11
- [x] Confirm Phase 02 is `done` — skill entries expose `:arrived` and `mark-team-skill-seen!` exists
- [x] Re-read `ai_panel.cljs:1227-1244` (handoff-notice) and `697-868` (Observer stack) — both match grounding
- [x] Re-read `ai_panel.cljs:2007-2073` (`skills-tab*`) / `1479-1538` (`skill-detail*`) — confirmed `selected` = skill's `:name` slug, `on-select` opens it; `ai-panel*` (2444+) owns `view*`/`skill*` local state that `skills-tab*` and `chat-tab*` don't have direct access to

## Checklist

- [x] New arrival-notice markup inline in `chat-tab*` (didn't need a separate `defc` — small enough, one `for` block), styled per the session's design reference but using the app's **real** design tokens (`--color-accent-primary`, `--color-background-secondary/quaternary`, the `deprecated.$s-*`/`$br-*` scale) instead of the earlier Penpot-mockup's own hex values — confirmed better fit during Before Start since these CSS custom properties already exist and are exactly what `handoff-notice`/`catalog-team` use
- [x] Rendered in `chat-tab*`, right after `observer-notifications*`, one card per catalog entry with `:arrived true` (computed via `mf/with-memo` over `refs/skills-catalog`) — coexists cleanly with the Observer stack (verified visually, both render together without conflict)
- [x] **Dismiss** → `dwts/mark-team-skill-seen!` (optimistic, immediate)
- [x] **View skill** → `dwts/mark-team-skill-seen!` **and** a new `on-view-skill` callback threaded from `ai-panel*` down through `chat-tab*` (parallel to the existing `on-create-skill` prop) that sets `skill*`/`view*` to open that skill's detail — reuses `skill-detail*` completely unmodified
- [x] No Disable action anywhere on the card — confirmed both in code and visually
- [x] Lint pass: `clj-kondo --lint frontend/src/app/main/ui/workspace/ai_panel.cljs` → 0 errors/warnings
- [x] Format pass: `cljfmt check` → all formatted correctly
- [x] shadow-cljs live watch rebuilt `:main` with 0 warnings after every edit (cljs + scss)
- [x] **Live preview review in devenv**, logged in as the actual second team member (`demo2@example.com`, "Demo Two", not the promoter) on the shared "Team" — see Notes for the full verification log and one real bug caught + fixed along the way
- [x] Human approval received
- [x] Committed with a gitmoji commit — `4c6d8090e2`

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — yes, Phase 04 unblocked

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — new `arrival-notice*` component + render call
- corresponding `.scss` (wherever `handoff-notice`'s styles live) — new card styles per the Penpot design reference (teal accent `#0DB39E`, not the wireframe's violet)

## Notes

Visual reference lives in a Penpot MCP file from this session (not in the repo) — two components, `NoticeCard` and `SkillListItem`. Translated the *spec* into this project's real SCSS tokens rather than the mockup's own hex values (see checklist).

**Bug caught + fixed during live verification:** the backend `get-team-skills` change from Phase 01 was only ever exercised through direct nREPL calls, never through the actual HTTP RPC dispatch. When first opened in the browser it 500'd — `"No value specified for parameter 2"` — because the running backend process's RPC method-dispatch table had captured the function at JVM boot (09:44, before Phase 01's edits), and reloading a namespace via nREPL updates the *var* but not whatever the dispatch table already resolved from it. A plain `./scripts/start-dev` restart picked up the real on-disk code and fixed it. **Environment lesson for future phases:** backend `.clj` edits need a full backend restart before they're live over HTTP — an nREPL `:reload` is only enough for direct-call testing, not for the actual served RPC.

**Full verification log** (devenv, `demo2@example.com` / "Demo Two", a real second member of team "Team" — not the promoter):
1. Opened an existing file inside "Team" (not Demo Two's personal Drafts — the first attempt used her personal default team, which correctly returned an empty list, a useful reminder that `:arrived` is scoped to whichever team the open file belongs to).
2. Needed a connected AI provider for `chat-tab*` to render at all (`skills-tab*` doesn't need one, but the arrival notice lives in `chat-tab*`) — inserted a local-only fake `profile_ai_provider` row directly via SQL for this session's testing (never a real key, never left in place — deleted afterward).
3. Saw all 3 promoted team skills ("Passive voice flagger", "Typo checker", "Heuristic evaluation (Nielsen's 10)") rendered as separate arrival-notice cards, stacked cleanly alongside the pre-existing Observer widget ("Tokens governance audit") with no visual or logic conflict.
4. Card content matched spec exactly: "New team skill: \<name\>", "Promoted by Demo User. \<description\>", Dismiss + View skill, no Disable.
5. Clicked **Dismiss** on "Passive voice flagger" → card disappeared immediately (optimistic) and the `mark-team-skill-seen` RPC returned 200. Reloaded the whole page → confirmed it did **not** reappear (persisted), the other two still did.
6. Clicked **View skill** on "Typo checker" → landed directly on that skill's existing detail view (`skill-detail*`, unmodified — "Team" badge, enabled toggle, all present) and marked it seen too; navigating back to Chat confirmed only "Heuristic evaluation" remained.
7. Cleaned up all test rows (`team_skill_seen`, the fake `profile_ai_provider`) afterward.

One tool quirk unrelated to the app: the Browser pane's coordinate-based clicks silently missed the Dismiss button after a `resize_window` call (no error, no network request); a plain DOM `.click()` via `javascript_tool` on the same element worked immediately and is what actually exercised the code path — noted here in case a future phase hits the same thing.
