# Phase 05 — Clickable shape refs in the transcript

**Status:** done

Adopts opencode's `file_path:line_number` convention: the agent already carries
"reference shapes by name" doctrine; this phase makes those references
clickable — click → `dws/select-shape` on canvas, exactly like the Audit tab's
⌖. The only phase in this plan that touches UI code.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Check if any gaps have been filled by other work since plan creation
- [ ] Re-read `markdown.cljs` (`safe-href` :30, `render-link` :48) and the Audit
      ⌖ handler (`ai_panel.cljs:843`) — confirm shapes select by uuid and how
      stale ids behave
- [ ] Check how the renderer is invoked from the transcript (does it accept
      options/handlers today, or only text?)

## Checklist

- [x] Write/update tests: markdown parser turns the chosen syntax into a
      shape-ref element; unknown scheme still sanitized; plain links unaffected
- [x] Choose syntax — recommendation: markdown link with a `shape:` scheme,
      `[Header](shape:<uuid>)`. `safe-href` currently BLOCKS it (allowlist is
      the sanitizer, good) — handle `shape:` explicitly in `render-link` as a
      button-styled span with an on-click, NEVER as a real `<a href>` (keeps
      the XSS posture: nothing new reaches `href`).
- [x] Renderer API: `markdown` component grows an optional `on-shape-click`
      (or a ref-resolver map) — default nil renders refs as plain text, so
      every other markdown consumer is untouched
- [x] Panel wiring: transcript passes a handler that emits
      `(dws/select-shape id)`; fail soft on deleted/unknown ids (no-op +
      optional toast — match whatever Audit ⌖ does on a pruned shape)
- [x] Doctrine line (one, in `native-tool-notes`): "When you mention a shape
      you created or changed, link it: `[Layer name](shape:<id>)` — the user
      can click it to select on canvas." Tool results already return ids.
- [x] SCSS for the ref chip (reuse Audit's look); remember
      `build-app-assets.js` after scss changes
- [x] Lint + typecheck pass (kondo; `compile test` + run; **`compile main`
      mandatory** — ai_panel/markdown are UI namespaces the test build skips)
- [x] Preview review: live turn (or console-injected transcript) showing a
      clickable ref selecting the shape; screenshot for review
- [x] Human approval received
- [ ] Committed with a gitmoji commit (e.g. `:sparkles: Shape references in the
      transcript select on click`)

## After Finish

- [ ] Rename this file: `todo-` → `done-` prefix
- [ ] Update README.md phase links to match new filename
- [ ] Note any follow-up items or discoveries below
- [ ] Check if next phase can proceed or needs adjustment

## Files

- `frontend/src/app/main/ui/components/markdown.cljs` — `shape:` handling in
  `render-link` + optional handler prop
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — pass the select handler
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — ref chip style
- `frontend/src/app/main/data/workspace/agent_skills.cljs` — one doctrine line
- `frontend/test/frontend_tests/…` — markdown tests (check where existing ones
  live; the component shipped with the chat-UX plan)

## Notes

- Keep the model's side cheap: uuids are long; the doctrine should say to link
  the shapes that MATTER (the board just built, the shape a defect names), not
  every mention — otherwise output tokens balloon.
- Cross-page refs: `select-shape` acts on the current page. If the id lives on
  another page, either no-op with a note or switch page first — decide at
  implementation against what Audit does (it already handles pruned shapes).
- ai_panel.scss has PRE-EXISTING prettier drift (keyframes) — keep it out of
  the commit.

- DEVIATION from plan: no `on-shape-click` renderer prop — event DELEGATION instead
  (button.shape-ref + data-shape-id from markdown.cljs; ONE delegated click handler on
  the transcript container), mirroring the observer-cards' data-id pattern. Renderer
  stays handler-free; other markdown consumers get an inert button.
- LIVE-VERIFIED on devenv :3450 (Native Dock Demo, demo profile): chip renders with ⌖,
  click selected recipe-card-3 (state hit:true) + zoom-to-selected; malformed
  `shape:not-a-uuid` stayed plain text; https links stayed anchors. Providers were
  faked via `ai_providers_fetched` in console (demo profile has no key) — note the
  panel's mount fetch CLOBBERS a pre-mount fake: emit it after the panel is open.
