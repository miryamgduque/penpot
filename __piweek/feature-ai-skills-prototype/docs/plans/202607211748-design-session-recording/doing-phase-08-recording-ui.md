# Phase 08 — Recording UI

**Status:** doing — **code complete and green; every LIVE check is blocked**

Make it usable without a console: a record control with unmistakable active
state, and a list of past sessions on the file.

> ## ⛔ BLOCKED on browser tooling — do not mark this phase done
>
> The UI is written, compiles clean, and — the thing that mattered most — **the
> recording namespaces are now in the `:main` bundle** (verified: all five
> `session_*.js` files are present, where before `session_recorder.js` was
> absent entirely). That was the hard blocker behind phases 04 and 07.
>
> But the **Claude-in-Chrome extension was unreachable for this entire session**,
> across every phase, and it is still down. So nothing below the line has been
> seen in a browser: not the control, not the popover, not the dark theme, and
> none of the five inherited live checks.
>
> Logging in to the built-in Browser pane instead would mean typing a password,
> which I will not do. The devenv's passwordless demo login is disabled and
> enabling it needs a flag restart.
>
> **Second, independent blocker found in this phase:** devenv's frontend never
> receives `PENPOT_FLAGS` at all, so the flag-gated control cannot render there
> even with a working browser. See "Devenv cannot show the UI" below — it affects
> every frontend flag in Penpot, not just this one.
>
> **What this means honestly:** phases 01–04 and 07–08 are unit-tested,
> code-traced, and compile clean, but have **never run**. Only phases 05 and 06
> are proven against a running Penpot (both backend). The checklist below is the
> exact sequence to run once a browser is available — it should take ten minutes.

## Before Start

- [x] Verify plan is still valid — Phase 07 landed at `093c200388`
- [x] Confirm Phase 07 landed
- [x] Re-read the panel header controls (`chat-controls*` in `ai_panel.cljs`) and
      the History popover — copied its outside-click/Escape popover pattern
- [x] Check the Penpot DS for available icons — **confirmed the gap**: there is
      no `record` and no `stop` glyph. `play` starts a recording, a styled
      `stroke-circle` marks the active state, `history` opens the list,
      `download` exports. Note `ai_panel` uses the **DS** icon set
      (`app.main.ui.ds.foundations.assets.icon`), not `app.main.ui.icons` — both
      exist and only the DS one works with `icon-button*`
- [x] Confirm whether this belongs in the agent panel or the workspace header —
      **both**, after Santi's 2026-07-26 direction: the *control* sits in the agent
      panel beside the conversation controls, and the *REC indicator* is in the
      workspace header on the presence widget so everyone being recorded sees it

## Live verification debt inherited from phases 02, 03 and 04

**This phase owns every outstanding live check for the feature.** Phases 02–04
are code-traced and unit-tested but have never run in a browser: the
Claude-in-Chrome extension was unreachable throughout, and — the harder blocker —
`session-recorder` is not in the `:main` build at all until something requires
it. **This phase creates that first caller**, so it is where all of it finally
becomes observable. Do not close Phase 08 without these:

- [ ] **Console-drive a real recording** (from Phase 04): start, edit shapes by
      hand, run an agent turn, stop, read the timeline.
- [ ] **Two-session attribution** (from Phase 02): open the file in two sessions,
      edit from each, assert each side records the OTHER's profile-id on its
      incoming events. This is the check that proves "record every person working
      on the file" actually works.
- [ ] **Agent-vs-human attribution** (from Phase 03): one real agent turn plus one
      manual edit in the same recording, separated correctly with the right model
      named. Specifically watch a `create_shape` into a **laid-out board**: its
      reflow commits land ~100ms AFTER the tool returns and must read `:agent`,
      not `:user`. That is exactly what `session-actor`'s 400ms grace window
      exists for and it has never been checked against real reflow timing.
