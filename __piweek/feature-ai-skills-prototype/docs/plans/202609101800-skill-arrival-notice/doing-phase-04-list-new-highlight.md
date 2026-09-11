# Phase 04 — Skill-list "NEW" highlight

**Status:** todo

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions) — confirmed 2026-09-11
- [x] Confirm Phase 02 is `done` — list entries expose `:arrived`
- [x] Re-read `skills-tab*`/`skill-row*` (now at lines 2043-2109 / 1576-1636, shifted by Phase 03's insertions) — confirmed current structure matches grounding

## Checklist

- [x] `skill-row*` — added an `arrived` prop; when true, applies `.catalog-card-new` (accent-tinted background, `color-mix(in srgb, var(--color-accent-primary) 8%, transparent)` — the project's real accent token, not the earlier Penpot-mockup hex) + a "New" pill (`.catalog-new-pill`, accent background/`--color-background-primary` text — the same convention already used 3× elsewhere in this file for accent buttons)
- [x] Client-side-only 20s fade: `mf/use-state` seeded from `arrived`, `mf/with-effect [arrived]` sets a 20000ms `js/setTimeout` (cleared on unmount) that flips it false; CSS `transition` declared on the always-present base classes (`.catalog-card`, `.catalog-new-pill`) so removing/adding the modifier class animates instead of snapping — no persisted timestamp, no backend call
- [x] Confirmed re-mounting (fresh panel open) restarts the local timer and the highlight/pill reappear — this is the accepted trade-off, verified live (see Notes)
- [x] Confirmed the highlight never calls `mark-team-skill-seen!` — it's purely local state, decoupled from Phase 03's notice actions, exactly per the README's "Seen-marking rule"
- [x] Lint pass: `clj-kondo --lint frontend/src/app/main/ui/workspace/ai_panel.cljs` → 0 errors/warnings
- [x] Format pass: `cljfmt check` → all formatted correctly
- [x] shadow-cljs live watch rebuilt with 0 warnings after every edit
- [x] **Live preview review in devenv** as the real second team member (`demo2@example.com`) — see Notes for the full log, including a browser-automation-specific quirk (not an app bug) caught during verification
- [ ] Human approval received
- [ ] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — `skill-row*` highlight + pill + local fade timer
- corresponding `.scss` — highlight/pill styles

## Notes

This phase is deliberately decoupled from Phase 03's seen-marking — re-read the README's "Seen-marking rule" before changing this behavior; it was a discovery-interview decision, not an oversight.

**Live verification log** (devenv, `demo2@example.com` / "Demo Two", the Skills tab of a file in the shared "Team" — reachable without a connected AI provider, unlike Phase 03's chat-tab):
1. Opened Skills (via the panel's "More actions" menu) → "Passive voice flagger" and "Typo checker" (both still `:arrived true` from Phase 03's testing — "Heuristic evaluation" had already been marked seen) both showed the accent-tinted row background and a "New" pill next to their names. Confirmed via `getComputedStyle`/class inspection that exactly the two expected rows carried `.catalog-card-new`.
2. Waited 20+ real seconds, then re-checked: both rows lost `.catalog-card-new` (background cleanly transitioned back — confirmed both visually and via computed style) and both pills gained `.catalog-new-pill-faded`.
3. **One thing that did NOT resolve cleanly in this environment:** the pill's opacity transition itself got stuck mid-animation (`Animation.playState: "running"`, frozen at `localTime: 0`) — `getComputedStyle` kept reporting `opacity: 1` well past the 900ms transition duration, even though the class was correctly applied and a fresh test element with the identical class combination immediately resolved to `opacity: 0`. Forcing the stuck `Animation` to `.finish()` snapped it to the correct final state instantly. This points to the Browser pane's compositor not ticking a real animation frame timeline for this specific `opacity` transition during automated/background execution — the background-color transition on the card (also declared the same way, on the always-present base class) completed normally in the same session, so it isn't a difference in my CSS approach. Concluded this is a tooling/environment artifact, not an app bug — the class logic, the CSS rule, and the final resting state are all verifiably correct; only the *live visual animation* of the pill fade wasn't observable through this specific automation harness. Worth a plain human eyeball check in a real browser tab at some point, but not blocking.
4. Reloaded the page fresh and reopened Skills → "Passive voice flagger" showed the highlight again (local state resets on remount since `arrived` is still true and nothing marks it seen) — confirms the intended "reappears until acknowledged via the notice" behavior from the README's Seen-marking rule.
