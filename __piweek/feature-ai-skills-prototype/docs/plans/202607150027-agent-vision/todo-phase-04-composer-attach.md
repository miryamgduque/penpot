# Phase 04 — Attach images in the composer

**Status:** todo
**Depends on:** Phases 02, 03

## Goal

Let the user hand the agent up to 5 images: pick, paste, or drop. This is the first phase that
**proves the whole vision pipeline end-to-end** — canonical model, codec, provider, model — with
no WASM flag, no new tool, and no render involved. If a pasted screenshot gets described
correctly here, Phase 06 is only a question of where the bytes come from.

Local-only by decision: base64 in the browser, sent with the turn, **never** written to Penpot's
media storage.

## Before Start

- [ ] Re-read the composer: `ai_panel.cljs:333-387` (`.composer`, `.composer-row`, textarea at
      `:370-374`, send/stop `icon-button*` at `:375-387`)
- [ ] Re-read `send` (`ai_panel.cljs:254-265`) and the event `send-message`
      (`data/workspace/ai_panel.cljs:185-251`) — the canonical user message is built at `:205`
- [ ] Re-read `webapi/read-file-as-data-url` (`webapi.cljs:60-62`) — rx observable, already
      carries the Safari/WebKit repeated-mimetype fix (`fix-webkit-data-uri` `:26-30`)
- [ ] Confirm `accept-image-types` (`data/workspace/media.cljs:40-41`) is the right accept
      string — but **do not** pull in the rest of `media.cljs`; that is the upload-to-storage
      path this feature deliberately avoids

## Checklist

- [ ] Attach button in `.composer-row` next to send, using
      `ui/components/file_uploader.cljs:13-42`. Note its contract quirk: `on-selected` is
      expected to **return an event it `st/emit!`s itself** (`:23-27`) — picker-only. Use the raw
      hidden-input pattern directly if that fights the rx flow
- [ ] **Gate the attach affordance on `(dai/vision? provider model)`** — disabled with a reason
      when the active model is text-only, never hidden (a control that vanishes reads as a bug)
      and never a silent no-op. *Moved here from Phase 03*, which delivered the predicate but
      was sequenced before the button existed. Note the model can change *after* attaching —
      `strip-images` already handles that end (Phase 03); this is only about not offering the
      control in the first place.
      **Post-refresh (2026-07-15): 12 of the 14 catalogued models now see.** Only Zhipu's
      `glm-5.2` and `glm-4.7` are text-only — so this is a narrower branch than Phase 03 found,
      but a *sharper* one to demo: within Zhipu, `glm-5v-turbo` sees and `glm-5.2` does not, so
      the affordance has to react to the model rather than the provider. Test with that pair
- [ ] Paste handler on the textarea (`:370-374`). `clipboard.cljs:266-290` (`create-paste-from-blob`)
      is the template for pulling image blobs off the event. **No coordination needed with the
      canvas paste flow** — `viewport/actions.cljs:585-586` already declines `TEXTAREA` targets
- [ ] Drag-and-drop on `.composer` (`:333`) — `:on-drop` / `:on-drag-over`, with a visible
      drop-target state
- [ ] Enforce **max 5**, and enforce it identically across all three entry paths. Reject
      non-image types with a reason, not silence
- [ ] Thumbnail strip above the input: each image individually removable, with an accessible name
- [ ] Thread images through: `send` (`:254-265`) → `send-message` arglist (`:197`) → the canonical
      message at `:205`
- [ ] `append-message` (`:71-79`) grows an `:images` field so the user's own bubble shows what
      was sent — the transcript must not lie about what the model received
- [ ] Verify live: paste a screenshot, ask the model to describe it, confirm it does. **This is
      the acceptance moment for the whole plan's foundation**
- [ ] Verify nothing hits media storage — watch the network tab for upload calls
- [ ] i18n keys; `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [ ] Human approval; commit `:sparkles: Attach images to the agent chat`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] Record the real payload size of a 5-image message — it is Phase 05's starting number, and
      it may prove Phase 05 is urgent rather than defensive

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — attach button, paste, drop, thumbnails
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — thumbnail strip, drop state
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — thread `:images` through send/append
- `frontend/translations/en.po` — new keys

## Notes

- **Do not persist.** Attachments are dropped from the saved conversation by design, so they
  vanish on reload. If that proves annoying in practice, it is a new plan, not a scope expansion.
- SCSS gotcha, will bite: **the devenv SCSS watch does not pick up any `.scss` edit.** Run
  `docker exec -w /home/penpot/penpot/frontend penpot-devenv-ws0-main node ./scripts/build-app-assets.js`
  (~3s) and reload. Symptom is a completely unstyled panel.
- Size guarding is deliberately **not** here — Phase 05 owns it, so this phase stays reviewable.
  Expect to be able to break the 4M cap with 5 large photos before then. That is fine and
  expected; note the number rather than fixing it early.