- [ ] **Reload mid-recording** (from Phase 07): start a recording, refresh the
      page, confirm it resumes with its timeline and that the row eventually
      closes with a real stop reason rather than staying open forever.
- [ ] **Noise-filter reality check** (from Phase 01): confirm `:fix-obj`,
      `:reg-objects` and `:assign`-only `:mod-obj` commits either do not appear in
      practice or get added to the noise filter. They currently classify as
      `:other` and are deliberately left visible rather than silently dropped.

## Checklist

- [x] Require `session-recorder` AND `session-persist` from the UI — **done, and
      verified in the bundle**: all five `session_*.js` namespaces are now in
      `resources/public/js/cljs-runtime/`
- [x] Emit `session-persist/start-persisting` alongside `start-recording`
- [x] Call `session-persist/resume-recording` on mount — with a documented
      limitation (it mounts with the panel, so edits between a reload and
      reopening the panel are missed)
- [x] **Surface `:local-only?`** — a "not saving" badge with a tooltip naming the
      failure count
- [x] Record control: start/stop with unambiguous active state — a pulsing red
      dot plus elapsed time and a live event count while recording
- [x] Live counter while recording (events captured, elapsed) — ticks every
      second, because a frozen readout is indistinguishable from a dead recording
- [x] Session list: past sessions on this file, newest first, with duration —
      **participant count NOT shown**, see Notes
- [x] **Visible to everyone being recorded** (Santi 2026-07-26) — `:recording-update`
      broadcast + REC badge on the presence widget
- [x] **Behind a feature flag, off by default** (Santi 2026-07-26) — enforced in
      the UI *and* at every RPC
- [ ] ~~Session detail: the timeline, grouped by actor~~ **descoped**, see Notes
- [x] Surface the stop reason when a session ended on a cap
- [x] Surface local-only degradation if flushes failed
- [x] SCSS — ran `build-app-assets.js`; confirmed the classes reached `main.css`
      (the devenv SCSS watch does not pick up new files)
- [x] Full suite green — **997 tests / 3027 assertions / 0 failures** — and
      `compile main` clean, which matters here because the test build does not
      compile `ai_panel.cljs`
- [x] Lint + format — 0 new warnings (three in `refs.cljs`/`ai_panel.cljs` are
      pre-existing, verified by linting them with my changes stashed)
