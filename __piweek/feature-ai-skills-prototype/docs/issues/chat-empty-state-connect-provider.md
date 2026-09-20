# Chat tab: redesign the "no AI provider" empty state

**Type:** enhancement · **Area:** workspace / Agent panel (Chat tab) · **Status:** ✅ resolved
**Created:** 2026-07-14 · **Resolved:** 2026-07-14

> Implemented: `unplug` icon added to the DS; `chat-tab*`'s no-provider check lifted to
> `ai-panel*` (replacing the whole body incl. tabs) as a `connect-empty*` card with title,
> subtitle and a primary "Connect a provider" button. Later refined (copy, tinted icon,
> position) per follow-up feedback.

## Summary

When no AI provider is connected, the Chat tab's empty state should be a single centered
card — an **unplug** icon, a title, a subtitle, and a **Connect an AI provider** button —
instead of today's split layout (a "Start a conversation…" transcript line + a one-line
"Connect an AI provider in Settings → Integrations" note above the composer).

## Motivation

First-run clarity: a user with no provider connected can't chat at all, so the panel should
lead with a single, obvious call to action rather than showing a chat affordance that doesn't
work yet.

## Current behavior

`chat-tab*` in [`ai_panel.cljs`](../../../../frontend/src/app/main/ui/workspace/ai_panel.cljs):
- transcript area shows `transcript-empty` → *"Start a conversation with the design agent."*
  regardless of provider state;
- composer shows either the model-picker + textarea (provider connected) or a `no-provider`
  one-liner: *"Connect an AI provider in Settings → Integrations to start chatting."*

So the no-provider state is two disconnected fragments, and the (non-functional) composer
chrome is still visible.

## Desired behavior (target mockup)

When **no provider is connected** (the model pool is empty), replace the **whole** Chat body
with one centered empty state (hide the context chip and composer):

- **Icon:** Lucide **unplug**, in the circular muted wrapper (same look as the DS
  `empty-state*` `.icon-wrapper`).
- **Title:** "Connect an AI provider" (bold).
- **Subtitle:** "Bring your own key to start using the agent in this and every file." (muted).
- **Button:** "Connect an AI provider" → navigates to `#/settings/integrations`.

The existing *"Start a conversation…"* empty state stays for the **provider-connected but no
messages** case.

> Header note: the mockup's card title reads "All-in Penpot", but the panel header is
> **"Agent"** (confirmed) — do **not** change it.

## Implementation notes

1. **Add the `unplug` icon to the DS** (not currently registered):
   - Add `resources/images/icons/unplug.svg` — Lucide `unplug`, re-scaled to the repo's
     **16px viewBox, stroke** convention (see [`bot.svg`](../../../../frontend/resources/images/icons/bot.svg)
     as the reference; Lucide ships it at 24px so paths need scaling ×2/3).
   - Add `(def ^:icon-id unplug "unplug")` in
     [`icon.cljs`](../../../../frontend/src/app/main/ui/ds/foundations/assets/icon.cljs)
     (alphabetical). The sprite build (`compileSvgSprites`) and `icon-list` pick it up.
2. **Empty-state layout:** the DS `empty-state*`
   ([`empty_state.cljs`](../../../../frontend/src/app/main/ui/ds/product/empty_state.cljs)) is
   **icon + single text only** — no title/subtitle/button. Build a small custom layout in the
   panel (reuse the DS `.icon-wrapper` circular style to stay on-system) with title, subtitle,
   and a button.
3. **Restructure `chat-tab*`:** branch on `(empty? pool)` — render the connect-provider empty
   state (no context chip, no composer) vs. the normal chat (context chip + transcript +
   composer). Add `ai_panel.scss` styles for the centered card.

## Acceptance criteria

- No provider connected → Chat tab shows only the centered card: unplug icon, "Connect an AI
  provider", the subtitle, and a button that opens Settings → Integrations. No composer/context.
- Provider connected, no messages → unchanged ("Start a conversation…").
- `unplug` is a registered DS icon (`i/unplug`) rendering from the sprite.
- Panel header remains "Agent".
- Compiles clean; verified in the devenv workspace.

## Out of scope

- Any change to the provider connection flow itself (lives on `/settings/integrations`).
- The Skills tab.
