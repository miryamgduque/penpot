# Phase 03 — Vision capability per model

**Status:** todo
**Depends on:** Phase 02

## Goal

Not every model can see. The chat lets the user switch models mid-conversation, so "can this
model take an image?" is a live, changing property — not a build-time constant. Make it explicit
in the catalog, and make the failure mode a **clear affordance instead of a provider error**.

This phase is small but it is the difference between a feature and a support ticket.

## Before Start

- [ ] Re-read the curated catalog `ai-provider-models` in
      `frontend/src/app/main/ui/settings/integrations.cljs` — per-provider
      `{:id :label :context}`, hand-maintained, already rendered with context-window info
- [ ] Note the standing caveat: those model IDs and context sizes are **best-effort
      placeholders**. Do not treat the existing rows as verified truth while adding a field to
      them
- [ ] Check how the model picker in `ai_panel.cljs` reads the enabled pool

## Checklist

- [ ] Add `:vision` to each catalog entry. **Verify each model's actual capability** rather than
      guessing from the name — and if a value is a guess, mark it as one in a comment
- [ ] Surface it in the settings list next to the context window, so the user can see which of
      their enabled models can see
- [ ] The composer's attach affordance is **disabled with a reason** when the active model is
      text-only — not hidden (a control that vanishes reads as a bug), and never a silent no-op
- [ ] Decide and record the switch-mid-conversation behaviour: the user attaches an image on a
      vision model, then switches to a text-only one. Options: block the switch, warn, or strip
      images from the re-encode. **Pick one and write down why** — this is a real interaction,
      not a hypothetical, because history is re-encoded from canonical on every round
- [ ] i18n keys for any new strings
- [ ] `clj-kondo`, `cljfmt`, `shadow-cljs compile main` clean
- [ ] Human approval; commit `:sparkles: Track vision capability per model`

## After Finish

- [ ] Rename `todo-`→`done-`, update README link
- [ ] Note which models were verified vs. assumed — the next person will trust this field

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