- [ ] **Live review in the browser, including dark theme — BLOCKED**
- [ ] Human approval — pending the live review
- [x] Committed with a gitmoji commit

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` (or a new `session_recorder.cljs` UI ns) — control + list + detail
- corresponding `.scss`
- `frontend/translations/en.po` — new i18n keys (append at EOF; this file conflicts constantly across sessions)

## Notes

### What was descoped, and why

**Session detail (a readable timeline grouped by actor).** The list shows each
session's time, duration, event count, stop reason and raw-loss, and export
copies the full structured bundle — which is the path that actually matters,
since the audience is a bot. A rendered in-panel timeline is a second consumer of
the same data and can wait until someone wants to read one by eye. Not started
rather than half-built.

**Participant count in the list.** The list endpoint returns metadata only;
deriving "2 people, agent" needs the events, and they are transit-encoded in
jsonb, so counting distinct actors in SQL would mean parsing transit keys inside
Postgres. It wants a denormalized column on `design_session`. Left out rather
than faked — a wrong participant count on a record of who did what would be
worse than none.

### RESOLVED by Santi (2026-07-26): visible, admin export, behind a flag

> "make it visibile, only admins can export it, available as a flag"

All three are now in place, and together they answer the privacy question the
phase left open — a recording is announced to everyone on the file, only admins
can take it away, and an instance has to opt in before any of it exists.

**1. Visible to everyone being recorded.** A `:recording-update` websocket
message (modelled exactly on `:pointer-update`, `websocket.clj`) broadcasts
start/stop to the file topic, and the receiving client stores it **on the
presence entry** rather than in a map of its own. That choice pays for itself:
`handle-presence` already drops the whole entry on disconnect / leave-file, so a
collaborator who closes their tab mid-recording **cannot leave a stale REC badge
behind** — the cleanup is free and cannot be forgotten.

The badge lives in `active-sessions*` beside the collaborator avatars, which is
where "who else is here" already is. It is in the workspace header, so it does
not depend on anyone having the agent panel open. The server stamps the real
profile/session on the message rather than trusting the client's.

**2. Only admins can export.** Already the case from Phase 06 and unchanged;
now pinned by a test at both ends (admin succeeds, outsider refused).

**3. Behind a flag, off by default.** `:design-session-recording` added to
`flags/varia` and deliberately **not** to `flags/default`. Enforced in two
places, because a hidden button is not an access boundary:

- the UI does not render the control (`cf/flags`, the same idiom `:mcp` uses);
- **every RPC command refuses** with `:restriction` / `:feature-disabled`.

A test disables the flag and asserts writes, reads and exports are all rejected,
plus that the shipped default set does not contain it.

Enable with `PENPOT_FLAGS=enable-design-session-recording`. It is on in devenv
(`docker/devenv/defaults.env`) and in the backend test harness.

### ⚠ Devenv cannot show the UI — the frontend never receives PENPOT_FLAGS

Found while wiring this up, and **it is not specific to this feature**.

Frontend `cf/flags` is parsed from `globalThis.penpotFlags`, defaulting to `""`
(`frontend/src/app/config.cljs:76-80`). Nothing in this branch ever sets that
global: production injects it by `sed`-ing a commented placeholder in
`js/config.js` from the nginx entrypoint
(`docker/images/files/nginx-entrypoint.sh:27-32,47`), a file that **does not
exist in devenv**, and `index.mustache` carries no placeholder either.

So in devenv the frontend only ever has `flags/default`, and **any** frontend
flag gate is dead there — including the pre-existing `(contains? cf/flags :mcp)`
at `main_menu.cljs:113`. The backend *does* get the flag correctly (verified:
`{:flag true, :flags-count 47}` in the running system).

Consequence: the record control will not appear in devenv until
`globalThis.penpotFlags` is set before the app boots. To try it, set that global
(what production's `sed` does in spirit) or temporarily drop the `cf/flags` guard
in `ai_panel.cljs`. Deliberately not "fixed" here — adding a flag-injection step
to the devenv build changes behaviour for every flag in Penpot, which is well
outside this feature's remit and should be its own change.

### Still open: who may START a recording

Today **any editor** may record; only admins may export. That asymmetry follows
from Santi's direction and is deliberate, but it means one collaborator can
record another's work. The badge now makes that visible, which is the important
half. Restricting *starting* to admins would be a one-line change to
`check-can-read!`'s sibling if that turns out to be wanted.

### DS gaps hit

- No `record` and no `stop` glyph, as expected from earlier phases. `play` /
  styled `stroke-circle` stand in.
- `ai_panel` imports the **DS** icon set, while `app.main.ui.icons` also exists
  with overlapping names. Only the DS ids work with `icon-button*`; the other
  namespace compiles and then fails to render.
- `--color-foreground-error` does not exist; the real token is
  `--color-accent-error`.

**Recording state must be impossible to misread.** This captures identifiable
activity by people who did not press the button — a collaborator may be recorded
without having chosen it. The indicator should be visible to anyone on the file,
not only to the person who started it. If that turns out to need presence work
beyond this phase's scope, say so and treat it as a blocker for shipping beyond
the branch, not a nice-to-have.

That is also the moment to resolve the privacy question flagged in the README:
whether participants are told. A quiet recorder is a different product from an
announced one, and the difference is not technical.

DS gaps to expect (from past phases on this branch): `icon-button*` has no
accessible name until hover, and there is no stop glyph. Do not invent a new
icon set for this; pick the closest existing glyph and note the gap.
