# Phase 03 — Vision capability per model

**Status:** todo
**Depends on:** Phase 02

## Goal

Not every model can see. The chat lets the user switch models mid-conversation, so "can this
model take an image?" is a live, changing property — not a build-time constant. Make it explicit
in the catalog, and make the failure mode a **clear affordance instead of a provider error**.

This phase is small but it is the difference between a feature and a support ticket.

## Before Start

- [x] Re-read the curated catalog `ai-provider-models` in
      `frontend/src/app/main/ui/settings/integrations.cljs` — per-provider
      `{:id :label :context}`, hand-maintained, already rendered with context-window info
- [x] Note the standing caveat: those model IDs and context sizes are **best-effort
      placeholders**. Do not treat the existing rows as verified truth while adding a field to
      them → **this paid off: two context values were wrong and one model is discontinued**
- [x] Check how the model picker in `ai_panel.cljs` reads the enabled pool
      → `provider-pool` flattens providers into `{:provider :model}`; `settings` carries the
      active pair. So a `(provider, model) → bool` predicate is the right shape

## Checklist

- [x] Add `:vision` to each catalog entry. **Verify each model's actual capability** rather than
      guessing from the name — and if a value is a guess, mark it as one in a comment
      → **all 12 verified against provider docs; nothing is a guess.** See Findings
- [x] Surface it in the settings list next to the context window, so the user can see which of
      their enabled models can see → an "Images" badge, rendered only when true
- [ ] ~~The composer's attach affordance is **disabled with a reason** when the active model is
      text-only~~ → **MOVED TO PHASE 04.** Plan error on my part: this phase is sequenced
      *before* the phase that builds the attach button, so there is no affordance to gate yet.
      Phase 04 builds the control and consumes `dai/vision?` when it does
- [x] Decide and record the switch-mid-conversation behaviour: the user attaches an image on a
      vision model, then switches to a text-only one. Options: block the switch, warn, or strip
      images from the re-encode. **Pick one and write down why** — this is a real interaction,
      not a hypothetical, because history is re-encoded from canonical on every round
      → **strip, with a note left in place.** See Decision below
- [x] i18n keys for any new strings → `models.vision`, `models.vision-hint`
- [x] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
      → kondo 0/0 · cljfmt clean · `[:main]` 0 warnings · **468 tests, 1904 assertions, 0
      failures** (11 new, all confirmed running by name)
- [x] Human approval; commit `:sparkles: Track vision capability per model`

## Decision — how a switch to a text-only model degrades

**Strip every image from the history at encode time, leaving a note in its place.**

The other two options were considered and rejected:

- **Block the switch** — punishes a legitimate action. The user may be switching for cost or
  speed and not care about the images at all; refusing outright makes them clear the chat.
- **Warn** — a warning that is followed by the same provider error is just a slower failure.

Stripping is the only option that keeps the conversation *alive*. It also lands on the exact
mechanism Phase 05 needs for its budget work (strip old images from history) — the two phases
share `strip-images` rather than growing two competing image-pruning paths.

Two details that matter more than they look:

1. **It must apply to the whole history, not the next message.** History is re-encoded from
   canonical on every round, so switching to a text-only model retroactively re-encodes images
   from ten turns ago. A next-message-only fix would look correct and fail on turn 2.
2. **The note is load-bearing.** Silently dropping an image leaves the model answering questions
   about something it can no longer see *and no longer knows existed* — an invitation to
   confabulate. `[2 images omitted: the selected model cannot read images]` is honest and cheap.

**Unknown models default to `false`** (a user can enable a model outside the catalog). The
asymmetry decides it: guessing `true` costs a provider error mid-conversation on content the
user already sent; guessing `false` only greys out a button on a model that might have coped.

## Findings — the catalog, verified 2026-07-15

Verified against provider documentation. The Anthropic Models API
(`capabilities.image_input.supported`) is the authoritative source for Claude, but this machine
has no API key and no `ant` CLI, so the vision docs page was used instead.

| Provider | Models | Vision | Note |
|---|---|---|---|
| Anthropic | opus-4.8, sonnet-5, haiku-4.5 | ✅ all | opus/sonnet are high-res tier (2576px, ≤4784 visual tokens); haiku is standard (1568px) |
| OpenAI | gpt-5, gpt-5-mini, gpt-4.1 | ✅ all | **no separate vision SKU** — the "look for the vision variant" instinct actively misleads here |
| Zhipu | glm-4.6, glm-4.5, glm-4.5-air | ❌ none | vision ships as **GLM-4.5V / 4.6V** — different model ids, not offered |
| Moonshot | kimi-k2-0905-preview, moonshot-v1-128k, moonshot-v1-32k | ❌ none | vision ships as **`moonshot-v1-*-vision-preview`** — different ids, not offered |

**Half the catalog is text-only.** The degradation path is not an edge case; it is what six of
twelve models do.

### Three things the verification caught that were not the assignment

1. **Two context values were wrong.** `claude-opus-4-8` and `claude-sonnet-5` were listed at
   **200K**; both are **1M**. Fixed — this phase renders vision *next to* the context window,
   and shipping a verified field beside a verified-wrong one is worse than not shipping.
   (Haiku 4.5's 200K was correct.)
2. **`kimi-k2-0905-preview` is discontinued** (2026-05-25; the whole kimi-k2 series). It is a
   dead row in a live picker. **Left in place with a TODO** — choosing the replacement
   (kimi-k2.6) is a product call, not a silent swap, and the UI already keeps
   enabled-but-uncatalogued models visible so removing it later is safe.
3. **Anthropic's own docs state the Phase 05 problem verbatim**: base64 images are re-sent in
   full on every turn as history grows, and their documented answer is the **Files API**
   (upload once, reference by `file_id`). Phase 05 should weigh that against decision 2
   ("local-only, never stored") — the Files API is *Anthropic's* storage, not Penpot's, so it
   may not actually violate the decision. It is provider-specific, though: there is no
   equivalent on the OpenAI-compatible path.

Also useful for Phases 05/07: image cost is **`⌈width/28⌉ × ⌈height/28⌉` visual tokens** — a
formula, not a guess. And the per-request image cap is 100 (200K-context models) or 600 — far
above our cap of 5.

## After Finish

- [x] Rename `todo-`→`done-`, update README link
- [x] Note which models were verified vs. assumed — the next person will trust this field
      → **none are assumed.** All 12 verified against provider docs; the sources are recorded in
      the catalog's own comments, and `ai-providers-test` pins each one so a careless edit fails
      loudly. The one field that *is* a judgement call is the unknown-model default (`false`),
      documented above

## Files

- `frontend/src/app/main/ui/settings/integrations.cljs` — `:vision` in the catalog + display
- `frontend/src/app/main/ui/workspace/ai_panel.cljs` — attach affordance reflects capability
- `frontend/translations/en.po` — new keys

## Notes

- The mid-conversation switch is the subtle one. Because every round re-encodes the whole history
  from the canonical model, switching to a text-only model **retroactively** re-encodes past
  images. Whatever is decided must hold for history, not just the next message.
- Related known gap, do not fix here: DS `icon-button*` has no accessible name until hover
  (aria-label → empty tooltip node, app-wide). The attach button will inherit it.
