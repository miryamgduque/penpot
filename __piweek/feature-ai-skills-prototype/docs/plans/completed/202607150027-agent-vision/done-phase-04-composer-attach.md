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

- [x] Re-read the composer: `ai_panel.cljs:333-387` (`.composer`, `.composer-row`, textarea at
      `:370-374`, send/stop `icon-button*` at `:375-387`)
- [x] Re-read `send` (`ai_panel.cljs:254-265`) and the event `send-message`
      (`data/workspace/ai_panel.cljs:185-251`) — the canonical user message is built at `:205`
- [x] Re-read `webapi/read-file-as-data-url` (`webapi.cljs:60-62`) — rx observable, already
      carries the Safari/WebKit repeated-mimetype fix (`fix-webkit-data-uri` `:26-30`)
- [x] Confirm `accept-image-types` (`data/workspace/media.cljs:40-41`) is the right accept
      string — but **do not** pull in the rest of `media.cljs`; that is the upload-to-storage
      path this feature deliberately avoids

## Checklist

- [x] Attach button in `.composer-row` next to send, using
      `ui/components/file_uploader.cljs:13-42`. Note its contract quirk: `on-selected` is
      expected to **return an event it `st/emit!`s itself** (`:23-27`) — picker-only. Use the raw
      hidden-input pattern directly if that fights the rx flow
- [x] **Gate the attach affordance on `(dai/vision? provider model)`** — disabled with a reason
      when the active model is text-only, never hidden (a control that vanishes reads as a bug)
      and never a silent no-op. *Moved here from Phase 03*, which delivered the predicate but
      was sequenced before the button existed. Note the model can change *after* attaching —
      `strip-images` already handles that end (Phase 03); this is only about not offering the
      control in the first place.
      **Post-refresh (2026-07-15): 12 of the 14 catalogued models now see.** Only Zhipu's
      `glm-5.2` and `glm-4.7` are text-only — so this is a narrower branch than Phase 03 found,
      but a *sharper* one to demo: within Zhipu, `glm-5v-turbo` sees and `glm-5.2` does not, so
      the affordance has to react to the model rather than the provider. Test with that pair
- [x] Paste handler on the textarea (`:370-374`). `clipboard.cljs:266-290` (`create-paste-from-blob`)
      is the template for pulling image blobs off the event. **No coordination needed with the
      canvas paste flow** — `viewport/actions.cljs:585-586` already declines `TEXTAREA` targets
- [x] Drag-and-drop on `.composer` (`:333`) — `:on-drop` / `:on-drag-over`, with a visible
      drop-target state
- [x] Enforce **max 5**, and enforce it identically across all three entry paths. Reject
      non-image types with a reason, not silence
- [x] Thumbnail strip above the input: each image individually removable, with an accessible name
- [x] Thread images through: `send` (`:254-265`) → `send-message` arglist (`:197`) → the canonical
      message at `:205`
- [x] `append-message` (`:71-79`) grows an `:images` field so the user's own bubble shows what
      was sent — the transcript must not lie about what the model received
- [x] Verify live: paste a screenshot, ask the model to describe it, confirm it does. **This is
      the acceptance moment for the whole plan's foundation** → ✅ **PASSED** — see Findings
- [x] Verify nothing hits media storage — watch the network tab for upload calls
      → zero upload/media requests; the transcript is empty after a reload, as designed
