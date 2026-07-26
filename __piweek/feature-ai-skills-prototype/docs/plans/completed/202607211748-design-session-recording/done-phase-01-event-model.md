# Phase 01 — Event model

**Status:** done

Pure data + pure functions. No UI, no backend, no subscription. Everything here
is unit-testable in isolation, which is the point: the coalescing and filtering
rules are where this feature is most likely to be quietly wrong.

## Before Start

- [x] Verify plan is still valid (no conflicts with other plans/sessions) — tree
      clean apart from unrelated `ai_providers.cljs` work; HEAD `bc5f2bbd9e`
- [x] Confirm the commit map shape at `frontend/src/app/main/data/changes.cljs:176-193`
      still carries `:origin`, `:undo-group`, `:source`, `:tags`, `:created-at` —
      **confirmed verbatim**
- [x] Re-check the noise filter precedent at `frontend/src/app/main/data/workspace.cljs:459`
      (`:position-data` tag) — **confirmed**; the tag is set at `workspace.cljs:244`
      and `:505`. `ignore-wasm?` turns out to be used by *only*
      `fix_deleted_fonts.cljs:106,124`, so it is a machine-repair marker rather
      than a general WASM-sync marker as the plan assumed
- [x] Decide the home ns — **frontend only** (`data/workspace/session_events.cljs`).
      Nothing backend-side validates events yet; revisit at Phase 06
- [x] **DRIFT FOUND — `:origin` is unusable as the primary label.** See the
      CORRECTION block in the plan README. Only 6 of 133 `commit-changes` call
      sites pass `origin`; the rest yield `:potok.v2.core/undefined`. Checklist
      below revised to classify on change types instead
- [x] Found reusable prior art: `touched-shape-ids`
      (`data/workspace/ai_panel.cljs:257-266`) already extracts shape ids from
      `:redo-changes` for the violations watcher. **Reuse it rather than writing
      a second copy** — extract it into this ns and have `ai_panel` consume it

## Checklist

- [x] Write tests first: `frontend/test/frontend_tests/data/session_events_test.cljs`
  - [x] a commit tagged `:position-data` is dropped
  - [x] a machine font-repair commit (`fix-deleted-fonts` origin) is dropped
  - [x] consecutive commits sharing an `:undo-group` coalesce into ONE event
  - [x] commits with no `:undo-group`, and commits with *different* groups, stand alone
  - [x] each known change type maps to a stable kind + human label; an UNKNOWN
        change type degrades to a generic label carrying the raw keyword rather
        than throwing
  - [x] `:mod-obj` is classified by its `:operations` attrs (geometry vs style
        vs layout vs rename vs token vs text)
  - [x] undo/redo are recognised via `:origin` (the one thing it is good for)
  - [x] event ordering is stable and monotonic by `:created-at`
- [x] Define the event schema (`:id :at :who :profile-id :session-id :provider :model :kind :label :shape-ids :origin :undo-group :raw-count`)
- [x] Implement `commit->event` (classify one commit map)
- [x] Implement `recordable?` (the noise filter — by tag + machine origin + empty changes)
- [x] Implement `coalesce` (fold a commit sequence into a semantic timeline by `:undo-group`)
- [x] Implement `timeline->prompt-text` (compact rendering for the model; this is what Phase 09 feeds)
- [x] Register the new test ns in BOTH spots of `frontend/test/frontend_tests/runner.cljs` (the `:require` list AND the `test-namespaces` vector — adding only the require compiles it but never runs it)
- [x] Confirm the new test names appear in `node target/tests/test.js` output — all 18 confirmed present
- [x] Extract `touched-shape-ids` out of `ai_panel.cljs` into this ns and have the
      violations watcher consume it (no second copy)
- [x] Lint + format (`/opt/utils/bin/clj-kondo --lint <paths>`, `/opt/utils/bin/cljfmt check <paths>` in the devenv container) — 0 errors / 0 warnings, formatted
- [x] `compile test` (0 warnings) → **944 tests / 2895 assertions / 0 failures**
- [x] `compile main` (0 warnings) — required because the test build does not
      compile `ai_panel.cljs`, so a break there would pass tests silently
