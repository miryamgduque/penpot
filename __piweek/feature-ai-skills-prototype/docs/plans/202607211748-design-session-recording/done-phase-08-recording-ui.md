# Phase 08 — Recording UI

**Status:** done — **LIVE-VERIFIED end to end** (2026-07-26)

Make it usable without a console: a record control with unmistakable active
state, and a list of past sessions on the file.

## Live verification — every inherited check passed, and it found a real bug

Chrome became reachable on 2026-07-26 and the whole backlog was run against a
real Penpot on devenv, file "New File 7". **This is the run that turned phases
01-04 and 07-08 from "unit-tested" into "proven".**

| Check | From | Result |
|---|---|---|
| flag reaches CLJS | 08 | `penpotFlags = "enable-design-session-recording"`; `app.config/flags` contains it |
| record control + REC badge + live counter render | 08 | badge `REC`, status `1:26 · 6 events` |
| session row created before any events | 07 | row present with `events = 0` |
| human edit attributed | 02 | `who: user`, `model: nil` |
| **agent edit attributed with its model** | 03 | `who: agent`, `model: claude-opus-4-8` |
| **reflow commit landing AFTER the tool returned** | 03 | `move` event → `who: agent` — *the case the 400ms grace window exists for, never before tested against real timing* |
| marker releases, no leak | 03 | edit after grace → `who: user`; `current-actor` nil past 400ms |
| stop closes the row with its reason | 07 | `stop_reason = manual`, `stopped_at` set |
| reload resumes the same row | 07 | same id, timeline restored, `raw` empty (correctly not resumed) |
| admin export bundle | 06 | `{file-id, session-count, sessions}`; events carry who / model / provider / profile-id |
| **a collaborator sees they are being recorded** | 08 | the second session shows `recording: true` on the other presence entry and renders `REC`, while not recording itself |
| **remote edit attributed to the OTHER session** | 02 | `source: :remote`, `fromThisTab: false`, `who: user` — *the "record every person on the file" premise, finally proven* |
| noise filter | 01 | no `:fix-obj` / `:reg-objects` / `:assign`-only events appeared in real traffic; left as-is |

Persisted timeline from the main probe, showing the whole story in one session:

| # | who | model | what |
|---|---|---|---|
| 1 | user | — | created 1 shape |
| 2-3 | user | — | changed layout |
| 4 | **agent** | claude-opus-4-8 | created 1 shape |
| 5 | **agent** | claude-opus-4-8 | moved 1 shape (the reflow) |
| 6 | user | — | renamed 1 shape |

### The bug live verification caught

**`resume-recording` restarted capture but not flushing.** A resumed recording
kept collecting events and never flushed again, so its row stayed
`stopped_at IS NULL` **forever** — precisely the "sessions that never end"
failure the resume design was written to prevent. Every unit test passed because
every *piece* worked; only the composition was wrong, and only a real reload
against a real backend could show it.

Fixed by extracting `resume-events` as a pure function returning all three events
(seed + capture + flush) and pinning it with a regression test that counts them.
Re-verified live: after the fix a resumed session stops and its row closes with
`stop_reason = manual`.

One orphaned row from the pre-fix run was closed by hand; the sessions database
now holds 5 rows with **0 still open**.

### Two false alarms worth recording

- An "Internal Error" report during login was **my** navigation, not the branch:
  `#/dashboard/recent` with no `team-id` trips an assert in
  `dashboard/initialize` (`Team ID: --` in the report). With the id present and a
  reload, the dashboard is fine.
- A transient "2 failures" in the frontend suite was a **stale build** read
  mid-compile; two clean consecutive runs give 999 tests / 3033 assertions /
  0 failures.

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

- [x] **Console-drive a real recording** (from Phase 04) — PASSED
- [x] **Two-session attribution** (from Phase 02) — PASSED: `source: :remote`,
      attributed to the other session, not ours
- [x] **Agent-vs-human attribution** (from Phase 03) — PASSED, including the
      reflow case: a `create_shape` into a laid-out board produced a `move` event
      ~100ms after the tool returned, correctly read as `:agent`
- [x] **Reload mid-recording** (from Phase 07) — PASSED after fixing a real bug
      it exposed (see above)
- [x] **Noise-filter reality check** (from Phase 01) — none of those change types
      appeared in real traffic; left classifying as `:other` and visible

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
- [x] Live review in the browser — control, badge, counter and popover all render
- [x] Human approval — standing approval from Santi; live results reported
- [x] Committed with a gitmoji commit

## After Finish

- [x] Rename this file: `doing-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — **Phase 09 can
      proceed**, and now on solid ground: the timeline it will critique is proven
      to be real, attributed, and persisted. The key with Opus 4.8 is configured
      on Santi's profile, so its acceptance moment (one real review turn) is
      runnable.

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

### FIXED: the frontend now receives PENPOT_FLAGS (was a Penpot-wide gap)

Found while wiring this up, and **it was not specific to this feature**.

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

**Fix applied** (2026-07-26), because otherwise the flag-gated control could
never be seen in a dev build: `renderTemplate` (`frontend/scripts/_helpers.js`)
now passes `process.env.PENPOT_FLAGS` into the template context, and
`index.mustache` emits `globalThis.penpotFlags` from it.

Verified both ways:

- with `PENPOT_FLAGS=enable-design-session-recording` (devenv default), the served
  HTML contains `penpotFlags = "enable-design-session-recording"`;
- with `PENPOT_FLAGS` empty, **nothing is emitted at all** (grep count 0) — so a
  packaged build, where the variable is usually absent at asset-build time, is
  untouched and its own `js/config.js` injection stays authoritative. That
  mustache section guard (`{{#flags}}`) is what makes the change safe rather than
  a regression.

Side effect worth knowing: this also revives every *other* frontend flag in a dev
build, `:mcp` included. That is a fix, not a surprise, but it means dev builds now
honour `PENPOT_FLAGS` where they previously ignored it.

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