- [x] i18n keys; `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
      → **no i18n needed**: this panel uses literal strings throughout (`"Ask the agent…"`,
      `"Stop generating"`), so a lone `tr` call here would be the odd one out. kondo adds **0
      new** warnings (the 4 it reports are pre-existing — verified identical against a stashed
      tree) · cljfmt clean · `[:main]` 0 warnings · **471 tests, 1938 assertions, 0 failures**
- [x] Human approval; commit `:sparkles: Attach images to the agent chat`

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] Record the real payload size of a 5-image message — it is Phase 05's starting number, and
      it may prove Phase 05 is urgent rather than defensive
      → **it does.** 66% of the cap on one realistic message; 743% on a photographic one. Phase
      05's own file now leads with this and reorders itself around it

## Findings

### ✅ The acceptance moment: an image reached a live model

Rendered a canvas image the model could not possibly guess — dark blue background, yellow
circle upper-left, red triangle upper-centre, and the string **`VERIFY-7742`** — attached it,
and asked Claude Haiku 4.5 to describe it. It replied:

> "**Yellow circle** (ellipse) — positioned in the upper left … **Red triangle** — a large
> triangle positioned in the upper center, filled with red … **Dark blue background** …
> **White text "VERIFY-7742"**"

Four for four, including the arbitrary string read verbatim. **The Phase 02 codecs are correct
against a live Anthropic provider.** Cost: 2 calls, 9.0k in / 241 out, ~$0.01.

### 🚩 Phase 05 is urgent, not defensive

The number this phase was asked to produce, and it is worse than expected:

| Case (5 images) | base64 chars | % of the 4M cap |
|---|---|---|
| 5 × 1440×900 UI screenshots (388 KB each) | 2,649,360 | **66%** |
| 5 × 1440×900 incompressible (photo/noise) | 29,732,536 | **743%** — over on one message |

**Five screenshots is not a stress test — it is exactly what a "attach up to 5 images" button
invites.** At 66% for the images alone, turn 1 is already tight once the system prompt, tool
schemas, and history are added; turn 2 re-sends the same images and goes over. A phone photo or
a photographic mockup breaks it on the *first* message.

Phase 05 should treat downscale-on-attach as the load-bearing fix, not the history pruning —
the accumulation problem is real but the single-message problem arrives first.

### Verified behaviours

| Case | Result |
|---|---|
| Picker (hidden input) | ✅ thumbnail renders, send enables |
| Paste into the composer | ✅ image attaches; no race with paste-onto-canvas |
| Image with no text | ✅ send enables from the image alone |
| Cap at 5 | ✅ 6 offered → 5 taken, attach button disables at the cap |
| Over-cap message | ✅ "Only 5 images can be attached at once." |
| Non-image (`.txt`) | ✅ rejected: "Only images can be attached (PNG, JPEG or WebP)." |
| Individual removal | ✅ all 5 removed one by one |
| Vision gate, **same provider** | ✅ `glm-5.2` disabled ("glm-5.2 can't read images"), `glm-5v-turbo` enabled, flips back correctly |
| Attach, then switch to a blind model | ✅ thumbnails stay, hint appears: "glm-5.2 can't read images — they won't be sent." |
| Media storage | ✅ zero upload requests |

### Not verified — the honest gap

**The OpenAI codec has still never met a live provider.** Only an Anthropic key is configured on
this machine, so `encode-openai`'s image block (`{:type "image_url" :image_url {:url "data:…"}}`)
remains verified against documentation and unit tests only — exactly the state Phase 02 was in.
If that dialect is subtly wrong, nothing here would have caught it. Needs a run with an OpenAI,
Zhipu, or Moonshot key.

## Files

- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — attach button, paste, drop, thumbnails
- `frontend/src/app/main/ui/workspace/ai_panel.scss` — thumbnail strip, drop state
- `frontend/src/app/main/data/workspace/ai_panel.cljs` — thread `:images` through send/append

## Notes

- **Do not persist.** Attachments are dropped from the saved conversation by design, so they
  vanish on reload. If that proves annoying in practice, it is a new plan, not a scope expansion.
- SCSS gotcha, will bite: **the devenv SCSS watch does not pick up any `.scss` edit.** Run
  `docker exec -w /home/penpot/penpot/frontend penpot-devenv-ws0-main node ./scripts/build-app-assets.js`
  (~3s) and reload. Symptom is a completely unstyled panel.
- Size guarding is deliberately **not** here — Phase 05 owns it, so this phase stays reviewable.
  Expect to be able to break the 4M cap with 5 large photos before then. That is fine and
  expected; note the number rather than fixing it early.