- [x] Human approval received — Santi 2026-07-25 ("So far it's OK. I will review
      it later once everything is done"), with the work moved onto its own branch
- [x] Committed with a gitmoji commit

## After Finish

- [x] Rename this file: `todo-` → `done-` prefix
- [x] Update README.md phase links to match new filename
- [x] Note any follow-up items or discoveries below
- [x] Check if next phase can proceed or needs adjustment — **Phase 02 can
      proceed unchanged.** It was already the phase that adds `:profile-id` /
      `:session-id` to the commit map, and `commit->event` already reads and
      passes through those keys plus `:who` / `:provider` / `:model`, pinned by
      `commit->event-preserves-explicit-attribution`. So Phase 02 is now purely
      an upstream change with a consumer waiting for it.

## Follow-ups discovered

- **Branch:** this and subsequent phases live on
  `feature/ai-skills-prototype-session-recording`, to be reconciled into
  `feature/ai-skills-prototype` by PR. Note git **cannot** name a branch
  `feature/ai-skills-prototype/session-recording` — `refs/heads/feature/ai-skills-prototype`
  exists as a file and cannot also be a directory.
- **`:fix-obj` is unclassified.** It maps to `:other` today. It is a repair
  change type (`changes.cljc:220`) and is probably noise that belongs in
  `recordable?`'s denylist, but no call site was confirmed during this phase, so
  it was left visible rather than silently dropped. Decide when Phase 04 sees
  real traffic.
- **`:reg-objects` likewise falls to `:other`.** It is geometry registration that
  rides along with other changes; if it shows up as its own event in real
  sessions it should join the noise filter.
- **`:assign` operations carry no `:attr`**, so a `:mod-obj` built from them
  classifies as `:other`. Not observed in practice during this phase; worth
  re-checking against real traffic in Phase 04.
- **Coalescing does not span the flush boundary.** `coalesce` is pure over a
  sequence, so if Phase 07 flushes mid-gesture, one drag could persist as two
  events. Phase 07 should coalesce before flushing, not after.

## Files

- `frontend/src/app/main/data/workspace/session_events.cljs` — new; schema + pure fns
- `frontend/test/frontend_tests/data/session_events_test.cljs` — new; the pinning tests
- `frontend/test/frontend_tests/runner.cljs` — register the ns (both spots)

## Notes

### The `:origin` finding (the reason this phase changed shape)

`:origin` cannot carry the semantic label. It is `(ptk/type origin)`, and
`origin` is passed by only **6 of 133** `commit-changes` call sites, so ~95% of
commits — every ordinary interaction — arrive as `:potok.v2.core/undefined`
(potok's `type` returns `::undefined` for non-`Event` values,
`potok/v2/core.cljs:82-86`). The six that *do* pass it are mostly not user
intentions: undo ×3, machine font-repair ×2, the `:position-data` writeback, and
one library sync.

Classification therefore reads the **changes**: `:redo-changes` `:type` plus, for
`:mod-obj`, the `:operations` `:attr` names. Strictly better — schema-validated
on every commit, the real data model rather than an incidental event name, and
identical for human and agent writes. `:origin` is kept as a secondary signal,
where it earns its place: recognising undo/redo and filtering machine repair.

### Design decisions worth knowing

- **Geometry has the LOWEST classification precedence.** Penpot recalculates
  position/selrect as a side effect of nearly everything, so a commit touching
  both `:fills` and `:x` is a restyle, not a move. Getting this backwards would
  have made almost every event look like a move.
- **`:at` is normalized to epoch millis at the boundary.** `:created-at` is a
  `js/Date`; events are plainly serializable for phases 06/07 and cheap to
  order.
- **Coalescing has three guards, each pinned by a test:** only *adjacent*
  commits merge (a group interrupted by other work does not swallow the
  interruption); a nil `:undo-group` never merges, including with another nil
  (hence the per-index discriminator); and commits from **different actors never
  merge even inside one group** — attribution is the point of a recording, so
  merging across people would falsify it.
- **Unknown change types degrade to `:other` and name the raw keyword in the
  label**, so a new upstream change type is visible rather than silently
  swallowed.

### Fixture realism

`fixtures-satisfy-the-real-change-schema` asserts every change form used in the
tests against `cpc/valid-change?` — the same validator `dch/commit` asserts with
(`changes.cljs:167-171`). This caught a real defect while writing the phase: the
first `:add-obj` fixture omitted the required `:obj` and `:frame-id` and would
have been rejected by the real pipeline. Without this test the suite would have
been pinning a fantasy. The invalid future-type fixture is asserted to be
invalid, which is *why* the degrade path exists.

### Not done here (deliberately)

Live verification against a running Penpot is Phase 04's job — this phase has no
subscription to verify. The Chrome extension dropped mid-phase, so the fixture
realism test above stands in as the stronger guarantee: it validates against the
authoritative schema rather than one observed session.

Build commands (there is no Makefile in this repo — the skill template's
`make lint/{app}` does not exist here):

```
docker exec -w /home/penpot/penpot/frontend penpot-devenv-ws0-main \
  sudo -EH -u penpot clojure -M:dev:shadow-cljs compile test
docker exec -w /home/penpot/penpot/frontend penpot-devenv-ws0-main \
  sudo -EH -u penpot node target/tests/test.js
```
